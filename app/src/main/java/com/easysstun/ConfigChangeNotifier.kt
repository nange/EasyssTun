package com.easysstun

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/** 配置变更的唯一广播出口：setter 只写存储、不会自动重启隧道，运行中的隧道须由调用方显式 [notifyChanged]。 */
internal object ConfigChangeNotifier {

    /** 应用内动作：运行中的 [EasyssVpnService] 只监听这一个动作。 */
    const val ACTION_CONFIG_CHANGED = "com.easysstun.action.CONFIG_CHANGED"

    fun notifyChanged(context: Context) {
        context.sendBroadcast(
            Intent(ACTION_CONFIG_CHANGED).setPackage(context.packageName)
        )
    }

    /** 以非导出方式注册：只接收本应用发出的广播。 */
    fun register(context: Context, receiver: BroadcastReceiver) {
        context.registerReceiver(
            receiver,
            IntentFilter(ACTION_CONFIG_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }
}
