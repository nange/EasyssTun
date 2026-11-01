package com.easysstun

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/**
 * Typed key/value access to the app's SharedPreferences.
 *
 * This class stores preferences and nothing else. The server profile repository
 * lives in [ProfileStore], the per-app routing selection in [AppSelectionStore]
 * and the config files handed to the native stack in [ProfileConfigWriter].
 * Those are built on the generic key/value helpers at the bottom, so every
 * stored key is defined next to the data model that owns it instead of leaking
 * through this class.
 *
 * The raw [SharedPreferences] handle is private on purpose: callers go through a
 * typed accessor (or one of the stores above), which keeps the stored
 * representation — and the migrations between its versions — in one place.
 */
class Pref(context: Context) {
    companion object {
        const val SERVICE_ENABLED = "enable"
        const val VERSION = "version"
        const val SHOW_SYSTEM_APPS = "show_system_apps"
        const val UPDATE_AUTO_CHECK = "update_auto_check"
        const val UPDATE_LAST_CHECK_AT = "update_last_check_at"
        const val UPDATE_DISMISSED_VERSION_CODE = "update_dismissed_version_code"
        const val PROXY_MODE_KEY = "proxy_mode"
        const val PROXY_MODE_BYPASS = "bypass"
        const val PROXY_MODE_PROXY_ONLY = "proxy_only"
    }

    private val prefs: SharedPreferences =
        PreferenceManager.getDefaultSharedPreferences(context)

    var version: String
        get() {
            val currentTimestamp: String = System.currentTimeMillis().toString()
            return prefs.getString(VERSION, currentTimestamp) ?: currentTimestamp
        }
        set(value) {
            prefs.edit { putString(VERSION, value) }
        }

    /**
     * The user's persisted *intent*: true while they want the VPN to be up.
     *
     * This is deliberately not a liveness flag — [VpnState] owns that. It is
     * written only by explicit user actions (start, stop, denied permission) and
     * by system reconciliation ([ServiceReceiver] on boot, [VpnController] on
     * revoke), never by the service on its own. The stored key keeps its old
     * name so existing installations keep their setting.
     */
    var userWantsVpn: Boolean
        get() = prefs.getBoolean(SERVICE_ENABLED, false)
        set(value) {
            prefs.edit { putBoolean(SERVICE_ENABLED, value) }
        }

    var showSystemApps: Boolean
        get() = prefs.getBoolean(SHOW_SYSTEM_APPS, true)
        set(value) {
            prefs.edit { putBoolean(SHOW_SYSTEM_APPS, value) }
        }

    /** Whether a release check runs once per [AUTO_CHECK_INTERVAL_MS] on app start. */
    var updateAutoCheck: Boolean
        get() = prefs.getBoolean(UPDATE_AUTO_CHECK, true)
        set(value) {
            prefs.edit { putBoolean(UPDATE_AUTO_CHECK, value) }
        }

    /** Timestamp of the last successful release check; 0 means "never checked". */
    var updateLastCheckAt: Long
        get() = prefs.getLong(UPDATE_LAST_CHECK_AT, 0L)
        set(value) {
            prefs.edit { putLong(UPDATE_LAST_CHECK_AT, value) }
        }

    /** versionCode the user chose to skip; 0 means "nothing skipped". */
    var updateDismissedVersionCode: Int
        get() = prefs.getInt(UPDATE_DISMISSED_VERSION_CODE, 0)
        set(value) {
            prefs.edit { putInt(UPDATE_DISMISSED_VERSION_CODE, value) }
        }

    /**
     * Per-app routing mode: [PROXY_MODE_BYPASS] (route everything except the
     * selected apps) or [PROXY_MODE_PROXY_ONLY] (route only the selected apps).
     *
     * The setter is a plain preference write. It does not notify the running
     * tunnel: a caller that changed the routing configuration on purpose calls
     * [ConfigChangeNotifier] itself, so a write never restarts the tunnel behind
     * the caller's back.
     */
    var proxyMode: String
        get() = prefs.getString(PROXY_MODE_KEY, PROXY_MODE_BYPASS) ?: PROXY_MODE_BYPASS
        set(value) {
            prefs.edit { putString(PROXY_MODE_KEY, value) }
        }

    // ── Key/value primitives used by the data-model stores ───────

    internal fun getString(key: String, defaultValue: String? = null): String? =
        prefs.getString(key, defaultValue)

    internal fun putString(key: String, value: String?) {
        prefs.edit {
            if (value == null) remove(key) else putString(key, value)
        }
    }

    internal fun getStringSet(key: String): Set<String>? = prefs.getStringSet(key, null)

    internal fun putStringSet(key: String, value: Set<String>) {
        prefs.edit { putStringSet(key, value) }
    }

    internal fun remove(key: String) {
        prefs.edit { remove(key) }
    }
}
