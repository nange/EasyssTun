package com.easysstun

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVpnService

/**
 * Tests for the process-wide VPN state machine.
 *
 * These replace the old broadcast-based contract tests: the three custom
 * broadcasts (STARTED / STOPPED / START_FAILED) are gone, and the service now
 * reports the same facts directly to [VpnController].
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.TIRAMISU])
class VpnControllerTest {

    private lateinit var context: Context
    private lateinit var pref: Pref
    private lateinit var profiles: ProfileStore
    private lateinit var controller: VpnController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        VpnController.resetForTests()
        ProfileStore.resetForTests()
        TunnelState.update(false)
        ShadowVpnService.setPrepareResult(null)
        pref = Pref(context)
        profiles = ProfileStore.get(context)
        controller = VpnController.get(context)
    }

    @After
    fun tearDown() {
        VpnController.resetForTests()
        ProfileStore.resetForTests()
        TunnelState.update(false)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun addProfile(name: String, id: String = name): Profile {
        val profile = Profile(
            id = id,
            name = name,
            server = "example.com",
            serverPort = 443,
            password = "secret",
        )
        profiles.add(profile)
        return profile
    }

    private fun startedServiceIntents(): List<Intent> {
        val application = context as Application
        return shadowOf(application).allStartedServices
    }

    // ── Commands ────────────────────────────────────────────────

    @Test
    fun initialState_isIdle() {
        assertEquals(VpnState.Idle, controller.state.value)
    }

    @Test
    fun connect_withoutProfile_staysIdleAndKeepsTheIntentOff() {
        assertEquals(ConnectRequest.NoProfile, controller.connect())

        assertEquals(VpnState.Idle, controller.state.value)
        assertFalse(pref.userWantsVpn)
        assertTrue("no service must be started", startedServiceIntents().isEmpty())
    }

    @Test
    fun connect_startsTheServiceAndEntersConnecting() {
        val profile = addProfile("A")
        profiles.select(profile.id)

        assertEquals(ConnectRequest.Started, controller.connect())

        assertEquals(VpnState.Connecting(profile.id), controller.state.value)
        assertTrue("an explicit start records the user's intent", pref.userWantsVpn)
        val started = startedServiceIntents().single()
        assertEquals(EasyssVpnService.ACTION_CONNECT, started.action)
        assertNull("configuration must not travel through the Intent", started.extras)
    }

    @Test
    fun connect_whileConnectingOrRunning_isIgnored() {
        val profile = addProfile("A")
        profiles.select(profile.id)

        controller.connect()
        assertEquals(ConnectRequest.AlreadyActive, controller.connect())
        controller.onServiceRunning(profile.id)
        assertEquals(ConnectRequest.AlreadyActive, controller.connect())

        assertEquals(
            "a duplicate command must not start the service twice",
            1,
            startedServiceIntents().size
        )
    }

    @Test
    fun connect_needsPermission_doesNotStartUntilGranted() {
        val profile = addProfile("A")
        profiles.select(profile.id)
        val permissionIntent = Intent("android.net.vpn.SETUP")
        ShadowVpnService.setPrepareResult(permissionIntent)

        val request = controller.connect()

        assertEquals(ConnectRequest.NeedsPermission(permissionIntent), request)
        assertEquals(VpnState.Connecting(profile.id), controller.state.value)
        assertTrue("no start before the permission is granted", startedServiceIntents().isEmpty())

        controller.onPermissionResult(granted = true)

        assertEquals(EasyssVpnService.ACTION_CONNECT, startedServiceIntents().single().action)
    }

    @Test
    fun connect_permissionDenied_revertsToIdleAndClearsTheIntent() {
        val profile = addProfile("A")
        profiles.select(profile.id)
        ShadowVpnService.setPrepareResult(Intent("android.net.vpn.SETUP"))
        controller.connect()

        controller.onPermissionResult(granted = false)

        assertEquals(VpnState.Idle, controller.state.value)
        assertFalse("declining the system dialog is an explicit stop", pref.userWantsVpn)
    }

    @Test
    fun disconnect_whileRunning_entersStoppingAndStopsTheService() {
        val profile = addProfile("A")
        profiles.select(profile.id)
        controller.connect()
        controller.onServiceRunning(profile.id)

        controller.disconnect()

        assertEquals(VpnState.Stopping, controller.state.value)
        assertFalse(pref.userWantsVpn)
        assertEquals(
            EasyssVpnService.ACTION_DISCONNECT,
            startedServiceIntents().last().action
        )

        controller.onServiceStopped()

        assertEquals(VpnState.Idle, controller.state.value)
        assertFalse(TunnelState.ready.value)
    }

    @Test
    fun disconnect_whileIdle_stillSendsACleanupIntent() {
        controller.disconnect()

        assertEquals(VpnState.Idle, controller.state.value)
        assertEquals(
            "an idle disconnect must still reach a residual service instance",
            EasyssVpnService.ACTION_DISCONNECT,
            startedServiceIntents().single().action
        )
    }

    @Test
    fun resumeConnect_recoversADroppedPermissionResult() {
        val profile = addProfile("A")
        profiles.select(profile.id)
        ShadowVpnService.setPrepareResult(Intent("android.net.vpn.SETUP"))
        controller.connect()
        assertTrue(startedServiceIntents().isEmpty())

        // The activity that launched the dialog was destroyed, so no result was
        // delivered; the permission has since been granted.
        ShadowVpnService.setPrepareResult(null)
        assertEquals(ConnectRequest.Started, controller.resume())

        assertEquals(EasyssVpnService.ACTION_CONNECT, startedServiceIntents().single().action)
        assertEquals(VpnState.Connecting(profile.id), controller.state.value)
    }

    @Test
    fun resumeConnect_isANoOpOnceTheStartWasIssued() {
        val profile = addProfile("A")
        profiles.select(profile.id)
        controller.connect()

        assertEquals(ConnectRequest.AlreadyActive, controller.resume())
        assertEquals("a resume must not issue a second start", 1, startedServiceIntents().size)
    }

    // ── Service facts ───────────────────────────────────────────

    @Test
    fun onServiceRunning_marksRunningAndPublishesTunnelReadiness() {
        val profile = addProfile("A")

        controller.onServiceStarting(profile.id)
        assertFalse(TunnelState.ready.value)
        controller.onServiceRunning(profile.id)

        assertEquals(VpnState.Running(profile.id), controller.state.value)
        assertTrue(TunnelState.ready.value)
    }

    @Test
    fun onServiceRunning_afterAStopRequest_isIgnored() {
        val profile = addProfile("A")
        profiles.select(profile.id)
        controller.connect()
        controller.disconnect()

        controller.onServiceRunning(profile.id)

        assertEquals(
            "a late startup must not resurrect a tunnel the user stopped",
            VpnState.Stopping,
            controller.state.value
        )
    }

    @Test
    fun onServiceFailed_keepsTheFailureAndReportsItExactlyOnce() {
        val profile = addProfile("A")

        controller.onServiceFailed("dial tcp 1.2.3.4:443: connection refused", profile.id)

        assertEquals(
            VpnState.Failed("dial tcp 1.2.3.4:443: connection refused", profile.id),
            controller.state.value
        )
        assertEquals(
            "dial tcp 1.2.3.4:443: connection refused",
            controller.consumeFailure()
        )
        assertNull("the same failure must not be shown twice", controller.consumeFailure())
    }

    @Test
    fun onServiceFailed_survivesTheFollowingStop() {
        controller.onServiceFailed("boom", null)

        controller.onServiceStopped()

        assertTrue(
            "finalizing the stopped service must not erase the failure",
            controller.state.value is VpnState.Failed
        )
    }

    @Test
    fun onServiceDestroyed_whileRunning_returnsToIdle() {
        val profile = addProfile("A")
        controller.onServiceStarting(profile.id)
        controller.onServiceRunning(profile.id)

        controller.onServiceDestroyed()

        assertEquals(VpnState.Idle, controller.state.value)
        assertFalse(TunnelState.ready.value)
    }

    @Test
    fun onServiceDestroyed_whileConnecting_returnsToIdleSoTheScreenCanRetry() {
        val profile = addProfile("A")
        controller.onServiceStarting(profile.id)

        controller.onServiceDestroyed()

        assertEquals(
            "a destroyed service must not leave the screen stuck on 'connecting'",
            VpnState.Idle,
            controller.state.value
        )
    }

    @Test
    fun onServiceDestroyed_whileStopping_returnsToIdle() {
        val profile = addProfile("A")
        profiles.select(profile.id)
        controller.connect()
        controller.disconnect()
        assertEquals(VpnState.Stopping, controller.state.value)

        controller.onServiceDestroyed()

        assertEquals(VpnState.Idle, controller.state.value)
    }

    @Test
    fun onServiceDestroyed_whileFailed_keepsTheFailure() {
        controller.onServiceFailed("boom", null)

        controller.onServiceDestroyed()

        assertTrue(controller.state.value is VpnState.Failed)
    }

    @Test
    fun onPermissionRevoked_clearsTheIntentAndStops() {
        val profile = addProfile("A")
        profiles.select(profile.id)
        controller.connect()
        controller.onServiceRunning(profile.id)

        controller.onPermissionRevoked()

        assertFalse(pref.userWantsVpn)
        assertEquals(VpnState.Stopping, controller.state.value)
    }

    @Test
    fun onServiceStopped_reportsWhetherItStartedAReplacement() {
        val first = addProfile("A")
        val second = addProfile("B")
        profiles.select(first.id)
        controller.connect()
        controller.onServiceRunning(first.id)
        controller.switchTo(second.id)

        assertTrue(
            "a switch issues a follow-up start, so the stopping instance is replaced",
            controller.onServiceStopped()
        )

        controller.onServiceRunning(second.id)
        controller.disconnect()
        assertFalse("a plain stop has no replacement", controller.onServiceStopped())
    }

    @Test
    fun onServiceStarting_duringAStop_isIgnored() {
        val profile = addProfile("A")
        profiles.select(profile.id)
        controller.connect()
        controller.onServiceRunning(profile.id)
        controller.disconnect()

        controller.onServiceStarting(profile.id)

        assertEquals(VpnState.Stopping, controller.state.value)
    }

    @Test
    fun onServiceStarting_duringASwitch_isIgnored() {
        val first = addProfile("A")
        val second = addProfile("B")
        profiles.select(first.id)
        controller.connect()
        controller.onServiceRunning(first.id)
        controller.switchTo(second.id)

        controller.onServiceStarting(first.id)

        assertEquals(VpnState.Switching(first.id, second.id), controller.state.value)
    }

    @Test
    fun onServiceStarting_afterARestart_dropsReadiness() {
        val profile = addProfile("A")
        profiles.select(profile.id)
        controller.connect()
        controller.onServiceRunning(profile.id)
        assertTrue(TunnelState.ready.value)

        // PREFS_UPDATED restart: the service rebuilds the tunnel from Pref.
        controller.onServiceStarting(profile.id)

        assertEquals(VpnState.Connecting(profile.id), controller.state.value)
        assertFalse(
            "readiness must drop as soon as the tunnel is being rebuilt",
            TunnelState.ready.value
        )
    }

    @Test
    fun onServiceFailed_repeatedMessage_isNotShownTwice() {
        controller.onServiceFailed("boom", null)
        assertEquals("boom", controller.consumeFailure())

        controller.onServiceFailed("boom", null)

        assertNull("the same failure must not re-arm the dialog", controller.consumeFailure())
    }

    // ── Server switch ───────────────────────────────────────────

    @Test
    fun switchTo_whileRunning_stopsFirstAndStartsTheNewProfileAfterwards() {
        val first = addProfile("A")
        val second = addProfile("B")
        profiles.select(first.id)
        controller.connect()
        controller.onServiceRunning(first.id)

        controller.switchTo(second.id)

        assertEquals(VpnState.Switching(first.id, second.id), controller.state.value)
        assertEquals(
            "the target becomes active immediately so a process death resumes on it",
            second.id,
            profiles.active()?.id
        )
        assertEquals(EasyssVpnService.ACTION_DISCONNECT, startedServiceIntents().last().action)

        controller.onServiceStopped()

        assertEquals(VpnState.Connecting(second.id), controller.state.value)
        assertEquals(EasyssVpnService.ACTION_CONNECT, startedServiceIntents().last().action)

        controller.onServiceRunning(second.id)
        assertEquals(VpnState.Running(second.id), controller.state.value)
    }

    @Test
    fun switchTo_whileIdle_onlySelectsTheProfile() {
        val first = addProfile("A")
        val second = addProfile("B")
        profiles.select(first.id)

        controller.switchTo(second.id)

        assertEquals(VpnState.Idle, controller.state.value)
        assertEquals(second.id, profiles.active()?.id)
        assertTrue("selecting a server while stopped is not a VPN command", startedServiceIntents().isEmpty())
    }

    @Test
    fun switchTo_sameProfile_isIgnored() {
        val profile = addProfile("A")
        profiles.select(profile.id)
        controller.connect()
        controller.onServiceRunning(profile.id)

        controller.switchTo(profile.id)

        assertEquals(VpnState.Running(profile.id), controller.state.value)
    }

    @Test
    fun switchTo_whileConnecting_restartsForTheNewProfile() {
        val first = addProfile("A")
        val second = addProfile("B")
        profiles.select(first.id)
        controller.connect()

        controller.switchTo(second.id)
        assertEquals(VpnState.Switching(first.id, second.id), controller.state.value)

        controller.onServiceStopped()
        assertEquals(VpnState.Connecting(second.id), controller.state.value)
    }

    @Test
    fun disconnect_duringASwitch_cancelsIt() {
        val first = addProfile("A")
        val second = addProfile("B")
        profiles.select(first.id)
        controller.connect()
        controller.onServiceRunning(first.id)
        controller.switchTo(second.id)

        controller.disconnect()
        controller.onServiceStopped()

        assertEquals(
            "cancelling a switch must not start the new profile",
            VpnState.Idle,
            controller.state.value
        )
    }

    @Test
    fun switchTo_deletedTarget_abandonsTheSwitch() {
        val first = addProfile("A")
        val second = addProfile("B")
        profiles.select(first.id)
        controller.connect()
        controller.onServiceRunning(first.id)
        controller.switchTo(second.id)

        profiles.delete(second.id)
        controller.onServiceStopped()

        assertEquals(VpnState.Idle, controller.state.value)
    }

    // ── Process rebuild ─────────────────────────────────────────

    @Test
    fun freshProcess_startsIdleAndResumesFromThePersistedIntent() {
        val profile = addProfile("A")
        profiles.select(profile.id)
        pref.userWantsVpn = true

        // A new process cannot inherit the tunnel: the VPN service lives in it,
        // so the state starts Idle instead of fabricating a connection.
        VpnController.resetForTests()
        val rebuilt = VpnController.get(context)
        assertEquals(VpnState.Idle, rebuilt.state.value)

        // The persisted intent is what brings it back when the screen opens.
        assertEquals(ConnectRequest.Started, rebuilt.resume())
        assertEquals(VpnState.Connecting(profile.id), rebuilt.state.value)
        assertEquals(EasyssVpnService.ACTION_CONNECT, startedServiceIntents().single().action)
        assertSame(rebuilt, VpnController.get(context))
    }

    @Test
    fun resume_afterAFailure_doesNotRetryAutomatically() {
        val profile = addProfile("A")
        profiles.select(profile.id)
        pref.userWantsVpn = true
        controller.onServiceFailed("boom", profile.id)

        assertEquals(ConnectRequest.AlreadyActive, controller.resume())
        assertTrue(startedServiceIntents().isEmpty())
        assertTrue(
            "the failure must stay on screen until the user acts",
            controller.state.value is VpnState.Failed
        )
    }

    @Test
    fun resume_whileTheUserDoesNotWantTheVpn_doesNothing() {
        val profile = addProfile("A")
        profiles.select(profile.id)

        assertEquals(ConnectRequest.AlreadyActive, controller.resume())
        assertEquals(VpnState.Idle, controller.state.value)
        assertTrue(startedServiceIntents().isEmpty())
    }
}
