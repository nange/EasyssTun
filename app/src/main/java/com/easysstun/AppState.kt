package com.easysstun

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 应用是否在前台；初始为 true（启动即可见）。 */
object AppState {

    private const val TAG = LogTags.APP_STATE

    private val _isForeground = MutableStateFlow(true)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    fun update(foreground: Boolean) {
        if (_isForeground.value != foreground) {
            // 用 Debug 级别：每次前后台切换都触发，INFO 会刷屏应用内日志界面。
            Log.d(TAG, "App state changed → ${if (foreground) "FOREGROUND (2s)" else "BACKGROUND (30s)"}")
        }
        _isForeground.value = foreground
    }
}
