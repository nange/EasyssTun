package com.easysstun

import android.content.Context
import android.content.SharedPreferences
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
import org.robolectric.annotation.Config

/**
 * Tests for the key/value preference layer.
 *
 * The server profile repository and the per-app selection no longer live here;
 * they are covered by [ProfileStoreTest] and [AppSelectionStoreTest].
 */
@RunWith(AndroidJUnit4::class)
// The sdk must be pinned explicitly: Robolectric reads the app manifest (binary
// resources are enabled) and rejects the unsupported targetSdk 37 otherwise.
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class PrefTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var pref: Pref

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        sharedPreferences.edit().clear().apply()
        pref = Pref(context)
    }

    @After
    fun tearDown() {
        sharedPreferences.edit().clear().apply()
    }

    @Test
    fun setAndGetIsServiceEnabled() {
        assertFalse("Initially, userWantsVpn should be false", pref.userWantsVpn)
        assertFalse("SharedPreferences should reflect false initially", sharedPreferences.getBoolean(Pref.SERVICE_ENABLED, false))

        pref.userWantsVpn = true
        assertTrue("userWantsVpn should be true after setting to true", pref.userWantsVpn)
        assertTrue("SharedPreferences should reflect true", sharedPreferences.getBoolean(Pref.SERVICE_ENABLED, false))

        pref.userWantsVpn = false
        assertFalse("userWantsVpn should be false after setting to false", pref.userWantsVpn)
        assertFalse("SharedPreferences should reflect false", sharedPreferences.getBoolean(Pref.SERVICE_ENABLED, true))
    }

    @Test
    fun proxyMode_defaultsToBypass() {
        assertEquals(Pref.PROXY_MODE_BYPASS, pref.proxyMode)
    }

    @Test
    fun proxyMode_roundTripsAndIsAPlainWrite() {
        pref.proxyMode = Pref.PROXY_MODE_PROXY_ONLY

        assertEquals(Pref.PROXY_MODE_PROXY_ONLY, pref.proxyMode)
        assertEquals(
            "the stored key is the one existing installs use",
            Pref.PROXY_MODE_PROXY_ONLY,
            sharedPreferences.getString(Pref.PROXY_MODE_KEY, null),
        )
        // A new Pref instance (same process, same file) sees the value.
        assertEquals(Pref.PROXY_MODE_PROXY_ONLY, Pref(context).proxyMode)
    }

    @Test
    fun showSystemApps_defaultsToTrueAndRoundTrips() {
        assertTrue(pref.showSystemApps)

        pref.showSystemApps = false

        assertFalse(pref.showSystemApps)
        assertFalse(Pref(context).showSystemApps)
    }

    @Test
    fun updatePreferences_haveStableDefaults() {
        assertTrue("Automatic update checks default to enabled", pref.updateAutoCheck)
        assertEquals("No check recorded yet", 0L, pref.updateLastCheckAt)
        assertEquals("Nothing skipped yet", 0, pref.updateDismissedVersionCode)
    }

    @Test
    fun updatePreferences_roundTrip() {
        pref.updateAutoCheck = false
        pref.updateLastCheckAt = 1_700_000_000_000L
        pref.updateDismissedVersionCode = 170

        assertEquals(false, pref.updateAutoCheck)
        assertEquals(1_700_000_000_000L, pref.updateLastCheckAt)
        assertEquals(170, pref.updateDismissedVersionCode)

        // New Pref instance reads the same default SharedPreferences.
        val reloaded = Pref(context)
        assertEquals(false, reloaded.updateAutoCheck)
        assertEquals(1_700_000_000_000L, reloaded.updateLastCheckAt)
        assertEquals(170, reloaded.updateDismissedVersionCode)
    }

    @Test
    fun version_getter_whenNotSet_returnsCurrentTimestamp() {
        val v = pref.version
        assertTrue("Version should not be blank", v.isNotBlank())
        assertTrue("Version should be a valid timestamp", v.toLongOrNull() != null)
    }

    @Test
    fun version_setterAndGetter_persists() {
        pref.version = "test-version-123"
        assertEquals("Version should be persisted", "test-version-123", pref.version)
        assertEquals("test-version-123", sharedPreferences.getString(Pref.VERSION, null))
    }
}
