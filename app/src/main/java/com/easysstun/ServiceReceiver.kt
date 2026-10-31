package com.easysstun

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.VpnService


class ServiceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = Pref(context)

            /* Auto-start */
            if (prefs.isServiceEnabled) {
                val i = VpnService.prepare(context)
                if (i != null) {
                    i.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    context.startActivity(i)
                } else {
                    // Permission already granted, start service directly. The
                    // service reads the active profile from Pref.
                    context.startForegroundService(EasyssVpnService.connectIntent(context))
                }
            }
        }
    }
}
