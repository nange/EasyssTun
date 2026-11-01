package com.easysstun

import java.io.File

/** 配置档辅助文件的路径；null 表示该字段为空，native 配置不能指向任何路径。 */
internal data class ProfileConfigPaths(
    val customCa: String? = null,
    val directFile: String? = null,
    val proxyFile: String? = null,
)

/** 写 native 读取的配置文件：内容未变不重写；IO 失败返回失败的 [Result]——缺 CA 或规则文件会让隧道静默按另一套配置运行。 */
internal class ProfileConfigWriter(private val cacheDir: File) {

    /** 返回的路径必须交给 `buildSimpleConfig`，否则 native 拿不到这些文件。 */
    fun writeProfileFiles(profile: Profile): Result<ProfileConfigPaths> = runCatching {
        ProfileConfigPaths(
            customCa = writeIfChanged(CUSTOM_CA_FILE, profile.customCa)?.absolutePath,
            directFile = writeIfChanged(DIRECT_FILE, profile.directFile)?.absolutePath,
            proxyFile = writeIfChanged(PROXY_FILE, profile.proxyFile)?.absolutePath,
        )
    }

    fun writeTproxyConfig(socksPort: Int): Result<File> = runCatching {
        checkNotNull(writeIfChanged(TPROXY_FILE, tproxyConfig(socksPort))) {
            "the tproxy config is never blank"
        }
    }

    private fun writeIfChanged(name: String, content: String): File? {
        if (content.isBlank()) return null
        val file = File(cacheDir, name)
        if (readIfExists(file) == content) return file
        file.writeText(content)
        return file
    }

    private fun readIfExists(file: File): String? =
        if (file.isFile) file.readText() else null

    // 480000ms（8 分钟）高于上游默认的 300000ms：让空闲但存活的 TCP 会话（长轮询、keep-alive）不被提前掐断。
    private fun tproxyConfig(socksPort: Int): String = """misc:
  tcp-read-write-timeout: 480000
  udp-read-write-timeout: 15000

socks5:
  port: $socksPort
  address: '127.0.0.1'
  udp: 'udp'
"""

    companion object {
        const val CUSTOM_CA_FILE = "easyss_custom_ca.conf"
        const val DIRECT_FILE = "easyss_direct.conf"
        const val PROXY_FILE = "easyss_proxy.conf"
        const val TPROXY_FILE = "tproxy.conf"
    }
}
