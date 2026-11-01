package com.easysstun

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.preference.PreferenceManager

/** SharedPreferences 的类型化键值访问，只做偏好读写：服务器配置档在 [ProfileStore]，分应用选择在 [AppSelectionStore]，
 * native 配置文件在 [ProfileConfigWriter]；它们复用底部的通用键值方法，key 定义在各自数据模型旁边。
 * [SharedPreferences] 句柄刻意私有：调用方走类型化访问器，存储表示与版本迁移才集中在一处。 */
class Pref(context: Context) {
    companion object {
        const val SERVICE_ENABLED = "enable"
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

    /** 用户持久化的「意图」：true 表示希望 VPN 处于连接状态。这不是存活标志，存活由 [VpnState] 负责。
     * 只由明确的用户操作（连接、断开、拒绝授权）与系统对账（[ServiceReceiver] 开机、[VpnController] 撤权）写入，服务自身从不写。
     * key 沿用旧名，老安装的设置在升级后继续有效。 */
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

    /** 启动时是否每 [AUTO_CHECK_INTERVAL_MS] 检查一次新版本。 */
    var updateAutoCheck: Boolean
        get() = prefs.getBoolean(UPDATE_AUTO_CHECK, true)
        set(value) {
            prefs.edit { putBoolean(UPDATE_AUTO_CHECK, value) }
        }

    /** 上次成功检查更新的时间戳；0 表示从未检查。 */
    var updateLastCheckAt: Long
        get() = prefs.getLong(UPDATE_LAST_CHECK_AT, 0L)
        set(value) {
            prefs.edit { putLong(UPDATE_LAST_CHECK_AT, value) }
        }

    /** 用户选择跳过的 versionCode；0 表示未跳过。 */
    var updateDismissedVersionCode: Int
        get() = prefs.getInt(UPDATE_DISMISSED_VERSION_CODE, 0)
        set(value) {
            prefs.edit { putInt(UPDATE_DISMISSED_VERSION_CODE, value) }
        }

    /** 分应用路由模式：[PROXY_MODE_BYPASS] 放行选中应用之外的流量，[PROXY_MODE_PROXY_ONLY] 只代理选中应用。
     * setter 只写偏好、不通知运行中的隧道：需要重载的调用方自己调 [ConfigChangeNotifier]，写入不会替调用方重启隧道。 */
    var proxyMode: String
        get() = prefs.getString(PROXY_MODE_KEY, PROXY_MODE_BYPASS) ?: PROXY_MODE_BYPASS
        set(value) {
            prefs.edit { putString(PROXY_MODE_KEY, value) }
        }

    // ── 供各数据模型仓库使用的键值原语 ───────

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
