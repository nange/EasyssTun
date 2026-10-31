package com.easysstun

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide readiness of the local easyss proxy.
 *
 * [EasyssVpnService] flips this to true only after the SOCKS5 listener answered
 * a real handshake ([EasyssVpnService.waitForSocksReady]) and the tunnel is
 * serving, so it is a strictly stronger signal than `Pref.userWantsVpn`,
 * which records the user's *intent* and is set before `Mobile.start()` has even
 * run. The in-app updater uses it to pick the local HTTP proxy route instead of
 * a direct connection that a censored network would drop.
 *
 * Defaults to false and lives in memory: the VPN runs in this same process, so
 * a fresh process never has a tunnel.
 */
object TunnelState {

    private const val TAG = "TunnelState"

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun update(ready: Boolean) {
        if (_ready.value == ready) return
        Log.i(TAG, "tunnel ready -> $ready")
        _ready.value = ready
    }
}
