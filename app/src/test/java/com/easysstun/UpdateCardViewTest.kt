package com.easysstun

import android.content.Context
import android.os.Build
import android.view.ContextThemeWrapper
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class UpdateCardViewTest {

    private val applicationContext: Context = ApplicationProvider.getApplicationContext()

    // Material widgets require the app's Material theme; the Robolectric
    // application context does not carry it.
    private val context: Context =
        ContextThemeWrapper(applicationContext, R.style.Theme)

    @Test
    fun showsAppVersion() {
        val view = UpdateCardView(context)
        val version = view.findViewById<TextView>(R.id.update_current_version).text.toString()

        assertTrue(version.contains(BuildConfig.VERSION_NAME))
        assertTrue(version.contains(BuildConfig.VERSION_CODE.toString()))
    }

    @Test
    fun debugBuildExplainsWhyUpdatesAreDisabled() {
        val view = UpdateCardView(context)

        assertFalse(view.findViewById<View>(R.id.update_check_button).isEnabled)
        assertFalse(view.findViewById<View>(R.id.update_auto_check_switch).isEnabled)
        assertEquals(
            View.GONE,
            view.findViewById<View>(R.id.update_download_button).visibility,
        )
        assertEquals(
            context.getString(R.string.update_debug_disabled),
            view.findViewById<TextView>(R.id.update_status).text.toString(),
        )
    }
}
