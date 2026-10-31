package com.easysstun

import android.content.Intent
import android.os.Build
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * Characterisation tests for the service's configuration preconditions.
 *
 * They pin the contract that a service start without a usable active profile
 * leaves the user-visible "enabled" flag off (and therefore the home screen in
 * the stopped state), without touching the native stack.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.TIRAMISU])
class EasyssVpnServiceConfigTest {

    private lateinit var service: EasyssVpnService

    @Before
    fun setUp() {
        PreferenceManager.getDefaultSharedPreferences(
            ApplicationProvider.getApplicationContext()
        ).edit().clear().commit()
        service = Robolectric.buildService(EasyssVpnService::class.java).get()
    }

    @After
    fun tearDown() {
        PreferenceManager.getDefaultSharedPreferences(service).edit().clear().commit()
    }

    @Test
    fun startWithoutProfile_disablesTheService() {
        val pref = Pref(service)
        pref.isServiceEnabled = true

        service.startService()

        assertFalse(
            "a start without an active profile must not leave the service enabled",
            pref.isServiceEnabled,
        )
    }

    @Test
    fun disconnectWhileIdle_keepsTheServiceDisabled() {
        val pref = Pref(service)
        pref.isServiceEnabled = false

        service.onStartCommand(
            Intent(EasyssVpnService.ACTION_DISCONNECT),
            0,
            1,
        )

        assertFalse(pref.isServiceEnabled)
    }
}
