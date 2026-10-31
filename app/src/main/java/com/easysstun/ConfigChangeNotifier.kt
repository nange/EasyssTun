package com.easysstun

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter

/**
 * Single entry point for "the persisted tunnel configuration changed, reload it".
 *
 * The preference setters ([Pref.proxyMode], [AppSelectionStore.setSelected]) and
 * the profile form are plain writes: they never restart the tunnel behind the
 * caller's back. A caller that changed the configuration of a running tunnel on
 * purpose calls [notifyChanged] after the write. This is the only place that
 * sends the signal, and [ACTION_CONFIG_CHANGED] is the only action
 * [EasyssVpnService] listens to.
 */
internal object ConfigChangeNotifier {

    /** In-app intent action telling a running VPN service to reload its config. */
    const val ACTION_CONFIG_CHANGED = "com.easysstun.action.CONFIG_CHANGED"

    /**
     * Asks a running [EasyssVpnService] to reload the persisted configuration.
     * The broadcast is addressed to this package only.
     */
    fun notifyChanged(context: Context) {
        context.sendBroadcast(
            Intent(ACTION_CONFIG_CHANGED).setPackage(context.packageName)
        )
    }

    /** Registers [receiver] for [ACTION_CONFIG_CHANGED] (non-exported). */
    fun register(context: Context, receiver: BroadcastReceiver) {
        context.registerReceiver(
            receiver,
            IntentFilter(ACTION_CONFIG_CHANGED),
            Context.RECEIVER_NOT_EXPORTED,
        )
    }
}
