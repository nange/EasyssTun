package com.musan.easysstun

import kotlinx.serialization.Serializable

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
    val disableQuic: String = "false",
    val ipv6Rule: String = "auto",
    val serverNameIndication: String = "",
    val customCa: String = ""
)
