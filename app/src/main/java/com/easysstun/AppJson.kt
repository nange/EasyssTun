package com.easysstun

import kotlinx.serialization.json.Json

// 应用自有数据的共享 JSON 配置：配置档仓库、传给 VPN 服务的配置、Release 响应。
// 忽略未知键以兼容更新版本或手改过的数据；写出默认值以保证旧版本读回时字段齐全。
internal val appJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}
