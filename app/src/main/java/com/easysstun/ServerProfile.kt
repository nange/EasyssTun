package com.easysstun

import android.util.Log
import io.github.nange.easyss.config.SimpleConfig
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@Serializable
data class ServerProfile(
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
    val proxyFile: String = ""
)

/**
 * Builds a SimpleConfig for the AAR-based easyss proxy from this profile.
 */
fun ServerProfile.buildSimpleConfig(cacheDir: File, socksPort: String): SimpleConfig {
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

    config.setLocalPort(socksPort.toLongOrNull() ?: 2080L)
    config.setTimeout(60L)

    if (customCa.isNotBlank()) {
        val customCaFile = File(cacheDir, Pref.CUSTOM_CA_FILE)
        try {
            customCaFile.createNewFile()
            FileOutputStream(customCaFile, false).use { fos ->
                fos.write(customCa.toByteArray())
            }
            config.setCAPath(customCaFile.absolutePath)
        } catch (e: IOException) {
            Log.e("ServerProfile", "Error writing custom CA file", e)
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
            Log.e("ServerProfile", "Error writing direct file", e)
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
            Log.e("ServerProfile", "Error writing proxy file", e)
        }
    }

    return config
}
