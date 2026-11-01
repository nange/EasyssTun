package com.easysstun

import android.content.Context
import android.content.pm.PackageInfo
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import com.easysstun.databinding.ItemAppBinding
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Adapter for the per-app proxy list.
 *
 * Label and icon loading is owned by the [AppViewHolder]: each bind cancels the
 * previous load and [onViewRecycled] cancels the active one, so a recycled row
 * can never show the label/icon of the package it used to display. Labels are
 * cached per package because filtering reads them for the whole list.
 */
class AppListAdapter(
    context: Context,
    private val lifecycleScope: CoroutineScope,
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {
    private val appContext = context.applicationContext
    private val packageManager = appContext.packageManager

    private var allApps: List<PackageInfo> = emptyList()
    private var filterJob: Job? = null
    private var saveJob: Job? = null
    private var proxyMode: String = Pref.PROXY_MODE_BYPASS

    private val differ = AsyncListDiffer(this, DIFF_CALLBACK)

    private val selectedApps = mutableSetOf<String>()
    private val selection = AppSelectionStore(Pref(appContext))

    /** Package name -> display label; the package list is stable while the screen is open. */
    private val labelCache = ConcurrentHashMap<String, String>()

    init {
        loadSelectedApps()
    }

    fun setProxyMode(mode: String) {
        if (proxyMode != mode) {
            proxyMode = mode
            loadSelectedApps()
            notifyItemRangeChanged(0, differ.currentList.size)
        }
    }

    private fun loadSelectedApps() {
        selectedApps.clear()
        selectedApps.addAll(selection.selected(proxyMode))
    }

    fun setAppList(list: List<PackageInfo>) {
        allApps = list
        differ.submitList(list)
    }

    fun filter(query: String) {
        filterJob?.cancel()
        val apps = allApps
        filterJob = lifecycleScope.launch(Dispatchers.IO) {
            val trimmedQuery = query.trim()
            val filtered = if (trimmedQuery.isEmpty()) {
                apps
            } else {
                apps
                    .filter {
                        val label = labelOf(it)
                        label.contains(trimmedQuery, ignoreCase = true) ||
                            it.packageName.contains(trimmedQuery, ignoreCase = true)
                    }
                    .sortedWith(
                        compareByDescending<PackageInfo> {
                            labelOf(it).contains(trimmedQuery, ignoreCase = true)
                        }
                    )
            }
            withContext(Dispatchers.Main) {
                differ.submitList(filtered)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val binding = ItemAppBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return AppViewHolder(binding)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        holder.bind(differ.currentList[position])
    }

    override fun getItemCount(): Int = differ.currentList.size

    override fun onViewRecycled(holder: AppViewHolder) {
        holder.cancelLoad()
        super.onViewRecycled(holder)
    }

    private fun labelOf(appInfo: PackageInfo): String =
        labelCache.getOrPut(appInfo.packageName) {
            appInfo.applicationInfo?.loadLabel(packageManager)?.toString() ?: appInfo.packageName
        }

    private fun saveSelectedApps() {
        val apps = selectedApps.toSet()
        // The store applies the write to the in-memory preferences immediately,
        // so a same-process reader (the VPN service) already sees it; no
        // blocking commit is needed on the main thread.
        selection.setSelected(proxyMode, apps)
        // Debounce only the reload signal to avoid rapid VPN restarts while the
        // user is ticking apps one by one.
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(300)
            ConfigChangeNotifier.notifyChanged(appContext)
        }
    }

    fun clearAllSelected() {
        selectedApps.clear()
        saveSelectedApps()
        notifyItemRangeChanged(0, differ.currentList.size)
    }

    inner class AppViewHolder(
        private val binding: ItemAppBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        private var loadJob: Job? = null

        fun bind(appInfo: PackageInfo) {
            binding.packageName.text = appInfo.packageName
            binding.checkbox.isChecked = appInfo.packageName in selectedApps

            // Show a placeholder until the asynchronous load completes; a recycled
            // holder must never keep the previous package's label/icon.
            binding.appName.text = labelCache[appInfo.packageName]
                ?: appContext.getString(R.string.app_name_placeholder)
            binding.appIcon.setImageResource(R.drawable.sync_24px)

            binding.checkbox.setOnClickListener {
                if (binding.checkbox.isChecked) {
                    selectedApps.add(appInfo.packageName)
                } else {
                    selectedApps.remove(appInfo.packageName)
                }
                saveSelectedApps()
            }

            cancelLoad()
            loadJob = lifecycleScope.launch {
                val label = withContext(Dispatchers.IO) { labelOf(appInfo) }
                val icon = withContext(Dispatchers.IO) {
                    appInfo.applicationInfo?.loadIcon(packageManager)
                }
                // The holder may have been rebound or recycled while the package
                // manager was loading; never overwrite the newer binding.
                if (!isActive) return@launch
                binding.appName.text = label
                binding.appIcon.setImageDrawable(icon)
            }
        }

        fun cancelLoad() {
            loadJob?.cancel()
            loadJob = null
        }
    }

    companion object {
        private val DIFF_CALLBACK = object : DiffUtil.ItemCallback<PackageInfo>() {
            override fun areItemsTheSame(oldItem: PackageInfo, newItem: PackageInfo): Boolean {
                return oldItem.packageName == newItem.packageName
            }

            override fun areContentsTheSame(oldItem: PackageInfo, newItem: PackageInfo): Boolean {
                return oldItem.packageName == newItem.packageName &&
                        oldItem.longVersionCode == newItem.longVersionCode &&
                        oldItem.lastUpdateTime == newItem.lastUpdateTime
            }
        }
    }
}
