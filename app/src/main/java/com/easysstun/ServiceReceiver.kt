package com.easysstun

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log


class ServiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = Pref(context)

            if (!prefs.userWantsVpn) return

            // 服务从仓库读配置：没有 active 配置档时无法在平台期限内 startForeground()，
            // 故启动前先校验并清掉过期标记。
            if (ProfileStore.get(context).active() == null) {
                Log.w(TAG, "BOOT_COMPLETED: service enabled but no active profile; clearing flag.")
                prefs.userWantsVpn = false
                return
            }

            val i = VpnService.prepare(context)
            if (i != null) {
                i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(i)
            } else {
                // 权限已授予，直接启动；服务自行从仓库读 active 配置档。
                context.startForegroundService(EasyssVpnService.connectIntent(context))
            }
        }
    }

    companion object {
        private const val TAG = LogTags.SERVICE_RECEIVER
    }
}
