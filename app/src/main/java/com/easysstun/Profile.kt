package com.easysstun

import io.github.nange.easyss.config.SimpleConfig
import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * One saved server profile.
 *
 * The numeric knobs ([serverPort], [socksPort], [timeout]) and the QUIC flag
 * ([enableQuic]) are typed. Builds before this model existed stored all of them
 * as strings, so each field is decoded by a lenient serializer that accepts both
 * representations and normalizes them on write; see [LenientIntSerializer] and
 * [LenientBooleanSerializer]. `null` means "not configured": the native config
 * then applies its own default instead of a value the user never chose.
 */
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
        /**
         * Local SOCKS port prefilled for new profiles and used as the fallback
         * when a stored profile carries no (or an unparseable) port.
         */
        const val DEFAULT_SOCKS_PORT = 4080
        const val STATS_PORT_OFFSET = 1000

        /**
         * Base timeout (seconds) prefilled for new profiles and applied when the
         * profile leaves `timeout` blank. Matches the native default
         * (`config.DefaultTimeout` in easyss, also the `-t` startup flag default).
         */
        const val DEFAULT_TIMEOUT = 30

        /**
         * Legal range (seconds) of the native timeout knob: easyss derives every
         * idle/dial/DNS/rotation timeout from it and documents 15-60 as the
         * accepted range. The form rejects anything outside it, so a saved profile
         * always applies exactly what the user typed.
         */
        const val MIN_TIMEOUT_SECONDS = 15
        const val MAX_TIMEOUT_SECONDS = 60

        private const val STATS_URL_FORMAT = "http://127.0.0.1:%d/stats"

        /**
         * Stats URL for the default SOCKS port (i.e. 5080), used when no active
         * profile is available.
         */
        fun defaultStatsUrl(): String =
            String.format(Locale.ROOT, STATS_URL_FORMAT, DEFAULT_SOCKS_PORT + STATS_PORT_OFFSET)
    }

    /**
     * The local HTTP stats endpoint port, derived from the SOCKS port
     * (matches the easyss native convention: HTTPPort = SocksPort + 1000).
     */
    fun statsPort(): Int = (socksPort ?: DEFAULT_SOCKS_PORT) + STATS_PORT_OFFSET

    /**
     * The local HTTP stats endpoint URL, e.g. http://127.0.0.1:5080/stats
     * for the default SOCKS port 4080.
     */
    fun statsUrl(): String = String.format(Locale.ROOT, STATS_URL_FORMAT, statsPort())

    /**
     * The base timeout (seconds) handed to the native config, i.e. the equivalent
     * of the easyss `-t/--timeout` startup flag. A missing or (hand-edited)
     * out-of-range value becomes 0, which makes libeasyss fall back to its own
     * default instead of pinning a value the user never chose.
     */
    fun timeoutSeconds(): Int =
        timeout?.takeIf { it in MIN_TIMEOUT_SECONDS..MAX_TIMEOUT_SECONDS } ?: 0
}

/**
 * Parses the timeout field of the profile form (seconds). Surrounding whitespace
 * is ignored; a blank field means "not configured".
 *
 * @return the timeout in seconds, `0` when the field is blank (the native default
 *         then applies), or `null` when the text is not a whole number within
 *         [Profile.MIN_TIMEOUT_SECONDS, Profile.MAX_TIMEOUT_SECONDS] and must be
 *         reported as an input error by the caller.
 */
fun parseTimeoutSeconds(raw: String): Int? {
    val text = raw.trim()
    if (text.isEmpty()) return 0
    val seconds = text.toIntOrNull() ?: return null
    return seconds.takeIf { it in Profile.MIN_TIMEOUT_SECONDS..Profile.MAX_TIMEOUT_SECONDS }
}

/**
 * Parses a port typed into the profile form. Surrounding whitespace is ignored.
 *
 * @return the port, or `null` when [raw] is blank or is not a whole number in
 *         1..65535. Callers that allow a blank field (the local SOCKS port)
 *         check the raw text to tell "not configured" from "invalid".
 */
fun parsePortOrNull(raw: String): Int? {
    val text = raw.trim()
    if (text.isEmpty()) return null
    return text.toIntOrNull()?.takeIf { it in 1..65535 }
}

/**
 * Builds a SimpleConfig for the AAR-based easyss proxy from this profile.
 *
 * Pure: the auxiliary files (custom CA, direct/proxy rules) are written by
 * [ProfileConfigWriter] beforehand, and [paths] tells this function where they
 * are. A missing path means the profile does not use that file.
 */
fun Profile.buildSimpleConfig(paths: ProfileConfigPaths = ProfileConfigPaths()): SimpleConfig {
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

    // Same knob as the easyss `-t/--timeout` startup flag: the base timeout all
    // idle/dial timeouts are derived from. 0 keeps the native default.
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
