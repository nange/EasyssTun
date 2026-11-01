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

/** 分应用代理列表：标签/图标异步加载，重绑与回收都会取消上一次加载，标签按包名缓存供过滤读取。 */
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

    /** 包名 → 显示名缓存：过滤要对整表读标签，不能每次现查。 */
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
        // 重新加载可能发生在应用升级之后：清掉缓存，避免继续显示过期标签。
        labelCache.clear()
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
        labelCache.computeIfAbsent(appInfo.packageName) {
            appInfo.applicationInfo?.loadLabel(packageManager)?.toString() ?: appInfo.packageName
        }

    private fun saveSelectedApps() {
        val apps = selectedApps.toSet()
        // 写入先落在内存中的偏好，同进程读者（VPN 服务）立刻可见，
        // 因此主线程无需阻塞式 commit。
        selection.setSelected(proxyMode, apps)
        // 只对重载信号做防抖：用户逐个勾选时避免隧道反复重启。
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

            // 异步加载完成前显示占位；回收的 holder 绝不能残留上一个包的标签/图标。
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
                // 加载期间 holder 可能已被重绑或回收：`isActive` 为假时不能再写入，
                // 否则会覆盖新绑定。
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
