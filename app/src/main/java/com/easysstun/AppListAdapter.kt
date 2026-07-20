package com.easysstun

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import androidx.preference.PreferenceManager // Add this import
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.edit
import androidx.recyclerview.widget.AsyncListDiffer
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListAdapter(
    private val context: Context,
    private val lifecycleScope: CoroutineScope
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {
    private var allApps: List<PackageInfo> = emptyList()
    private var filterJob: kotlinx.coroutines.Job? = null
    private var saveJob: kotlinx.coroutines.Job? = null
    private var proxyMode: String = Pref.PROXY_MODE_BYPASS

    private val differ = AsyncListDiffer(this, DIFF_CALLBACK)

    private val selectedApps = mutableListOf<String>()
    private val sharedPreferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

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

    private fun getSelectedAppsKey(): String {
        return if (proxyMode == Pref.PROXY_MODE_PROXY_ONLY) Pref.SELECTED_APPS_PROXY_ONLY else Pref.SELECTED_APPS_BYPASS
    }

    private fun loadSelectedApps() {
        selectedApps.clear()
        val savedApps = sharedPreferences.getStringSet(getSelectedAppsKey(), emptySet())
        selectedApps.addAll(savedApps ?: emptySet())
    }

    fun setAppList(list: List<PackageInfo>) {
        allApps = list
        differ.submitList(list)
    }

    fun filter(query: String) {
        filterJob?.cancel()
        filterJob = lifecycleScope.launch(Dispatchers.IO) {
            val filtered = if (query.isEmpty()) {
                allApps
            } else {
                allApps.filter {
                    val label = it.applicationInfo?.loadLabel(context.packageManager)?.toString() ?: ""
                    label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true)
                }
            }
            withContext(Dispatchers.Main) {
                differ.submitList(filtered)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val appInfo = differ.currentList[position]
        holder.bind(appInfo)


    }

    override fun getItemCount(): Int {
        return differ.currentList.size
    }

    private fun saveSelectedApps() {
        // Use commit() for synchronous disk write to avoid race conditions
        val key = getSelectedAppsKey()
        val apps = selectedApps.toSet()
        Log.i("AppListAdapter", "Saving $apps to key=$key (commit)")
        sharedPreferences.edit(commit = true) { putStringSet(key, apps) }
        // Debounce only the broadcast to avoid rapid VPN restarts
        saveJob?.cancel()
        saveJob = lifecycleScope.launch {
            delay(300)
            val intent = Intent(Pref.PREFS_UPDATED).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }

    fun clearAllSelected() {
        selectedApps.clear()
        saveSelectedApps()
        notifyItemRangeChanged(0, differ.currentList.size)
    }

    inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appNameTextView: TextView = itemView.findViewById(R.id.appName)
        val packageNameTextView: TextView = itemView.findViewById(R.id.packageName)
        val appIconImageView: ImageView = itemView.findViewById(R.id.appIcon)
        val checkBox: CheckBox = itemView.findViewById(R.id.checkbox)

        fun bind(appInfo: PackageInfo) {
            packageNameTextView.text = appInfo.packageName
            checkBox.isChecked = selectedApps.contains(appInfo.packageName)

            appNameTextView.text = context.getString(R.string.app_name_placeholder)
            appIconImageView.setImageResource(R.drawable.sync_24px)

            lifecycleScope.launch {
                val label = withContext(Dispatchers.IO) {
                    appInfo.applicationInfo?.loadLabel(context.packageManager).toString()
                }
                val icon = withContext(Dispatchers.IO) {
                    appInfo.applicationInfo?.loadIcon(context.packageManager)
                }

                // Update UI on the main thread
                withContext(Dispatchers.Main) {
                    appNameTextView.text = label
                    appIconImageView.setImageDrawable(icon)
                }
            }


            checkBox.setOnClickListener {
                Log.e("app", "selected " + appInfo.packageName)
                if (checkBox.isChecked) {
                    selectedApps.add(appInfo.packageName)
                } else {
                    selectedApps.remove(appInfo.packageName)
                }
                saveSelectedApps()
            }
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
