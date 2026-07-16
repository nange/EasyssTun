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
    fun getEasyssInfo_noActiveProfile_returnsInvalid() {
        val info = pref.getEasyssInfo()
        assertFalse("easyssInfo.valid should be false when no active profile", info.valid)
        assertTrue("easyssInfo.info should be empty", info.info.isEmpty())
        assertTrue("easyssInfo.cmdList should be empty", info.cmdList.isEmpty())
    }

    @Test
    fun getEasyssInfo_withActiveProfile_returnsValidInfoAndCorrectSocksPort() {
        val profile = createDummyProfile(id = "active_profile_id", name = "Active Profile").copy(
            server = "my.server.org",
            serverPort = "8888",
            serverNameIndication = "my.sni.org", // SNI is different from server
            password = "secret",
            encryption = "aes-256-gcm",
            proxyRule = "bypass_lan",
            outbound = "ipv4_only",
            logLevel = "debug",
            enableQuic = "true",
            ipv6Rule = "ipv6_only"
        )

        pref.addServerProfile(profile)
        pref.setActiveServer(profile.id)

        // Set SOCKS port in preferences
        sharedPreferences.edit().putString("socks_port", "1080").apply()

        val info = pref.getEasyssInfo()

        assertTrue("easyssInfo should be valid", info.valid)
        assertEquals("Info string should be server:port", "my.server.org:8888", info.info)
        
        val expectedCmdList = listOf(
            "-s", "my.server.org",
            "-p", "8888",
            "-k", "secret",
            "-m", "aes-256-gcm",
            "-proxy-rule", "bypass_lan",
            "-outbound-proto", "ipv4_only",
            "-l", "1080", // Verifies SOCKS port from prefs
            "-t", "60",
            "-log-level", "debug",
            "-enable-quic=true",
            "-ipv6-rule", "ipv6_only",
            "-sn", "my.sni.org", // Verifies SNI is used
            "-enable-tun2socks=false",
            "-daemon=false"
        )
        assertEquals("Command list should match expected", expectedCmdList, info.cmdList)
    }

    @Test
    fun getEasyssInfo_withActiveProfile_SNIisBlank_usesServerAsSNI() {
        val profile = createDummyProfile(id = "active_profile_sni_blank").copy(
            server = "actual.server.name",
            serverNameIndication = "" // SNI is blank
        )
        pref.addServerProfile(profile)
        pref.setActiveServer(profile.id)

        val info = pref.getEasyssInfo()
        assertTrue(info.valid)
        val snIndex = info.cmdList.indexOf("-sn")
        assertTrue("cmdList should contain -sn", snIndex != -1 && snIndex + 1 < info.cmdList.size)
        assertEquals("SNI should be actual.server.name when original SNI is blank", "actual.server.name", info.cmdList[snIndex + 1])
    }


    @Test
    fun getEasyssInfo_withActiveProfile_usesDefaultSocksPortIfNotSet() {
        val profile = createDummyProfile(id = "active_profile_id_default_socks")
        pref.addServerProfile(profile)
        pref.setActiveServer(profile.id)

        // SOCKS port NOT set in preferences, should use default "2080"
        sharedPreferences.edit().remove("socks_port").apply()


        val info = pref.getEasyssInfo()
        assertTrue("easyssInfo should be valid", info.valid)
        val localPortIndex = info.cmdList.indexOf("-l")
        assertTrue("cmdList should contain -l parameter", localPortIndex != -1 && localPortIndex + 1 < info.cmdList.size)
        assertEquals("Default SOCKS port should be 2080", "2080", info.cmdList[localPortIndex + 1])
    }

    // ── Additional tests ──────────────────────────────────────────

    @Test
    fun getEasyssInfo_withCustomCa_addsCaPathArg() {
        val profile = createDummyProfile(id = "custom_ca_id").copy(
            customCa = "-----BEGIN CERTIFICATE-----\nFAKE\n-----END CERTIFICATE-----"
        )
        pref.addServerProfile(profile)
        pref.setActiveServer(profile.id)

        val info = pref.getEasyssInfo()
        assertTrue("easyssInfo should be valid", info.valid)
        assertTrue("cmdList should contain -ca-path", info.cmdList.contains("-ca-path"))
        val caIndex = info.cmdList.indexOf("-ca-path")
        assertTrue("Should have a path after -ca-path", caIndex + 1 < info.cmdList.size)
    }

    @Test
    fun getEasyssInfo_withDirectFile_addsDirectFileArg() {
        val profile = createDummyProfile(id = "direct_file_id").copy(
            directFile = "example.com\ngoogle.com"
        )
        pref.addServerProfile(profile)
        pref.setActiveServer(profile.id)

        val info = pref.getEasyssInfo()
        assertTrue("easyssInfo should be valid", info.valid)
        assertTrue("cmdList should contain -direct-file", info.cmdList.contains("-direct-file"))
        val dfIndex = info.cmdList.indexOf("-direct-file")
        assertTrue("Should have a path after -direct-file", dfIndex + 1 < info.cmdList.size)
    }

    @Test
    fun getEasyssInfo_withProxyFile_addsProxyFileArg() {
        val profile = createDummyProfile(id = "proxy_file_id").copy(
            proxyFile = "internal.corp\nvpn.domain"
        )
        pref.addServerProfile(profile)
        pref.setActiveServer(profile.id)

        val info = pref.getEasyssInfo()
        assertTrue("easyssInfo should be valid", info.valid)
        assertTrue("cmdList should contain -proxy-file", info.cmdList.contains("-proxy-file"))
        val pfIndex = info.cmdList.indexOf("-proxy-file")
        assertTrue("Should have a path after -proxy-file", pfIndex + 1 < info.cmdList.size)
    }

    @Test
    fun getEasyssInfo_withAllOptionalFiles_addsAllArgs() {
        val profile = createDummyProfile(id = "all_files_id").copy(
            customCa = "fake-ca",
            directFile = "fake-direct",
            proxyFile = "fake-proxy"
        )
        pref.addServerProfile(profile)
        pref.setActiveServer(profile.id)

        val info = pref.getEasyssInfo()
        assertTrue(info.valid)
        assertTrue("Should have -ca-path", info.cmdList.contains("-ca-path"))
        assertTrue("Should have -direct-file", info.cmdList.contains("-direct-file"))
        assertTrue("Should have -proxy-file", info.cmdList.contains("-proxy-file"))
    }

    @Test
    fun getEasyssInfo_withBlankOptionalFiles_doesNotAddFileArgs() {
        val profile = createDummyProfile(id = "no_files_id").copy(
            customCa = "",
            directFile = "",
            proxyFile = ""
        )
        pref.addServerProfile(profile)
        pref.setActiveServer(profile.id)

        val info = pref.getEasyssInfo()
        assertTrue(info.valid)
        assertFalse("Should NOT have -ca-path for blank customCa", info.cmdList.contains("-ca-path"))
        assertFalse("Should NOT have -direct-file for blank directFile", info.cmdList.contains("-direct-file"))
        assertFalse("Should NOT have -proxy-file for blank proxyFile", info.cmdList.contains("-proxy-file"))
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
