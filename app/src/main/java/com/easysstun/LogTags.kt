package com.easysstun

/**
 * 应用全部 logcat tag。日志页用 [ALL] 生成 `logcat -s` 过滤列表：新增 logger 必须把 tag 加进 [ALL]，漏登记会被静默丢弃。
 */
internal object LogTags {
    const val APP_STATE = "AppState"
    const val APP_SELECTION = "AppSelectionStore"
    /** libeasyss 的 Go runtime 输出的 tag，不是应用代码写入的。 */
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
