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
import android.content.pm.PackageInfo
import android.text.Editable
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
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
                lifecycleScope.launch {
                    val appList = withContext(Dispatchers.IO) { getInstalledApps() }
                    adapter.setAppList(appList)
                }
            }
        }

        view.findViewById<MaterialButton>(R.id.btnClearAll).setOnClickListener {
            adapter.clearAllSelected()
        }

        lifecycleScope.launch {
            val progressBar = view.findViewById<ProgressBar>(R.id.progressBar)
            progressBar.visibility = View.VISIBLE
            val appList = withContext(Dispatchers.IO) {
                getInstalledApps()
            }
            adapter.setAppList(appList)
            progressBar.visibility = View.GONE
        }
        return view
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

        var appList = packages
            .filter { packageInfo ->
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