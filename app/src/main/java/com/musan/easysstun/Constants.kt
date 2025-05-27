package com.musan.easysstun

object Constants {
    // Intent Actions
    const val ACTION_CONNECT = "com.musan.easysstun.CONNECT" // Made more specific
    const val ACTION_DISCONNECT = "com.musan.easysstun.DISCONNECT" // Made more specific
    const val ACTION_SERVICE_STOPPED = "com.musan.easysstun.SERVICE_FULLY_STOPPED"
    const val ACTION_PREFS_UPDATED = "com.musan.easysstun.PREFS_UPDATED"

    // Intent Extras
    const val EXTRA_ACTIVE_SERVER_PROFILE_JSON = "com.musan.easysstun.ACTIVE_SERVER_PROFILE_JSON_EXTRA"

    // Notification
    const val NOTIFICATION_CHANNEL_NAME = "easysstun_channel" // Changed from just "easysstun" to avoid conflict if app name is same
    const val NOTIFICATION_ID = 1

    // Preference Keys
    const val PREF_SOCKS_PORT = "socks_port"
    // Pref.kt already has: SERVICE_ENABLED, VERSION, SERVER_PROFILES, ACTIVE_SERVER_ID

    // Default Values
    const val DEFAULT_SOCKS_PORT = "2080"
    const val DEFAULT_MTU = 8500 // From TProxyService
    const val DEFAULT_DNS_SERVER = "1.1.1.1" // From TProxyService
    const val TPROXY_CONF_FILE_NAME = "tproxy.conf"
    const val EASYSS_CUSTOM_CA_FILE_NAME = "easyss_custom_ca.conf"

    // Logging Tags (Example, if more are needed. TProxyService.TAG is fine in its companion)
    // const val TAG_PREF = "PrefDiag"
}
