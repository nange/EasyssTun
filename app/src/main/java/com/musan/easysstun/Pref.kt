package com.musan.easysstun
import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


class Pref(private val ctx: Context) {
    companion object {
        const val SERVICE_ENABLED = "enable"
        const val VERSION = "version"
    }

    val prefs = PreferenceManager.getDefaultSharedPreferences(ctx)

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
        set(value) = prefs.edit { putBoolean(SERVICE_ENABLED, value) }

    fun getApps(): Set<String>? {
        return prefs.getStringSet("selected_apps", HashSet<String>())
    }

    fun all(): Map<String, *> {
        return prefs.all
    }

    fun getEasyssInfo(): easyssInfo {
        val myMap = mutableMapOf<String, Any?>()
        var easyssInfo = easyssInfo()
        var easyss_server = prefs.getString("easyss_server", "")
        var easyss_serverport = prefs.getString("easyss_serverport", "")
        var easyss_password = prefs.getString("easyss_password", "")

        myMap["valid"] = false
        if (!easyss_server.isNullOrBlank() and !easyss_serverport.isNullOrBlank() and !easyss_password.isNullOrBlank()){
            easyssInfo.valid = true
            easyssInfo.info = easyss_server.toString() + ":" + easyss_serverport.toString()
        }

        var easyss_encryption = prefs.getString("easyss_encryption", "chacha20-poly1305")?:"aes-256-gcm"
        var easyss_proxyrule = prefs.getString("easyss_proxyrule", "auto")
        var easyss_outbound = prefs.getString("easyss_outbound", "native")
        var easyss_loglevel = prefs.getString("easyss_loglevel", "info")
        var easyss_disable_quic = prefs.getString("easyss_disable_quic", "false")
        var easyss_ipv6_rule = prefs.getString("easyss_ipv6_rule", "auto")
        var easyss_sn = prefs.getString("easyss_sn", "")

        if (easyss_sn.isNullOrBlank()) {
            easyss_sn = easyss_server
        }

//        val cmdList = mutableListOf<String>()
        var cmdList = listOf("-s", easyss_server,
            "-p", easyss_serverport,
            "-k", easyss_password,
            "-m", easyss_encryption,
            "-proxy-rule", easyss_proxyrule,
            "-outbound-proto", easyss_outbound,
            "-l", "2080",
            "-m", "chacha20-poly1305",
            "-t", "60",
            "-log-level", easyss_loglevel,
            String.format("-disable-quic=%s", easyss_disable_quic),
            "-ipv6-rule", easyss_ipv6_rule,
            "-sn", easyss_sn,
            "-enable-tun2socks=false",
            "-daemon=false")

        var easyss_custom_ca = prefs.getString("easyss_custom_ca", "")
        if (!easyss_custom_ca.isNullOrBlank()){
            val easyss_custom_ca_file = File(ctx.cacheDir, "easyss_custom_ca.conf")
            try {
                easyss_custom_ca_file.createNewFile()
                val fos = FileOutputStream(easyss_custom_ca_file, false)
                fos.write(easyss_custom_ca.toByteArray())
                fos.close()
            } catch (e: IOException) {

            }

            cmdList = cmdList + listOf("-ca-path", easyss_custom_ca_file.absolutePath)
        }

        easyssInfo.cmdList = cmdList as List<String>

        return easyssInfo
    }

}

data class easyssInfo(
    var valid: Boolean = false,
    var info: String = "",
    var cmdList: List<String> = listOf(),
)