package com.easysstun

import android.content.Context
import android.os.Build
import android.os.Looper
import android.view.View
import android.widget.LinearLayout
import android.widget.Spinner
import androidx.navigation.fragment.NavHostFragment
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.android.material.button.MaterialButton
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowVpnService

/**
 * Characterisation tests for the home screen's connect/disconnect state
 * machine. The screen is a pure renderer of [VpnState], so these tests drive
 * the process-wide [VpnController] (the service's reports) and assert what the
 * user sees.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class MainFragmentStateTest {

    private lateinit var context: Context
    private lateinit var activityController: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var vpn: VpnController

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        VpnController.resetForTests()
        TunnelState.update(false)
        ShadowVpnService.setPrepareResult(null)
        vpn = VpnController.get(context)
        activityController = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = activityController.get()
    }

    @After
    fun tearDown() {
        activityController.destroy()
        VpnController.resetForTests()
        TunnelState.update(false)
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun mainFragment(): MainFragment {
        val navHost = activity.supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        return navHost.childFragmentManager.primaryNavigationFragment as MainFragment
    }

    private fun button(): MaterialButton =
        mainFragment().requireView().findViewById(R.id.service_button)

    private fun spinner(): Spinner =
        mainFragment().requireView().findViewById(R.id.server_spinner)

    private fun statsGrid(): LinearLayout =
        mainFragment().requireView().findViewById(R.id.stats_grid)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun recreate() {
        activityController.recreate()
        activity = activityController.get()
        idle()
    }

    private fun addProfile(name: String, id: String = name): Profile {
        val profile = Profile(
            id = id,
            name = name,
            server = "example.com",
            serverPort = "443",
            password = "secret",
        )
        Pref(context).addProfile(profile)
        return profile
    }

    @Test
    fun noProfile_showsDisabledStoppedState() {
        assertEquals(activity.getString(R.string.service_enable), button().text.toString())
        assertFalse(button().isEnabled)
        assertEquals(View.GONE, statsGrid().visibility)
    }

    @Test
    fun start_entersConnectingStateUntilServiceReportsRunning() {
        val profile = addProfile("A")
        Pref(context).setActiveServer(profile.id)
        // Recreate so the fragment picks up the profile added after launch.
        recreate()

        button().performClick()
        idle()

        assertEquals(
            activity.getString(R.string.service_connecting),
            button().text.toString()
        )
        assertFalse("the start button must be disabled while connecting", button().isEnabled)

        // The service reports a fully established tunnel.
        vpn.onServiceRunning(profile.id)
        idle()

        assertEquals(activity.getString(R.string.service_disable), button().text.toString())
        assertTrue(button().isEnabled)
        assertEquals(View.VISIBLE, statsGrid().visibility)
    }

    @Test
    fun stopWhileConnecting_returnsToStoppedState() {
        val profile = addProfile("A")
        Pref(context).setActiveServer(profile.id)
        recreate()

        button().performClick()
        idle()
        assertFalse(button().isEnabled)

        // The user stops while the tunnel is still coming up.
        vpn.disconnect()
        idle()
        assertEquals(
            activity.getString(R.string.service_stopping),
            button().text.toString()
        )
        assertFalse(button().isEnabled)

        // The service finished tearing down.
        vpn.onServiceStopped()
        idle()

        assertEquals(activity.getString(R.string.service_enable), button().text.toString())
        assertTrue(button().isEnabled)
        assertEquals(View.GONE, statsGrid().visibility)
    }

    @Test
    fun switchingServer_activatesTheNewProfileAndRebuildsTheTunnel() {
        val first = addProfile("A")
        val second = addProfile("B")
        Pref(context).setActiveServer(first.id)
        Pref(context).userWantsVpn = true
        vpn.onServiceRunning(first.id)
        recreate()

        spinner().setSelection(1)
        idle()

        // Selecting a different server asks the controller for a switch: the
        // target becomes the active profile immediately (so a process death
        // resumes on it) while the old tunnel is torn down first.
        assertEquals(
            activity.getString(R.string.service_switching),
            button().text.toString()
        )
        assertFalse("the spinner is disabled while switching", spinner().isEnabled)
        assertEquals("B", Pref(context).getActiveProfile()?.name)

        // The old tunnel is gone: the switch continues with the new profile.
        vpn.onServiceStopped()
        idle()
        assertEquals("B", Pref(context).getActiveProfile()?.name)
        assertEquals(
            activity.getString(R.string.service_connecting),
            button().text.toString()
        )

        vpn.onServiceRunning(second.id)
        idle()
        assertTrue("the spinner is usable again after the switch", spinner().isEnabled)
        assertEquals(activity.getString(R.string.service_disable), button().text.toString())
    }

    @Test
    fun rotationDuringSwitch_keepsTheSwitchingState() {
        val first = addProfile("A")
        val second = addProfile("B")
        Pref(context).setActiveServer(first.id)
        Pref(context).userWantsVpn = true
        vpn.onServiceRunning(first.id)
        recreate()

        spinner().setSelection(1)
        idle()

        // The pending target lives in VpnState now, not in the fragment.
        recreate()
        assertEquals(
            activity.getString(R.string.service_switching),
            button().text.toString()
        )
        assertFalse(spinner().isEnabled)

        vpn.onServiceStopped()
        idle()
        assertEquals("B", Pref(context).getActiveProfile()?.name)

        vpn.onServiceRunning(second.id)
        idle()
        assertEquals(activity.getString(R.string.service_disable), button().text.toString())
        assertTrue(spinner().isEnabled)
    }

    @Test
    fun rotation_whileRunning_keepsRunningUi() {
        val profile = addProfile("A")
        Pref(context).setActiveServer(profile.id)
        Pref(context).userWantsVpn = true
        vpn.onServiceRunning(profile.id)
        recreate()

        assertEquals(activity.getString(R.string.service_disable), button().text.toString())
        assertTrue(button().isEnabled)

        recreate()

        assertEquals(
            "recreating the activity must not drop the running state",
            activity.getString(R.string.service_disable),
            button().text.toString()
        )
    }

    @Test
    fun startFailure_showsTheErrorOnceAndReturnsToStopped() {
        val profile = addProfile("A")
        Pref(context).setActiveServer(profile.id)
        recreate()

        button().performClick()
        idle()

        vpn.onServiceFailed("dial tcp 1.2.3.4:443: connection refused", profile.id)
        idle()

        val dialog = ShadowDialog.getLatestDialog()
        assertNotNull("a failed start must explain itself", dialog)
        assertTrue(dialog.isShowing)
        assertEquals(activity.getString(R.string.service_enable), button().text.toString())
        assertTrue(button().isEnabled)

        // A rotation must not pop the same dialog again.
        recreate()
        assertSame("the failure must be reported only once", dialog, ShadowDialog.getLatestDialog())
    }
}
