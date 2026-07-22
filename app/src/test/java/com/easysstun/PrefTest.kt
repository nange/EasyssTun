package com.easysstun

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

// Use Robolectric to allow PreferenceManager.getDefaultSharedPreferences to work in unit tests
@RunWith(AndroidJUnit4::class)
@Config(manifest=Config.NONE) // We don't need a manifest for these unit tests
class PrefTest {

    private lateinit var context: Context
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var pref: Pref // The actual Pref class

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        // Use the same default SharedPreferences that Pref uses internally
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        sharedPreferences.edit().clear().apply() // Clear before each test

        // Initialize the real Pref object, which will use the SharedPreferences provided by Robolectric's context
        pref = Pref(context)
    }

    @After
    fun tearDown() {
        // Clear SharedPreferences after each test to ensure test isolation
        sharedPreferences.edit().clear().apply()
    }

    private fun createDummyProfile(id: String = UUID.randomUUID().toString(), name: String = "Test Profile"): ServerProfile {
        return ServerProfile(
            id = id,
            name = name,
            server = "test.server.com",
            serverPort = "1234",
            password = "password",
            encryption = "chacha20-poly1305",
            proxyRule = "auto",
            outbound = "native",
            logLevel = "info",
            enableQuic = "false",
            ipv6Rule = "auto",
            serverNameIndication = "test.sni.com",
            customCa = ""
        )
    }

    @Test
    fun addAndGetServerProfiles() {
        val profile1 = createDummyProfile(id = "id1", name = "Profile 1")
        val profile2 = createDummyProfile(id = "id2", name = "Profile 2")

        assertTrue("Initially, profiles should be empty", pref.getServerProfiles().isEmpty())

        pref.addServerProfile(profile1)
        var profiles = pref.getServerProfiles()
        assertEquals("After adding one profile, size should be 1", 1, profiles.size)
        assertEquals("The retrieved profile should match the added one", profile1, profiles[0])

        pref.addServerProfile(profile2)
        profiles = pref.getServerProfiles()
        assertEquals("After adding a second profile, size should be 2", 2, profiles.size)
        assertTrue("Profiles list should contain profile1", profiles.contains(profile1))
        assertTrue("Profiles list should contain profile2", profiles.contains(profile2))
    }

    @Test
    fun updateServerProfile() {
        val profileId = "id_to_update"
        val originalProfile = createDummyProfile(id = profileId, name = "Original Name")
        pref.addServerProfile(originalProfile)

        val updatedProfile = originalProfile.copy(name = "Updated Name", server = "new.server.com")
        pref.updateServerProfile(updatedProfile)

        val profiles = pref.getServerProfiles()
        assertEquals("Profile list size should remain 1", 1, profiles.size)
        assertEquals("The updated profile should be retrieved", updatedProfile, profiles[0])
        assertEquals("Profile name should be updated", "Updated Name", profiles[0].name)
        assertEquals("Profile server should be updated", "new.server.com", profiles[0].server)
    }

    @Test
    fun deleteServerProfile() {
        val profile1 = createDummyProfile("id1")
        val profile2 = createDummyProfile("id2")
        pref.addServerProfile(profile1)
        pref.addServerProfile(profile2)

        pref.deleteServerProfile("id1")
        var profiles = pref.getServerProfiles()
        assertEquals("After deleting one profile, size should be 1", 1, profiles.size)
        assertEquals("The remaining profile should be profile2", profile2, profiles[0])

        pref.deleteServerProfile("id2")
        profiles = pref.getServerProfiles()
        assertTrue("After deleting all profiles, list should be empty", profiles.isEmpty())
    }

    @Test
    fun deleteActiveServerProfile_clearsActiveId() {
        val activeProfile = createDummyProfile("active_id")
        pref.addServerProfile(activeProfile)
        pref.setActiveServer(activeProfile.id)
        assertEquals("Active server ID should be set in SharedPreferences", activeProfile.id, sharedPreferences.getString(Pref.ACTIVE_SERVER_ID, null))

        pref.deleteServerProfile(activeProfile.id)
        assertNull("Active server ID should be cleared from SharedPreferences after deletion", sharedPreferences.getString(Pref.ACTIVE_SERVER_ID, null))
        assertTrue("Server profiles list should be empty", pref.getServerProfiles().isEmpty())
    }
    
    @Test
    fun setActiveAndGetActiveServerProfile() {
        assertNull("Initially, active profile should be null", pref.getActiveServerProfile())

        val profile1 = createDummyProfile("id1")
        val profile2 = createDummyProfile("id2")
        pref.addServerProfile(profile1)
        pref.addServerProfile(profile2)

        pref.setActiveServer("id1")
        var active = pref.getActiveServerProfile()
        assertNotNull("Active profile should not be null after setting", active)
        assertEquals("Active profile should be profile1", profile1, active)

        pref.setActiveServer("id2")
        active = pref.getActiveServerProfile()
        assertNotNull("Active profile should not be null after setting to profile2", active)
        assertEquals("Active profile should be profile2", profile2, active)
    }

    @Test
    fun getActiveServerProfile_whenNoneSet_returnsNull() {
        val profile1 = createDummyProfile("id1")
        pref.addServerProfile(profile1)
        // No active server set yet
        assertNull("Active profile should be null when none is explicitly set", pref.getActiveServerProfile())
    }

    @Test
    fun setAndGetIsServiceEnabled() {
        assertFalse("Initially, isServiceEnabled should be false", pref.isServiceEnabled)
        assertFalse("SharedPreferences should reflect false initially", sharedPreferences.getBoolean(Pref.SERVICE_ENABLED, false))


        pref.isServiceEnabled = true
        assertTrue("isServiceEnabled should be true after setting to true", pref.isServiceEnabled)
        assertTrue("SharedPreferences should reflect true", sharedPreferences.getBoolean(Pref.SERVICE_ENABLED, false))


        pref.isServiceEnabled = false
        assertFalse("isServiceEnabled should be false after setting to false", pref.isServiceEnabled)
        assertFalse("SharedPreferences should reflect false", sharedPreferences.getBoolean(Pref.SERVICE_ENABLED, true))
    }

    @Test
    fun getServerProfiles_corruptedJson_returnsEmptyList() {
        // Write invalid JSON directly to SharedPreferences
        sharedPreferences.edit().putString(Pref.SERVER_PROFILES, "NOT VALID JSON {{{").apply()

        // Re-initialize Pref to pick up the corrupted JSON
        pref = Pref(context)

        val profiles = pref.getServerProfiles()
        assertTrue("Corrupted JSON should result in empty list", profiles.isEmpty())
    }

    @Test
    fun updateServerProfile_nonexistentId_doesNothing() {
        val profile = createDummyProfile(id = "existing")
        pref.addServerProfile(profile)

        val nonexistentProfile = createDummyProfile(id = "nonexistent", name = "Ghost")
        pref.updateServerProfile(nonexistentProfile)

        val profiles = pref.getServerProfiles()
        assertEquals("Should still have 1 profile", 1, profiles.size)
        assertEquals("Existing profile unchanged", profile, profiles[0])
    }

    @Test
    fun getApps_returnsSavedSelection() {
        val testApps = setOf("com.example.app1", "com.example.app2")
        sharedPreferences.edit().putStringSet("selected_apps", testApps).apply()

        // Re-init Pref to pick up the apps
        pref = Pref(context)
        val saved = pref.getApps()
        assertEquals("Should return the saved app set", testApps, saved)
    }

    @Test
    fun getApps_whenNoneSaved_returnsEmptySet() {
        val apps = pref.getApps()
        assertTrue("Should return empty set when nothing saved", apps.isEmpty())
    }

    @Test
    fun version_getter_whenNotSet_returnsCurrentTimestamp() {
        // Version should fall back to current timestamp
        val v = pref.version
        assertTrue("Version should not be blank", v.isNotBlank())
        // Should be a parsable Long
        assertTrue("Version should be a valid timestamp", v.toLongOrNull() != null)
    }

    @Test
    fun version_setterAndGetter_persists() {
        pref.version = "test-version-123"
        assertEquals("Version should be persisted", "test-version-123", pref.version)

        // Verify in SharedPreferences directly
        assertEquals("test-version-123", sharedPreferences.getString(Pref.VERSION, null))
    }

    @Test
    fun setActiveServer_multipleSwitches_updatesCorrectly() {
        val p1 = createDummyProfile("s1", "Server 1")
        val p2 = createDummyProfile("s2", "Server 2")
        val p3 = createDummyProfile("s3", "Server 3")
        pref.addServerProfile(p1)
        pref.addServerProfile(p2)
        pref.addServerProfile(p3)

        pref.setActiveServer("s1")
        assertEquals("p1", p1, pref.getActiveServerProfile())

        pref.setActiveServer("s3")
        assertEquals("p3", p3, pref.getActiveServerProfile())

        pref.setActiveServer("s2")
        assertEquals("p2", p2, pref.getActiveServerProfile())
    }

    @Test
    fun addServerProfile_withDuplicateId_appendsBoth() {
        val original = createDummyProfile(id = "dup", name = "Original")
        pref.addServerProfile(original)

        val duplicate = createDummyProfile(id = "dup", name = "Duplicate").copy(server = "new.server")
        pref.addServerProfile(duplicate)

        val profiles = pref.getServerProfiles()
        assertEquals("Duplicate IDs are appended (not deduplicated)", 2, profiles.size)
        // Actually, addServerProfile just appends to list - duplicates are possible.
        // This test verifies that behavior.
        assertTrue("Original still present", profiles.contains(original))
        assertTrue("Duplicate also present", profiles.contains(duplicate))
    }
}
