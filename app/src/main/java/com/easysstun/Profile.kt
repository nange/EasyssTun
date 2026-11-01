package com.easysstun

import io.github.nange.easyss.config.SimpleConfig
import kotlinx.serialization.Serializable
import java.util.Locale

/** 一个已保存的服务器配置档。[serverPort]、[socksPort]、[timeout] 与 QUIC 开关是类型化字段，
 * 旧版本把它们存成字符串，故用 [LenientIntSerializer]/[LenientBooleanSerializer] 解码兼容、写入时归一化；
 * `null` 表示未配置，由 native 侧自身默认值兜底，而不是沿用用户从未选择的值。 */
@Serializable
data class Profile(
    val id: String,
    val name: String,
    val server: String,
    @Serializable(with = LenientIntSerializer::class)
    val serverPort: Int? = null,
    val password: String,
    val encryption: String = "chacha20-poly1305",
    val proxyRule: String = "auto",
    val outbound: String = "native",
    val logLevel: String = "info",
    @Serializable(with = LenientBooleanSerializer::class)
    val enableQuic: Boolean = false,
    val ipv6Rule: String = "auto",
    val serverNameIndication: String = "",
    val customCa: String = "",
    val directFile: String = "",
    val proxyFile: String = "",
    @Serializable(with = LenientIntSerializer::class)
    val socksPort: Int? = DEFAULT_SOCKS_PORT,
    @Serializable(with = LenientIntSerializer::class)
    val timeout: Int? = DEFAULT_TIMEOUT,
) {
    companion object {
        /** 新建配置档预填的本地 SOCKS 端口；已存配置档端口缺失或无法解析时也用它兜底。 */
        const val DEFAULT_SOCKS_PORT = 4080
        const val STATS_PORT_OFFSET = 1000

        /** 新建配置档预填的超时秒数；配置档 timeout 为空时同样套用。与 easyss 的 `config.DefaultTimeout`（`-t` 默认值）一致。 */
        const val DEFAULT_TIMEOUT = 30

        /** native 超时的合法区间（秒）：easyss 的 idle/dial/DNS/轮换超时都由它派生，只接受 15-60。 */
        const val MIN_TIMEOUT_SECONDS = 15
        const val MAX_TIMEOUT_SECONDS = 60

        private const val STATS_URL_FORMAT = "http://127.0.0.1:%d/stats"

        /** 默认 SOCKS 端口对应的统计 URL（即 5080），无当前配置档时使用。 */
        fun defaultStatsUrl(): String =
            String.format(Locale.ROOT, STATS_URL_FORMAT, DEFAULT_SOCKS_PORT + STATS_PORT_OFFSET)
    }

    /** 本地 HTTP 统计端口，由 SOCKS 端口派生：easyss 约定 HTTPPort = SocksPort + 1000。 */
    fun statsPort(): Int = (socksPort ?: DEFAULT_SOCKS_PORT) + STATS_PORT_OFFSET

    /** 本地 HTTP 统计端点 URL，如 http://127.0.0.1:5080/stats。 */
    fun statsUrl(): String = String.format(Locale.ROOT, STATS_URL_FORMAT, statsPort())

    /** 交给 native 的基础超时（秒），等价 easyss 的 `-t/--timeout`：缺失或越界（手改）返回 0，由 libeasyss 自身默认值兜底。 */
    fun timeoutSeconds(): Int =
        timeout?.takeIf { it in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS } ?: 0
}

/** 解析表单里的超时（秒）：忽略首尾空白，空串返回 0 表示未配置，由 native 默认值兜底。
 * 非整数或超出 [Profile.MIN_TIMEOUT_SECONDS]..[Profile.MAX_TIMEOUT_SECONDS] 返回 `null`，调用方须当作输入错误上报。 */
fun parseTimeoutSeconds(raw: String): Int? {
    val text = raw.trim()
    if (text.isEmpty()) return 0
    val seconds = text.toIntOrNull() ?: return null
    return seconds.takeIf { it in Profile.MIN_TIMEOUT_SECONDS..Profile.MAX_TIMEOUT_SECONDS }
}

/** 解析表单里的端口：忽略首尾空白，空串或非 1..65535 的整数返回 `null`。
 * 允许留空的调用方（本地 SOCKS 端口）要另看原文才能区分「未配置」与「非法」。 */
fun parsePortOrNull(raw: String): Int? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    return text.toIntOrNull()?.takeIf { it in 1..65535 }
}

/** 由配置档构造 AAR 版 easyss 的 SimpleConfig。纯函数：辅助文件（自定义 CA、直连/代理规则）由 [ProfileConfigWriter] 预先写出，
 * [paths] 指明位置且故意必填——忘了写文件的调用方不能静默启动一份丢掉这些文件的配置。 */
internal fun Profile.buildSimpleConfig(paths: ProfileConfigPaths): SimpleConfig {
    val config = SimpleConfig()

    config.setServer(server)
    config.setServerPort(serverPort?.toLong() ?: 443L)
    config.setPassword(password)
    config.setMethod(encryption)
    config.setProxyRule(proxyRule)
    config.setOutboundProto(outbound)
    config.setLogLevel(logLevel)
    config.setEnableQUIC(enableQuic)
    config.setIPV6Rule(ipv6Rule)

    var sni = serverNameIndication
    if (sni.isBlank()) {
        sni = server
    }
    config.setSN(sni)

    config.setLocalPort((socksPort ?: Profile.DEFAULT_SOCKS_PORT).toLong())

    // 与 easyss 的 `-t/--timeout` 是同一个旋钮：所有 idle/dial 超时由它派生，0 沿用 native 默认值。
    config.setTimeout(timeoutSeconds().toLong())

    if (paths.customCa != null) {
        config.setCAPath(paths.customCa)
    }

    if (paths.directFile != null) {
        config.setDirectFile(paths.directFile)
    }

    if (paths.proxyFile != null) {
        config.setProxyFile(paths.proxyFile)
    }

    return config
}
