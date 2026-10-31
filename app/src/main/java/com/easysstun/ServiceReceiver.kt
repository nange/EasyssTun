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

            /* Auto-start */
            if (!prefs.isServiceEnabled) return

            // The service reads its configuration from Pref. Starting a
            // foreground service without an active profile would leave it
            // unable to call startForeground() within the platform deadline,
            // so validate the configuration before starting it.
            if (prefs.getActiveProfile() == null) {
                Log.w(TAG, "BOOT_COMPLETED: service enabled but no active profile; clearing flag.")
                prefs.isServiceEnabled = false
                return
            }

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

    companion object {
        private const val TAG = "ServiceReceiver"
    }
}
