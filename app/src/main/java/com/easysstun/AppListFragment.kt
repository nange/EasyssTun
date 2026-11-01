package com.easysstun

import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.easysstun.databinding.FragmentAppListBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListFragment : Fragment() {

    private var _binding: FragmentAppListBinding? = null
    private val binding get() = checkNotNull(_binding)

    private lateinit var pref: Pref
    private lateinit var adapter: AppListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAppListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pref = Pref(requireContext())

        adapter = AppListAdapter(requireContext(), viewLifecycleOwner.lifecycleScope)
        adapter.setProxyMode(pref.proxyMode)
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        // 条目布局简单，显式把池子上限设为 50，避免池子无谓增长。
        binding.recyclerView.recycledViewPool.setMaxRecycledViews(0, 50)
        binding.recyclerView.adapter = adapter

        binding.searchEditText.doAfterTextChanged { text ->
            adapter.filter(text?.toString().orEmpty())
        }

        // 先摘监听器再回填选中状态：否则初始化就会写偏好并触发重载。
        val currentMode = pref.proxyMode
        binding.proxyModeToggleGroup.clearOnButtonCheckedListeners()
        if (currentMode == Pref.PROXY_MODE_PROXY_ONLY) {
            binding.proxyModeToggleGroup.check(R.id.btnProxyOnlyMode)
            binding.modeDescription.text = getString(R.string.mode_proxy_only_desc)
        } else {
            binding.proxyModeToggleGroup.check(R.id.btnBypassMode)
            binding.modeDescription.text = getString(R.string.mode_bypass_desc)
        }
        binding.proxyModeToggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnBypassMode -> {
                        pref.proxyMode = Pref.PROXY_MODE_BYPASS
                        binding.modeDescription.text = getString(R.string.mode_bypass_desc)
                    }
                    R.id.btnProxyOnlyMode -> {
                        pref.proxyMode = Pref.PROXY_MODE_PROXY_ONLY
                        binding.modeDescription.text = getString(R.string.mode_proxy_only_desc)
                    }
                }
                // 分流配置已变：通知运行中的隧道重载。
                ConfigChangeNotifier.notifyChanged(requireContext())
                adapter.setProxyMode(pref.proxyMode)
                reloadList()
            }
        }

        binding.checkboxShowSystemApps.isChecked = pref.showSystemApps
        binding.checkboxShowSystemApps.setOnCheckedChangeListener { _, isChecked ->
            pref.showSystemApps = isChecked
            reloadList()
        }

        binding.btnClearAll.setOnClickListener {
            adapter.clearAllSelected()
        }

        reloadList(showProgress = true)
    }

    override fun onDestroyView() {
        binding.recyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }

    private fun reloadList(showProgress: Boolean = false) {
        // 需要 Context 的东西都在主线程取好；加载器只收纯值，才能放到 IO 线程。
        val packageManager = requireContext().packageManager
        val selfPackage = requireContext().packageName
        val showSystemApps = pref.showSystemApps
        val selectedApps = AppSelectionStore(pref).selected(pref.proxyMode)

        viewLifecycleOwner.lifecycleScope.launch {
            if (showProgress) _binding?.progressBar?.visibility = View.VISIBLE
            try {
                val appList = withContext(Dispatchers.IO) {
                    loadInstalledApps(packageManager, selfPackage, showSystemApps, selectedApps)
                }
                adapter.setAppList(appList)
            } finally {
                if (showProgress) _binding?.progressBar?.visibility = View.GONE
            }
        }
    }

    private fun loadInstalledApps(
        packageManager: PackageManager,
        selfPackage: String,
        showSystemApps: Boolean,
        selectedApps: Set<String>,
    ): List<PackageInfo> {
        val packages = packageManager.getInstalledPackages(
            PackageManager.MATCH_UNINSTALLED_PACKAGES
                    or PackageManager.GET_PERMISSIONS
                    or PackageManager.GET_PROVIDERS
                    or PackageManager.GET_META_DATA
        )

        return packages
            .filter { packageInfo ->
                val appInfo = packageInfo.applicationInfo
                appInfo != null &&
                        (showSystemApps || appInfo.flags and
                                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) &&
                        packageInfo.requestedPermissions?.contains(Manifest.permission.INTERNET) == true &&
                        packageInfo.packageName != selfPackage
            }
            .sortedWith(
                compareBy<PackageInfo> { it.packageName !in selectedApps }
                    .thenBy {
                        it.applicationInfo?.loadLabel(packageManager)?.toString()?.lowercase()
                            ?: it.packageName
                    }
            )
    }
}
