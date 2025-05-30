package com.musan.easysstun

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
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppListAdapter(
    private val context: Context,
    private val lifecycleScope: CoroutineScope
) : RecyclerView.Adapter<AppListAdapter.AppViewHolder>() {
    private var appList: List<PackageInfo> = emptyList()

    private val selectedApps = mutableListOf<String>()
    private val sharedPreferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    init {
        val savedApps = sharedPreferences.getStringSet("selected_apps", emptySet())
        selectedApps.addAll(savedApps ?: emptySet())
    }

    fun setAppList(list: List<PackageInfo>) {
        appList = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return AppViewHolder(view)
    }

    override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
        val appInfo = appList[position]
        holder.bind(appInfo)


    }

    override fun getItemCount(): Int {
        return appList.size
    }

    private fun saveSelectedApps() {
        sharedPreferences.edit().putStringSet("selected_apps", selectedApps.toSet()).apply()

        val intent = Intent("prefs_updated")
        context.sendBroadcast(intent)
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
}