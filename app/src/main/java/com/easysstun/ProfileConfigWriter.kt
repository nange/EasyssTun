package com.easysstun

import java.io.File

/**
 * Paths of the auxiliary files written for a profile.
 *
 * A null entry means the profile does not use that file (the corresponding
 * field was blank), which is exactly the condition under which the native
 * config must not be pointed at a path.
 */
data class ProfileConfigPaths(
    val customCa: String? = null,
    val directFile: String? = null,
    val proxyFile: String? = null,
)

/**
 * Writes the config files the native stack reads from the app's cache dir.
 *
 * Writes are idempotent: a file is only rewritten when its content actually
 * changed, so restarting the tunnel with the same profile does not touch the
 * disk. IO failures are returned as a failed [Result] instead of being swallowed:
 * starting with a missing CA or routing-rules file would silently run the tunnel
 * with a different configuration than the user saved.
 */
internal class ProfileConfigWriter(private val cacheDir: File) {

    /**
     * Writes the files owned by [profile] and returns the paths to pass to
     * [buildSimpleConfig].
     */
    fun writeProfileFiles(profile: Profile): Result<ProfileConfigPaths> = runCatching {
        ProfileConfigPaths(
            customCa = writeIfChanged(CUSTOM_CA_FILE, profile.customCa)?.absolutePath,
            directFile = writeIfChanged(DIRECT_FILE, profile.directFile)?.absolutePath,
            proxyFile = writeIfChanged(PROXY_FILE, profile.proxyFile)?.absolutePath,
        )
    }

    /** Writes the hev-socks5-tunnel config pointing at the local SOCKS port. */
    fun writeTproxyConfig(socksPort: Int): Result<File> = runCatching {
        checkNotNull(writeIfChanged(TPROXY_FILE, tproxyConfig(socksPort))) {
            "the tproxy config is never blank"
        }
    }

    /**
     * Writes [content] to [name] unless the file already holds exactly that
     * text. Returns the file, or null when there is nothing to write.
     */
    private fun writeIfChanged(name: String, content: String): File? {
        if (content.isBlank()) return null
        val file = File(cacheDir, name)
        if (readIfExists(file) == content) return file
        file.writeText(content)
        return file
    }

    private fun readIfExists(file: File): String? =
        if (file.isFile) file.readText() else null

    /**
     * hev-socks5-tunnel config. TCP idle timeout is raised from the upstream
     * 300000ms default to 480000ms (8min) so idle-but-alive TCP sessions (long
     * polls, keep-alive connections) survive longer.
     */
    private fun tproxyConfig(socksPort: Int): String = """misc:
  tcp-read-write-timeout: 480000
  udp-read-write-timeout: 15000

socks5:
  port: $socksPort
  address: '127.0.0.1'
  udp: 'udp'
"""

    companion object {
        // Names of the files written into the app cache dir.
        const val CUSTOM_CA_FILE = "easyss_custom_ca.conf"
        const val DIRECT_FILE = "easyss_direct.conf"
        const val PROXY_FILE = "easyss_proxy.conf"
        const val TPROXY_FILE = "tproxy.conf"
    }
}
