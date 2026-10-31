package com.easysstun

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowVpnService

/**
 * Tests for ServiceReceiver: boot-completed auto-start logic.
 *
 * Since VpnService.prepare() and startForegroundService() require a real Android
 * environment, we focus on verifying the branching logic:
 *   - BOOT_COMPLETED + isServiceEnabled=true + active profile → tries to start
 *   - BOOT_COMPLETED + isServiceEnabled=true + no active profile → does not start
 *   - BOOT_COMPLETED + isServiceEnabled=false → does nothing
 *   - Non-BOOT_COMPLETED action               → does nothing
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.TIRAMISU])
class ServiceReceiverTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var pref: Pref

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        pref = Pref(context)
        sharedPreferences = pref.prefs
        sharedPreferences.edit().clear().commit()
    }

    @After
    fun tearDown() {
        ShadowVpnService.reset()
        sharedPreferences.edit().clear().commit()
    }

    @Test
    fun onReceive_bootCompleted_serviceDisabled_doesNotStart() {
        // Confirm service is disabled
        pref.isServiceEnabled = false
        assertFalse("Service should be disabled", pref.isServiceEnabled)

        val receiver = ServiceReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)

        // This should not throw and should not change service state
        receiver.onReceive(context, intent)

        // Service should remain disabled (no start attempted)
        assertFalse("Service should still be disabled after broadcast", pref.isServiceEnabled)
    }

    @Test
    fun onReceive_bootCompleted_serviceEnabled_attemptsStart() {
        // Set a valid profile so the service would have something to connect to
        val profile = Profile(
            id = "boot-test-id",
            name = "Boot Test Server",
            server = "test.example.com",
            serverPort = "443",
            password = "test-password"
        )
        pref.addProfile(profile)
        pref.setActiveServer(profile.id)
        pref.isServiceEnabled = true

        val application = context as Application
        shadowOf(application).clearStartedServices()
        // Force VpnService.prepare() to report that permission is already granted,
        // so the receiver takes the direct startForegroundService() branch.
        ShadowVpnService.setPrepareResult(null)

        val receiver = ServiceReceiver()
        val intent = Intent(Intent.ACTION_BOOT_COMPLETED)
        receiver.onReceive(context, intent)

        val started = shadowOf(application).nextStartedService
        assertNotNull("boot auto-start must start the VPN service", started)
        assertEquals(EasyssVpnService.ACTION_CONNECT, started.action)
        assertNull(
            "the service must read its configuration from Pref, not the Intent",
            started.extras
        )
    }

    @Test
    fun onReceive_bootCompleted_enabledButNoProfile_doesNotStartAndClearsFlag() {
        pref.isServiceEnabled = true

        val application = context as Application
        shadowOf(application).clearStartedServices()

        val receiver = ServiceReceiver()
        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertNull(
            "boot auto-start must not start a foreground service without an active profile",
            shadowOf(application).nextStartedService
        )
        assertFalse("the stale enabled flag must be cleared", pref.isServiceEnabled)
    }

    @Test
    fun onReceive_nonBootAction_doesNothing() {
        pref.isServiceEnabled = true

        val receiver = ServiceReceiver()
        val intent = Intent("some.other.action")

        // Should silently return without doing anything
        receiver.onReceive(context, intent)

        // Verify nothing changed — prefs should still be intact
        assertTrue("Service should still be enabled after non-boot broadcast", pref.isServiceEnabled)
    }

    @Test
    fun onReceive_nullAction_doesNothing() {
        pref.isServiceEnabled = true

        val receiver = ServiceReceiver()
        val intent = Intent() // No action set

        // Should not crash
        receiver.onReceive(context, intent)

        // Service state unchanged
        assertTrue("Service should still be enabled", pref.isServiceEnabled)
    }
}
