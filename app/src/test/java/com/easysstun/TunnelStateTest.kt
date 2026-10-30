package com.easysstun

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class TunnelStateTest {

    @After
    fun tearDown() {
        // TunnelState is a process-wide singleton; leave it as the app starts.
        TunnelState.update(false)
    }

    @Test
    fun defaultsToNotReady() {
        TunnelState.update(false)

        assertFalse(TunnelState.ready.value)
    }

    @Test
    fun emitsOnlyOnChange() = runTest {
        TunnelState.update(false)
        val emissions = mutableListOf<Boolean>()
        val watcher = launch { TunnelState.ready.collect { emissions += it } }
        runCurrent()

        TunnelState.update(true)
        runCurrent()
        TunnelState.update(true)
        runCurrent()

        watcher.cancel()
        assertEquals(listOf(false, true), emissions)
    }
}
