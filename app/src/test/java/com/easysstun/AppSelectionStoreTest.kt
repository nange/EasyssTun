package com.easysstun

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Tests for the per-mode app selection store, including the migration of the
 * legacy bypass-only key.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class AppSelectionStoreTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var store: AppSelectionStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        sharedPreferences.edit().clear().apply()
        store = AppSelectionStore(Pref(context))
    }

    @After
    fun tearDown() {
        sharedPreferences.edit().clear().apply()
    }

    @Test
    fun selected_whenNothingSaved_isEmpty() {
        assertTrue(store.selected(Pref.PROXY_MODE_BYPASS).isEmpty())
        assertTrue(store.selected(Pref.PROXY_MODE_PROXY_ONLY).isEmpty())
    }

    @Test
    fun selectionIsPerMode() {
        val bypass = setOf("com.example.a", "com.example.b")
        val proxyOnly = setOf("com.example.c")

        store.setSelected(Pref.PROXY_MODE_BYPASS, bypass)
        store.setSelected(Pref.PROXY_MODE_PROXY_ONLY, proxyOnly)

        assertEquals(bypass, store.selected(Pref.PROXY_MODE_BYPASS))
        assertEquals(proxyOnly, store.selected(Pref.PROXY_MODE_PROXY_ONLY))
        assertEquals(
            bypass,
            sharedPreferences.getStringSet(AppSelectionStore.SELECTED_APPS_BYPASS, null),
        )
        assertEquals(
            proxyOnly,
            sharedPreferences.getStringSet(AppSelectionStore.SELECTED_APPS_PROXY_ONLY, null),
        )
    }

    @Test
    fun setSelected_withEmptySet_storesAnEmptySelection() {
        store.setSelected(Pref.PROXY_MODE_BYPASS, setOf("com.example.a"))
        store.setSelected(Pref.PROXY_MODE_BYPASS, emptySet())

        assertTrue(store.selected(Pref.PROXY_MODE_BYPASS).isEmpty())
    }

    @Test
    fun selected_migratesTheLegacyBypassKeyOnce() {
        val legacy = setOf("com.example.legacy")
        sharedPreferences.edit()
            .putStringSet(AppSelectionStore.SELECTED_APPS, legacy)
            .apply()

        assertEquals(
            "a selection written before proxy-only mode existed belongs to bypass mode",
            legacy,
            store.selected(Pref.PROXY_MODE_BYPASS),
        )
        assertEquals(
            legacy,
            sharedPreferences.getStringSet(AppSelectionStore.SELECTED_APPS_BYPASS, null),
        )
        assertNull(
            "the legacy key is removed after the migration",
            sharedPreferences.getStringSet(AppSelectionStore.SELECTED_APPS, null),
        )

        // Reading again must not re-run the migration or lose the data.
        assertEquals(legacy, store.selected(Pref.PROXY_MODE_BYPASS))
    }

    @Test
    fun selected_doesNotMigrateTheLegacyKeyIntoProxyOnlyMode() {
        sharedPreferences.edit()
            .putStringSet(AppSelectionStore.SELECTED_APPS, setOf("com.example.legacy"))
            .apply()

        assertTrue(store.selected(Pref.PROXY_MODE_PROXY_ONLY).isEmpty())
    }

    @Test
    fun selected_legacyEmptySet_isIgnored() {
        sharedPreferences.edit()
            .putStringSet(AppSelectionStore.SELECTED_APPS, emptySet())
            .apply()

        assertTrue(store.selected(Pref.PROXY_MODE_BYPASS).isEmpty())
        assertNull(
            "an empty legacy set has nothing to migrate",
            sharedPreferences.getStringSet(AppSelectionStore.SELECTED_APPS_BYPASS, null),
        )
    }
}
