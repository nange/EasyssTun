package com.easysstun

import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.Manifest
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.text.Editable
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.textfield.TextInputEditText
import android.widget.ProgressBar
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: AppListAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_app_list, container, false)
        recyclerView = view.findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(requireContext())

        val pref = Pref(requireContext())

        adapter = AppListAdapter(requireContext(), lifecycleScope)
        adapter.setProxyMode(pref.getProxyMode())
        // Avoid overly large recycled view pool for a simple item layout
        recyclerView.recycledViewPool.setMaxRecycledViews(0, 50)
        recyclerView.adapter = adapter

        val searchEditText = view.findViewById<TextInputEditText>(R.id.searchEditText)
        searchEditText.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val toggleGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.proxyModeToggleGroup)
        val modeDescription = view.findViewById<TextView>(R.id.modeDescription)

        // Initialize toggle state WITHOUT triggering the listener
        val currentMode = pref.getProxyMode()
        toggleGroup.clearOnButtonCheckedListeners()
        if (currentMode == Pref.PROXY_MODE_PROXY_ONLY) {
            toggleGroup.check(R.id.btnProxyOnlyMode)
            modeDescription.text = getString(R.string.mode_proxy_only_desc)
        } else {
            toggleGroup.check(R.id.btnBypassMode)
            modeDescription.text = getString(R.string.mode_bypass_desc)
        }
        toggleGroup.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                when (checkedId) {
                    R.id.btnBypassMode -> {
                        pref.setProxyMode(Pref.PROXY_MODE_BYPASS)
                        modeDescription.text = getString(R.string.mode_bypass_desc)
                    }
                    R.id.btnProxyOnlyMode -> {
                        pref.setProxyMode(Pref.PROXY_MODE_PROXY_ONLY)
                        modeDescription.text = getString(R.string.mode_proxy_only_desc)
                    }
                }
                // Reload the app list with the new mode's selections
                adapter.setProxyMode(pref.getProxyMode())
                reloadList(requireView())
            }
        }

        val checkboxShowSystemApps = view.findViewById<MaterialCheckBox>(R.id.checkboxShowSystemApps)
        checkboxShowSystemApps.isChecked = pref.showSystemApps
        checkboxShowSystemApps.setOnCheckedChangeListener { _, isChecked ->
            pref.showSystemApps = isChecked
            reloadList(requireView())
        }

        view.findViewById<MaterialButton>(R.id.btnClearAll).setOnClickListener {
            adapter.clearAllSelected()
        }

        reloadList(view, showProgress = true)
        return view
    }

    private fun reloadList(rootView: View, showProgress: Boolean = false) {
        lifecycleScope.launch {
            val progressBar = rootView.findViewById<ProgressBar>(R.id.progressBar)
            if (showProgress) progressBar.visibility = View.VISIBLE
            try {
                val appList = withContext(Dispatchers.IO) {
                    getInstalledApps()
                }
                adapter.setAppList(appList)
            } finally {
                if (showProgress) progressBar.visibility = View.GONE
            }
        }
    }

    private fun getInstalledApps(): List<PackageInfo> {
        val pm = requireContext().packageManager

        val packages = pm.getInstalledPackages(
            PackageManager.MATCH_UNINSTALLED_PACKAGES
                    or PackageManager.GET_PERMISSIONS
                    or PackageManager.GET_PROVIDERS
                    or PackageManager.GET_META_DATA
        )

        val pref = Pref(requireContext())
        val selectedApps = pref.getApps()
        val showSystemApps = pref.showSystemApps

        var appList = packages
            .filter { packageInfo ->
                val appInfo = packageInfo.applicationInfo
                appInfo != null &&
                        (showSystemApps || appInfo.flags and
                                (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0) &&
                        packageInfo.requestedPermissions?.contains(Manifest.permission.INTERNET) == true &&
                        packageInfo.packageName != requireActivity().applicationContext.packageName
            }

        appList = appList.sortedWith(
            compareBy<PackageInfo>{
                selectedApps.contains(it.packageName) != true }
                .thenBy { it.applicationInfo?.loadLabel(pm)?.toString()?.lowercase() ?: it.packageName }
        )

        return appList
    }
}