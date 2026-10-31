package com.easysstun

import android.content.Context
import android.content.Intent
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * Characterisation tests for the home screen's connect/disconnect state
 * machine. They pin the observable UI states that the planned VpnState
 * refactor must preserve; they do not assert how the state is stored.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class MainFragmentStateTest {

    private lateinit var context: Context
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
    }

    @After
    fun tearDown() {
        controller.destroy()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
    }

    private fun mainFragment(): MainFragment {
        val navHost = activity.supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_content_main) as NavHostFragment
        return navHost.childFragmentManager.primaryNavigationFragment as MainFragment
    }

    private fun button(): MaterialButton =
        mainFragment().requireView().findViewById(R.id.service_button)

    private fun statsGrid(): LinearLayout =
        mainFragment().requireView().findViewById(R.id.stats_grid)

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
    fun start_entersConnectingStateUntilServiceReportsStarted() {
        val profile = addProfile("A")
        Pref(context).setActiveServer(profile.id)
        // Recreate so the fragment picks up the profile added after launch.
        controller.recreate()
        activity = controller.get()

        button().performClick()

        assertEquals(
            activity.getString(R.string.service_connecting),
            button().text.toString()
        )
        assertFalse("the start button must be disabled while connecting", button().isEnabled)

        // The service reports a fully established tunnel.
        context.sendBroadcast(
            Intent(EasyssVpnService.ACTION_SERVICE_STARTED).setPackage(context.packageName)
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(activity.getString(R.string.service_disable), button().text.toString())
        assertTrue(button().isEnabled)
        assertEquals(View.VISIBLE, statsGrid().visibility)
    }

    @Test
    fun serviceStoppedWhileConnecting_returnsToStoppedState() {
        val profile = addProfile("A")
        Pref(context).setActiveServer(profile.id)
        controller.recreate()
        activity = controller.get()

        button().performClick()
        assertFalse(button().isEnabled)

        // The same click target is disabled while connecting; the user stops
        // through the preference flag + the UI refresh triggered by the
        // service-failure/stop path. Exercise the stop branch directly.
        Pref(context).isServiceEnabled = false
        context.sendBroadcast(
            Intent(EasyssVpnService.ACTION_SERVICE_STOPPED).setPackage(context.packageName)
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(activity.getString(R.string.service_enable), button().text.toString())
        assertTrue(button().isEnabled)
        assertEquals(View.GONE, statsGrid().visibility)
    }

    @Test
    fun switchingServer_activatesPendingProfileWhenServiceStops() {
        val first = addProfile("A")
        addProfile("B")
        Pref(context).setActiveServer(first.id)
        Pref(context).isServiceEnabled = true
        controller.recreate()
        activity = controller.get()

        val spinner = mainFragment().requireView().findViewById<Spinner>(R.id.server_spinner)
        spinner.setSelection(1)
        shadowOf(Looper.getMainLooper()).idle()

        // Selecting a different server only arms the switch: the fragment stops
        // the service and waits for ACTION_SERVICE_STOPPED before activating the
        // pending profile.
        assertFalse("the spinner is disabled while switching", spinner.isEnabled)
        assertEquals("A", Pref(context).getActiveProfile()?.name)

        context.sendBroadcast(
            Intent(EasyssVpnService.ACTION_SERVICE_STOPPED).setPackage(context.packageName)
        )
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals("B", Pref(context).getActiveProfile()?.name)
        assertTrue("the spinner is usable again after the switch", spinner.isEnabled)
        assertEquals(activity.getString(R.string.service_disable), button().text.toString())
    }

    @Test
    fun rotation_whileEnabled_keepsRunningUi() {
        val profile = addProfile("A")
        Pref(context).setActiveServer(profile.id)
        Pref(context).isServiceEnabled = true
        controller.recreate()
        activity = controller.get()

        assertEquals(activity.getString(R.string.service_disable), button().text.toString())
        assertTrue(button().isEnabled)

        controller.recreate()
        activity = controller.get()

        assertEquals(
            "recreating the activity must not drop the running state",
            activity.getString(R.string.service_disable),
            button().text.toString()
        )
    }
}
