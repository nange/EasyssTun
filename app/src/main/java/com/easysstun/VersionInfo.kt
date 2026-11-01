package com.easysstun

import android.util.Log
import io.github.nange.easyss.mobile.Mobile

/** 版本横幅的 tag；必须留在 [LogTags.ALL] 里，否则 [LogStore] 的 `logcat -s` 过滤会丢掉它。 */
const val VERSION_LOG_TAG = "EasyssTun"

/** 内置 libeasyss 的版本（AAR 的 gomobile `Mobile.version()`，即 Go 构建的 git tag）；native 库加载失败（单元测试）或返回空时为 `"unknown"`。 */
internal fun libeasyssVersion(): String =
    runCatching { Mobile.version() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: "unknown"

/** 日志页的一行版本横幅；libeasyss 只在 CLI（`cmd/easyss`）打印版本，gomobile 绑定不会，故由应用代为打印。 */
internal fun versionBanner(): String =
    "EasyssTun v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | libeasyss ${libeasyssVersion()}"

/** 以 INFO 输出：采集期过滤会丢掉 DEBUG，除非当前配置档调低了级别；[detail] 追加在同一行。 */
internal fun logVersionBanner(detail: String? = null) {
    val line = if (detail.isNullOrBlank()) versionBanner() else "${versionBanner()} | $detail"
    Log.i(VERSION_LOG_TAG, line)
}
