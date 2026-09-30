package com.easysstun

import android.content.Intent
import android.os.Build
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Tests for the server profile form: the timeout parameter must be validated and
 * reach the stored profile.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ServerProfileActivityTest {

    private fun launch(profileId: String? = null): ServerProfileActivity {
        val intent = Intent(ApplicationProvider.getApplicationContext(), ServerProfileActivity::class.java)
        profileId?.let { intent.putExtra("profileId", it) }
        return Robolectric.buildActivity(ServerProfileActivity::class.java, intent).setup().get()
    }

    private fun ServerProfileActivity.field(id: Int): EditText = findViewById(id)

    private fun ServerProfileActivity.fillRequired() {
        field(R.id.profile_server).setText("example.com")
        field(R.id.profile_server_port).setText("443")
        field(R.id.profile_password).setText("secret")
    }

    private fun ServerProfileActivity.save() {
        findViewById<Button>(R.id.save_profile_button).performClick()
    }

    @Test
    fun save_storesTimeoutChosenByTheUser() {
        val activity = launch()
        activity.fillRequired()
        activity.field(R.id.profile_timeout).setText("45")

        activity.save()

        val saved = Pref(activity).getProfiles().single()
        assertEquals("45", saved.timeout)
        assertEquals(45, saved.timeoutSeconds())
    }

    @Test
    fun save_allowsBlankTimeoutAsNativeDefault() {
        val activity = launch()
        activity.fillRequired()
        activity.field(R.id.profile_timeout).setText("")

        activity.save()

        val saved = Pref(activity).getProfiles().single()
        assertEquals("", saved.timeout)
        assertEquals("A blank timeout must leave libeasyss on its own default", 0, saved.timeoutSeconds())
    }

    @Test
    fun save_rejectsTimeoutOutsideTheNativeRange() {
        val activity = launch()
        activity.fillRequired()
        activity.field(R.id.profile_timeout).setText("5")

        activity.save()

        assertTrue("An invalid timeout must not create a profile", Pref(activity).getProfiles().isEmpty())
        assertEquals(
            "Timeout must be a whole number of seconds (15–60)",
            ShadowToast.getTextOfLatestToast()
        )
    }

    @Test
    fun edit_showsTheStoredTimeout() {
        val pref = Pref(ApplicationProvider.getApplicationContext())
        val profile = Profile(
            id = "timeout-profile",
            name = "Server",
            server = "example.com",
            serverPort = "443",
            password = "secret",
            timeout = "50"
        )
        pref.addProfile(profile)

        val activity = launch(profileId = profile.id)

        assertEquals("50", activity.field(R.id.profile_timeout).text.toString())
    }
}
