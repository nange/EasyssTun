package com.easysstun

import android.util.Log
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
 * Builds the command-line argument list for the native easyss proxy from this profile.
 */
fun ServerProfile.buildCmdList(cacheDir: File, socksPort: String): List<String> {
    var sn = serverNameIndication
    if (sn.isBlank()) {
        sn = server
    }

    val cmdList = mutableListOf(
        "-s", server,
        "-p", serverPort,
        "-k", password,
        "-m", encryption,
        "-proxy-rule", proxyRule,
        "-outbound-proto", outbound,
        "-l", socksPort,
        "-t", "60",
        "-log-level", logLevel,
        "-enable-quic=$enableQuic",
        "-ipv6-rule", ipv6Rule,
        "-sn", sn,
        "-enable-tun2socks=false",
        "-daemon=false"
    )

    if (customCa.isNotBlank()) {
        val customCaFile = File(cacheDir, Pref.CUSTOM_CA_FILE)
        try {
            customCaFile.createNewFile()
            FileOutputStream(customCaFile, false).use { fos ->
                fos.write(customCa.toByteArray())
            }
            cmdList.addAll(listOf("-ca-path", customCaFile.absolutePath))
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
            cmdList.addAll(listOf("-direct-file", directConfFile.absolutePath))
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
            cmdList.addAll(listOf("-proxy-file", proxyConfFile.absolutePath))
        } catch (e: IOException) {
            Log.e("ServerProfile", "Error writing proxy file", e)
        }
    }

    return cmdList
}
