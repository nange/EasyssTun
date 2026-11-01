package com.easysstun

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 本地 easyss 代理就绪：仅当 SOCKS5 真实握手成功（[EasyssVpnService.waitForSocksReady]）且隧道已服务才为 true，强于只代表用户意图的 `Pref.userWantsVpn`；更新器据此走本地代理。
 */
object TunnelState {

    private const val TAG = LogTags.TUNNEL

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun update(ready: Boolean) {
        if (_ready.value == ready) return
        // Debug 级别：start/stop 已由 VpnState 以 INFO 上报，避免重复刷屏。
        Log.d(TAG, "tunnel ready -> $ready")
        _ready.value = ready
    }
}
