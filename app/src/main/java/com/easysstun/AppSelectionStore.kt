package com.easysstun

import android.util.Log

/**
 * Per-app routing selection for each [Pref.proxyMode].
 *
 * Bypass and proxy-only mode each keep their own set of package names, so
 * switching the mode back and forth restores the selection the user made for
 * it. Both sets live under their own key; the pre-mode builds had a single
 * `selected_apps` set that only ever meant bypass mode, so reading bypass mode
 * migrates that legacy set once.
 *
 * Writes use `apply()` through [Pref]: the in-memory value is updated
 * synchronously, which is all a same-process reader (the VPN service) needs,
 * and the disk write stays off the main thread.
 */
internal class AppSelectionStore(private val pref: Pref) {

    /** Package names selected for [mode]. Never null; an unset mode is empty. */
    fun selected(mode: String): Set<String> {
        val key = keyFor(mode)
        // getStringSet() returns an instance that must not be modified by the
        // caller, so hand out a copy instead of the stored set.
        pref.getStringSet(key)?.let { return it.toSet() }
        if (key == SELECTED_APPS_BYPASS) {
            // Migration: the legacy key was written by builds that only had
            // bypass mode, so its content belongs to this mode.
            val legacy = pref.getStringSet(SELECTED_APPS)
            if (!legacy.isNullOrEmpty()) {
                Log.i(TAG, "migrating the legacy app selection into $key (${legacy.size} apps)")
                pref.putStringSet(key, legacy)
                pref.remove(SELECTED_APPS)
                return legacy
            }
        }
        return emptySet()
    }

    fun setSelected(mode: String, apps: Set<String>) {
        pref.putStringSet(keyFor(mode), apps)
    }

    private fun keyFor(mode: String): String =
        if (mode == Pref.PROXY_MODE_PROXY_ONLY) SELECTED_APPS_PROXY_ONLY else SELECTED_APPS_BYPASS

    companion object {
        private const val TAG = "AppSelectionStore"

        /** Legacy key written when bypass was the only mode. Read once, then removed. */
        const val SELECTED_APPS = "selected_apps"
        const val SELECTED_APPS_BYPASS = "selected_apps_bypass"
        const val SELECTED_APPS_PROXY_ONLY = "selected_apps_proxy_only"
    }
}
