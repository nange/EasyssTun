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
 * Tests for the server profile form: parameter fields must be saved without
 * surrounding whitespace, and the timeout parameter must reach the profile.
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
    fun save_trimsSurroundingWhitespaceOfEveryParameterField() {
        val activity = launch()
        activity.field(R.id.profile_name).setText("  My Server  ")
        activity.field(R.id.profile_server).setText(" example.com ")
        activity.field(R.id.profile_server_port).setText(" 8443 ")
        activity.field(R.id.profile_password).setText(" secret ")
        activity.field(R.id.profile_socks_port).setText(" 2081 ")
        activity.field(R.id.profile_server_name_indication).setText(" sni.example.com ")
        activity.field(R.id.profile_custom_ca).setText("\n-----BEGIN CERTIFICATE-----\nMOCK\n-----END CERTIFICATE-----\n")
        activity.field(R.id.profile_direct_file).setText(" example.org\n")
        activity.field(R.id.profile_proxy_file).setText(" example.net ")
        activity.field(R.id.profile_timeout).setText(" 45 ")

        activity.save()

        val saved = Pref(activity).getProfiles().single()
        assertEquals("My Server", saved.name)
        assertEquals("example.com", saved.server)
        assertEquals("8443", saved.serverPort)
        assertEquals("secret", saved.password)
        assertEquals("2081", saved.socksPort)
        assertEquals("sni.example.com", saved.serverNameIndication)
        assertEquals("-----BEGIN CERTIFICATE-----\nMOCK\n-----END CERTIFICATE-----", saved.customCa)
        assertEquals("example.org", saved.directFile)
        assertEquals("example.net", saved.proxyFile)
        assertEquals("45", saved.timeout)
    }

    @Test
    fun save_storesTimeoutChosenByTheUser() {
        val activity = launch()
        activity.fillRequired()
        activity.field(R.id.profile_timeout).setText(" 45 ")

        activity.save()

        val saved = Pref(activity).getProfiles().single()
        assertEquals("45", saved.timeout)
        assertEquals(45, saved.timeoutSeconds())
    }

    @Test
    fun save_allowsBlankTimeoutAsNativeDefault() {
        val activity = launch()
        activity.fillRequired()
        activity.field(R.id.profile_timeout).setText("   ")

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
    fun parameterField_isTrimmedWhenItLosesFocus() {
        val activity = launch()
        val server = activity.field(R.id.profile_server)
        server.setText("  example.com  ")

        // Focus gained: keep the text untouched so typing never fights the caret.
        server.onFocusChangeListener.onFocusChange(server, true)
        assertEquals("  example.com  ", server.text.toString())
        // Focus lost: strip the stray whitespace right away.
        server.onFocusChangeListener.onFocusChange(server, false)
        assertEquals("example.com", server.text.toString())
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
