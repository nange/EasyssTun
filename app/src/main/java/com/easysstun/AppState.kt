package com.easysstun

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks whether the app is in the foreground. Defaults to true (app starts visible). */
object AppState {

    private const val TAG = "AppState"

    private val _isForeground = MutableStateFlow(true)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    fun update(foreground: Boolean) {
        if (_isForeground.value != foreground) {
            Log.i(TAG, "App state changed → ${if (foreground) "FOREGROUND (1s)" else "BACKGROUND (30s)"}")
        }
        _isForeground.value = foreground
    }
}
