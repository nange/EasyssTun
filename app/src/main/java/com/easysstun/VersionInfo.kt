package com.easysstun

import android.util.Log
import io.github.nange.easyss.mobile.Mobile

/**
 * Log tag of the startup version banner. It must stay listed in
 * [LogParser.APP_LOG_TAGS], otherwise the `logcat -s` filter used by
 * [LogStore] drops the banner before it can be parsed.
 */
const val VERSION_LOG_TAG = "EasyssTun"

/**
 * Version of the bundled libeasyss build (the AAR's gomobile `Mobile.version()`
 * binding, i.e. the Go build's Git tag), or `"unknown"` when the native library
 * cannot be loaded (unit tests) or reports nothing.
 */
internal fun libeasyssVersion(): String =
    runCatching { Mobile.version() }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }
        ?: "unknown"

/**
 * One-line version banner for the in-app log viewer: this app's version plus
 * the libeasyss build it drives. libeasyss itself only prints a version banner
 * in its CLI (`cmd/easyss`), never through the gomobile binding the app uses,
 * so the app logs it on the native library's behalf.
 */
internal fun versionBanner(): String =
    "EasyssTun v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) | libeasyss ${libeasyssVersion()}"

/**
 * Logs [versionBanner] at INFO under [VERSION_LOG_TAG]; [detail] appends
 * context (e.g. the profile in use) to the same line. INFO is required: the
 * capture-time filter in [LogStore] drops DEBUG lines unless the active
 * profile asks for them.
 */
internal fun logVersionBanner(detail: String? = null) {
    val line = if (detail.isNullOrBlank()) versionBanner() else "${versionBanner()} | $detail"
    Log.i(VERSION_LOG_TAG, line)
}
