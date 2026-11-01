package com.easysstun

import android.util.Log

/** 分应用选择：bypass 与 proxy-only 各存一份；旧版本的单个 `selected_apps` 只属于 bypass，首次读取时迁移。写入走 `apply()`，内存同步更新。 */
internal class AppSelectionStore(private val pref: Pref) {

    fun selected(mode: String): Set<String> {
        val key = keyFor(mode)
        // `getStringSet()` 返回的集合不可修改，必须复制后再交给调用方。
        pref.getStringSet(key)?.let { return it.toSet() }
        if (key == SELECTED_APPS_BYPASS) {
            val legacy = pref.getStringSet(SELECTED_APPS)
            if (!legacy.isNullOrEmpty()) {
                Log.i(TAG, "migrating the legacy app selection into $key (${legacy.size} apps)")
                pref.putStringSet(key, legacy)
                pref.remove(SELECTED_APPS)
                return legacy.toSet()
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
        private const val TAG = LogTags.APP_SELECTION

        /** 旧 key：只有 bypass 模式时写入；读一次后删除。 */
        const val SELECTED_APPS = "selected_apps"
        const val SELECTED_APPS_BYPASS = "selected_apps_bypass"
        const val SELECTED_APPS_PROXY_ONLY = "selected_apps_proxy_only"
    }
}
