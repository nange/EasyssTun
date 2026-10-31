package com.easysstun

import android.content.Intent
import android.os.Build
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Characterisation tests for the service's configuration preconditions.
 *
 * They pin the new contract: the service reports a failure through
 * [VpnController] and stops itself, but it never writes [Pref.userWantsVpn] —
 * the persisted intent belongs to the user (and the controller).
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
        VpnController.resetForTests()
        TunnelState.update(false)
        service = Robolectric.buildService(EasyssVpnService::class.java).get()
    }

    @After
    fun tearDown() {
        VpnController.resetForTests()
        TunnelState.update(false)
        PreferenceManager.getDefaultSharedPreferences(service).edit().clear().commit()
    }

    @Test
    fun startWithoutProfile_reportsFailureAndStopsWithoutTouchingTheIntent() {
        val pref = Pref(service)
        pref.userWantsVpn = true

        val result = service.onStartCommand(EasyssVpnService.connectIntent(service), 0, 1)

        assertEquals(
            "a start that cannot run must not be restarted by the system",
            android.app.Service.START_NOT_STICKY,
            result
        )
        assertTrue(
            "the service must not clear the user's intent on a failed start",
            pref.userWantsVpn,
        )
        assertTrue(
            "a start without an active profile must stop the service instance",
            shadowOf(service).isStoppedBySelf,
        )
        val state = VpnController.get(service).state.value
        assertTrue("the failure must reach the UI instead of staying 'connecting'", state is VpnState.Failed)
        assertEquals(
            service.getString(R.string.error_no_active_profile),
            (state as VpnState.Failed).message
        )
    }

    @Test
    fun disconnectWhileIdle_stopsTheResidualServiceInstance() {
        // Nothing was ever started: `pref` is not even initialised here. This is
        // the "idle ACTION_DISCONNECT left a service instance behind" bug.
        val result = service.onStartCommand(
            Intent(EasyssVpnService.ACTION_DISCONNECT),
            0,
            1,
        )

        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertTrue(
            "an idle disconnect must finalize the service instance",
            shadowOf(service).isStoppedBySelf,
        )
        assertEquals(VpnState.Idle, VpnController.get(service).state.value)
        assertFalse(TunnelState.ready.value)
    }
}
