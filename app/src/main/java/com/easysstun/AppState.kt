package com.easysstun

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Tracks whether the app is in the foreground. Defaults to true (app starts visible). */
object AppState {

    private val _isForeground = MutableStateFlow(true)
    val isForeground: StateFlow<Boolean> = _isForeground.asStateFlow()

    fun update(foreground: Boolean) {
        _isForeground.value = foreground
    }
}
