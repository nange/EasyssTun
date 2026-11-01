package com.easysstun

/**
 * Every logcat tag the app writes under, in one place.
 *
 * The in-app log viewer runs `logcat -s <tags>` with [ALL]; a tag missing from
 * this list is silently dropped before it can be parsed. Keeping the constants
 * here — instead of a private TAG per file plus a hand-maintained array —
 * makes that coupling visible: adding a logger means adding its tag to [ALL].
 */
internal object LogTags {
    const val APP_STATE = "AppState"
    const val APP_SELECTION = "AppSelectionStore"
    /** Tag the Go runtime gives to libeasyss's own logcat output. */
    const val GO = "GoLog"
    const val LOG_STORE = "LogStore"
    const val MAIN = "MainFragment"
    const val PROFILE_STORE = "ProfileStore"
    const val SERVICE = "EasyssVpnServiceDiag"
    const val SERVICE_RECEIVER = "ServiceReceiver"
    const val STATS = "Stats"
    const val TUNNEL = "TunnelState"
    const val UPDATER = "Updater"
    const val VPN_CONTROLLER = "VpnController"

    /** Tags captured by the in-app log viewer. */
    val ALL: List<String> = listOf(
        APP_STATE,
        APP_SELECTION,
        GO,
        LOG_STORE,
        MAIN,
        PROFILE_STORE,
        SERVICE,
        SERVICE_RECEIVER,
        STATS,
        TUNNEL,
        UPDATER,
        VPN_CONTROLLER,
        VERSION_LOG_TAG,
    )
}
