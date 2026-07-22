package com.easysstun
import android.content.Context
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@OptIn(ExperimentalSerializationApi::class)
class Pref(private val ctx: Context) {
    companion object {
        const val SERVICE_ENABLED = "enable"
        const val VERSION = "version"
        const val SERVER_PROFILES = "server_profiles"
        const val ACTIVE_SERVER_ID = "active_server_id"
        const val SELECTED_APPS = "selected_apps"
        const val SELECTED_APPS_BYPASS = "selected_apps_bypass"
        const val SELECTED_APPS_PROXY_ONLY = "selected_apps_proxy_only"
        const val PROXY_MODE_KEY = "proxy_mode"
        const val PROXY_MODE_BYPASS = "bypass"
        const val PROXY_MODE_PROXY_ONLY = "proxy_only"
        const val PREFS_UPDATED = "prefs_updated"
        const val CUSTOM_CA_FILE = "easyss_custom_ca.conf"
        const val DIRECT_FILE = "easyss_direct.conf"
        const val PROXY_FILE = "easyss_proxy.conf"
        const val TPROXY_FILE = "tproxy.conf"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

    var version: String
        get() {
            val currentTimestamp: String = System.currentTimeMillis().toString()
            return prefs.getString(VERSION, currentTimestamp) ?: currentTimestamp
        }
        set(value) {
            prefs.edit { putString(VERSION, value) }
        }

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(SERVICE_ENABLED, false)
        set(value) {
            prefs.edit { putBoolean(SERVICE_ENABLED, value) }
        }

    fun getApps(): Set<String> {
        return getAppsForMode(getProxyMode())
    }

    fun getAppsForMode(mode: String): Set<String> {
        val key = if (mode == PROXY_MODE_PROXY_ONLY) SELECTED_APPS_PROXY_ONLY else SELECTED_APPS_BYPASS
        val apps = prefs.getStringSet(key, null)
        if (apps != null) {
            Log.d("Pref", "getAppsForMode: mode=$mode, key=$key, found=$apps", Throwable("getAppsForMode call stack"))
            return apps
        }
        // Migration: if the mode-specific key is empty but the old key has data,
        // migrate old data to bypass mode key (since bypass was the only mode before)
        if (key == SELECTED_APPS_BYPASS) {
            val oldApps = prefs.getStringSet(SELECTED_APPS, null)
            if (oldApps != null && oldApps.isNotEmpty()) {
                Log.i("Pref", "getAppsForMode: migrating old data to $key: $oldApps", Throwable("migration call stack"))
                prefs.edit(commit = true) {
                    putStringSet(SELECTED_APPS_BYPASS, oldApps)
                    remove(SELECTED_APPS)
                }
                return oldApps
            }
        }
        Log.d("Pref", "getAppsForMode: mode=$mode, key=$key, returning empty", Throwable("getAppsForMode empty call stack"))
        return emptySet()
    }

    fun setAppsForMode(mode: String, apps: Set<String>) {
        val key = if (mode == PROXY_MODE_PROXY_ONLY) SELECTED_APPS_PROXY_ONLY else SELECTED_APPS_BYPASS
        prefs.edit { putStringSet(key, apps) }
    }

    fun getProxyMode(): String {
        return prefs.getString(PROXY_MODE_KEY, PROXY_MODE_BYPASS) ?: PROXY_MODE_BYPASS
    }

    fun setProxyMode(mode: String) {
        Log.i("Pref", "setProxyMode: $mode")
        prefs.edit { putString(PROXY_MODE_KEY, mode) }
        // Notify VPN service to restart with new mode
        val intent = android.content.Intent(PREFS_UPDATED).apply {
            setPackage(ctx.packageName)
        }
        ctx.sendBroadcast(intent)
    }

    fun getProfiles(): List<Profile> {
        val profilesJson = prefs.getString(SERVER_PROFILES, null)
        return if (profilesJson != null) {
            try {
                json.decodeFromString<List<Profile>>(profilesJson)
            } catch (e: Exception) { // Catches any exception during deserialization
                // In a real scenario, one might log e.message here.
                Log.e("Pref", "Error deserializing server profiles: ${e.message}", e) // Added logging
                emptyList() // Return empty list if deserialization fails
            }
        } else {
            emptyList()
        }
    }

    private fun saveProfiles(profiles: List<Profile>) {
        val profilesJson = json.encodeToString(profiles)
        prefs.edit {
            putString(SERVER_PROFILES, profilesJson)
        }
    }

    fun addProfile(profile: Profile) {
        val profiles = getProfiles().toMutableList()
        profiles.add(profile)
        saveProfiles(profiles)
    }

    fun updateProfile(profile: Profile) {
        val profiles = getProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index != -1) {
            profiles[index] = profile
            saveProfiles(profiles)
        }
    }

    fun deleteProfile(profileId: String) {
        val profiles = getProfiles().toMutableList()
        profiles.removeAll { it.id == profileId }
        saveProfiles(profiles)
        if (prefs.getString(ACTIVE_SERVER_ID, null) == profileId) {
            prefs.edit {
                remove(ACTIVE_SERVER_ID)
                apply()
            }
        }
    }

    fun setActiveServer(profileId: String) {
        prefs.edit { putString(ACTIVE_SERVER_ID, profileId) }
    }

    fun getActiveProfile(): Profile? {
        val activeId = prefs.getString(ACTIVE_SERVER_ID, null)
        return if (activeId != null) {
            getProfiles().find { it.id == activeId }
        } else {
            null
        }
    }

    fun getEasyssInfo(): easyssInfo {
        val activeProfile = getActiveProfile()
        val easyssInfo = easyssInfo()

        if (activeProfile == null) {
            easyssInfo.valid = false
            return easyssInfo
        }

        easyssInfo.valid = true
        easyssInfo.info = "${activeProfile.server}:${activeProfile.serverPort}"
        return easyssInfo
    }

}

data class easyssInfo(
    var valid: Boolean = false,
    var info: String = "",
)