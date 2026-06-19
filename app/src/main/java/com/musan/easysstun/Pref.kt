package com.musan.easysstun
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
import java.util.UUID

@OptIn(ExperimentalSerializationApi::class)
class Pref(private val ctx: Context) {
    companion object {
        const val SERVICE_ENABLED = "enable"
        const val VERSION = "version"
        const val SERVER_PROFILES = "server_profiles"
        const val ACTIVE_SERVER_ID = "active_server_id"
    }

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

    init {
        migrateOldConfig()
    }

    private fun migrateOldConfig() {
        val oldServer = prefs.getString("easyss_server", null)
        // Check if migration has already happened by looking for the old key
        // or if there are already profiles.
        if (prefs.contains("easyss_server") && !oldServer.isNullOrBlank() && getServerProfiles().isEmpty()) {
            val id = UUID.randomUUID().toString()
            // Use old server address as name, or a default if blank (though oldServer check should prevent this)
            val name = if (oldServer.isNotBlank()) oldServer else "Default Server"
            val server = oldServer // Already checked for null/blank
            val serverPort = prefs.getString("easyss_serverport", "") ?: ""
            val password = prefs.getString("easyss_password", "") ?: ""
            val encryption = prefs.getString("easyss_encryption", "chacha20-poly1305") ?: "chacha20-poly1305"
            val proxyRule = prefs.getString("easyss_proxyrule", "auto") ?: "auto"
            val outbound = prefs.getString("easyss_outbound", "native") ?: "native"
            val logLevel = prefs.getString("easyss_loglevel", "info") ?: "info"
            val enableQuic = prefs.getString("easyss_enable_quic", "false") ?: "false"
            val ipv6Rule = prefs.getString("easyss_ipv6_rule", "auto") ?: "auto"
            val serverNameIndication = prefs.getString("easyss_sn", "") ?: ""
            val customCa = prefs.getString("easyss_custom_ca", "") ?: ""

            val migratedProfile = ServerProfile(
                id = id,
                name = name,
                server = server,
                serverPort = serverPort,
                password = password,
                encryption = encryption,
                proxyRule = proxyRule,
                outbound = outbound,
                logLevel = logLevel,
                enableQuic = enableQuic,
                ipv6Rule = ipv6Rule,
                serverNameIndication = serverNameIndication,
                customCa = customCa
            )
            addServerProfile(migratedProfile)
            setActiveServer(id)

            // Remove old keys after migration
            prefs.edit {
                remove("easyss_server")
                remove("easyss_serverport")
                remove("easyss_password")
                remove("easyss_encryption")
                remove("easyss_proxyrule")
                remove("easyss_outbound")
                remove("easyss_loglevel")
                remove("easyss_enable_quic")
                remove("easyss_ipv6_rule")
                remove("easyss_sn")
                remove("easyss_custom_ca")
                apply() // Ensure changes are persisted
            }
        }
    }

    var version: String
        get() {
            val currentTimestamp: String = System.currentTimeMillis().toString()
            return prefs.getString(VERSION, currentTimestamp) ?: currentTimestamp
        }
        set(value) {
            prefs.edit().putString(VERSION, value).apply()
        }

    var isServiceEnabled: Boolean
        get() = prefs.getBoolean(SERVICE_ENABLED, false)
        set(value) {
            prefs.edit().putBoolean(SERVICE_ENABLED, value).apply()
        }

    fun getApps(): Set<String> {
        return prefs.getStringSet("selected_apps", emptySet()) ?: emptySet()
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
        val editor = prefs.edit()
        editor.putString(SERVER_PROFILES, profilesJson)
        editor.apply()
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
        prefs.edit().putString(ACTIVE_SERVER_ID, profileId).apply()
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

        var sn = activeProfile.serverNameIndication
        if (sn.isBlank()) {
            sn = activeProfile.server
        }

        val localSocksPort = prefs.getString("socks_port", "2080") ?: "2080"
        val cmdList = mutableListOf(
            "-s", activeProfile.server,
            "-p", activeProfile.serverPort,
            "-k", activeProfile.password,
            "-m", activeProfile.encryption,
            "-proxy-rule", activeProfile.proxyRule,
            "-outbound-proto", activeProfile.outbound,
            "-l", localSocksPort, // Use the variable here
            "-t", "60", // This seems to be a fixed timeout, kept as is.
            "-log-level", activeProfile.logLevel,
            "-enable-quic=${activeProfile.enableQuic}",
            "-ipv6-rule", activeProfile.ipv6Rule,
            "-sn", sn,
            "-enable-tun2socks=false",
            "-daemon=false"
        )

        if (activeProfile.customCa.isNotBlank()) {
            val customCaFile = File(ctx.cacheDir, "easyss_custom_ca.conf")
            try {
                customCaFile.createNewFile()
                FileOutputStream(customCaFile, false).use { fos ->
                    fos.write(activeProfile.customCa.toByteArray())
                }
                cmdList.addAll(listOf("-ca-path", customCaFile.absolutePath))
            } catch (e: IOException) {
                // Log error or handle, for now, it will proceed without custom CA if file ops fail
            }
        }

        if (activeProfile.directFile.isNotBlank()) {
            val directFile = File(ctx.cacheDir, "easyss_direct.conf")
            try {
                directFile.createNewFile()
                FileOutputStream(directFile, false).use { fos ->
                    fos.write(activeProfile.directFile.toByteArray())
                }
                cmdList.addAll(listOf("-direct-file", directFile.absolutePath))
            } catch (e: IOException) {
            }
        }

        if (activeProfile.proxyFile.isNotBlank()) {
            val proxyFile = File(ctx.cacheDir, "easyss_proxy.conf")
            try {
                proxyFile.createNewFile()
                FileOutputStream(proxyFile, false).use { fos ->
                    fos.write(activeProfile.proxyFile.toByteArray())
                }
                cmdList.addAll(listOf("-proxy-file", proxyFile.absolutePath))
            } catch (e: IOException) {
            }
        }

        easyssInfo.cmdList = cmdList
        return easyssInfo
    }

}

data class easyssInfo(
    var valid: Boolean = false,
    var info: String = "",
    var cmdList: List<String> = listOf(),
)