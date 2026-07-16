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
        const val SOCKS_PORT_KEY = "socks_port"
        const val DEFAULT_SOCKS_PORT = "2080"
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
        return prefs.getStringSet(SELECTED_APPS, emptySet()) ?: emptySet()
    }

    fun getServerProfiles(): List<ServerProfile> {
        val profilesJson = prefs.getString(SERVER_PROFILES, null)
        return if (profilesJson != null) {
            try {
                json.decodeFromString<List<ServerProfile>>(profilesJson)
            } catch (e: Exception) { // Catches any exception during deserialization
                // In a real scenario, one might log e.message here.
                Log.e("Pref", "Error deserializing server profiles: ${e.message}", e) // Added logging
                emptyList() // Return empty list if deserialization fails
            }
        } else {
            emptyList()
        }
    }

    private fun saveServerProfiles(profiles: List<ServerProfile>) {
        val profilesJson = json.encodeToString(profiles)
        prefs.edit {
            putString(SERVER_PROFILES, profilesJson)
        }
    }

    fun addServerProfile(profile: ServerProfile) {
        val profiles = getServerProfiles().toMutableList()
        profiles.add(profile)
        saveServerProfiles(profiles)
    }

    fun updateServerProfile(profile: ServerProfile) {
        val profiles = getServerProfiles().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index != -1) {
            profiles[index] = profile
            saveServerProfiles(profiles)
        }
    }

    fun deleteServerProfile(profileId: String) {
        val profiles = getServerProfiles().toMutableList()
        profiles.removeAll { it.id == profileId }
        saveServerProfiles(profiles)
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

    fun getActiveServerProfile(): ServerProfile? {
        val activeId = prefs.getString(ACTIVE_SERVER_ID, null)
        return if (activeId != null) {
            getServerProfiles().find { it.id == activeId }
        } else {
            null
        }
    }

    fun getEasyssInfo(): easyssInfo {
        val activeProfile = getActiveServerProfile()
        val easyssInfo = easyssInfo()

        if (activeProfile == null) {
            easyssInfo.valid = false
            return easyssInfo
        }

        easyssInfo.valid = true
        easyssInfo.info = "${activeProfile.server}:${activeProfile.serverPort}"

        val localSocksPort = prefs.getString(SOCKS_PORT_KEY, DEFAULT_SOCKS_PORT) ?: DEFAULT_SOCKS_PORT
        easyssInfo.cmdList = activeProfile.buildCmdList(ctx.cacheDir, localSocksPort)
        return easyssInfo
    }

}

data class easyssInfo(
    var valid: Boolean = false,
    var info: String = "",
    var cmdList: List<String> = listOf(),
)