package com.easysstun

import android.util.Log
import io.github.nange.easyss.config.SimpleConfig
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale

@Serializable
data class Profile(
    val id: String,
    val name: String,
    val server: String,
    val serverPort: String,
    val password: String,
    val encryption: String = "chacha20-poly1305",
    val proxyRule: String = "auto",
    val outbound: String = "native",
    val logLevel: String = "info",
    val enableQuic: String = "false",
    val ipv6Rule: String = "auto",
    val serverNameIndication: String = "",
    val customCa: String = "",
    val directFile: String = "",
    val proxyFile: String = "",
    val socksPort: String = DEFAULT_SOCKS_PORT
) {
    companion object {
        const val DEFAULT_SOCKS_PORT = "2080"
        const val STATS_PORT_OFFSET = 1000
        private const val STATS_URL_FORMAT = "http://127.0.0.1:%d/stats"

        /**
         * Stats URL for the default SOCKS port (i.e. 3080), used when no active
         * profile is available.
         */
        fun defaultStatsUrl(): String =
            String.format(Locale.ROOT, STATS_URL_FORMAT, DEFAULT_SOCKS_PORT.toInt() + STATS_PORT_OFFSET)
    }

    /**
     * The local HTTP stats endpoint port, derived from the SOCKS port
     * (matches the easyss native convention: HTTPPort = SocksPort + 1000).
     */
    fun statsPort(): Int =
        (socksPort.toIntOrNull() ?: DEFAULT_SOCKS_PORT.toInt()) + STATS_PORT_OFFSET

    /**
     * The local HTTP stats endpoint URL, e.g. http://127.0.0.1:3080/stats
     * for the default SOCKS port 2080.
     */
    fun statsUrl(): String = String.format(Locale.ROOT, STATS_URL_FORMAT, statsPort())
}

/**
 * Builds a SimpleConfig for the AAR-based easyss proxy from this profile.
 */
fun Profile.buildSimpleConfig(cacheDir: File): SimpleConfig {
    val config = SimpleConfig()

    config.setServer(server)
    config.setServerPort(serverPort.toLongOrNull() ?: 443L)
    config.setPassword(password)
    config.setMethod(encryption)
    config.setProxyRule(proxyRule)
    config.setOutboundProto(outbound)
    config.setLogLevel(logLevel)
    config.setEnableQUIC(enableQuic.equals("true", ignoreCase = true))
    config.setIPV6Rule(ipv6Rule)

    var sni = serverNameIndication
    if (sni.isBlank()) {
        sni = server
    }
    config.setSN(sni)

    config.setLocalPort(socksPort.toLongOrNull() ?: Profile.DEFAULT_SOCKS_PORT.toLong())

    if (customCa.isNotBlank()) {
        val customCaFile = File(cacheDir, Pref.CUSTOM_CA_FILE)
        try {
            customCaFile.createNewFile()
            FileOutputStream(customCaFile, false).use { fos ->
                fos.write(customCa.toByteArray())
            }
            config.setCAPath(customCaFile.absolutePath)
        } catch (e: IOException) {
            Log.e("Profile", "Error writing custom CA file", e)
        }
    }

    if (directFile.isNotBlank()) {
        val directConfFile = File(cacheDir, Pref.DIRECT_FILE)
        try {
            directConfFile.createNewFile()
            FileOutputStream(directConfFile, false).use { fos ->
                fos.write(directFile.toByteArray())
            }
            config.setDirectFile(directConfFile.absolutePath)
        } catch (e: IOException) {
            Log.e("Profile", "Error writing direct file", e)
        }
    }

    if (proxyFile.isNotBlank()) {
        val proxyConfFile = File(cacheDir, Pref.PROXY_FILE)
        try {
            proxyConfFile.createNewFile()
            FileOutputStream(proxyConfFile, false).use { fos ->
                fos.write(proxyFile.toByteArray())
            }
            config.setProxyFile(proxyConfFile.absolutePath)
        } catch (e: IOException) {
            Log.e("Profile", "Error writing proxy file", e)
        }
    }

    return config
}
