package com.musan.easysstun

import android.content.Context
import android.content.SharedPreferences
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.*
import org.mockito.junit.MockitoJUnitRunner
import org.junit.Assert.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

@RunWith(MockitoJUnitRunner::class)
class PrefTest {

    @Mock
    private lateinit var mockContext: Context

    @Mock
    private lateinit var mockPrefs: SharedPreferences

    @Mock
    private lateinit var mockEditor: SharedPreferences.Editor

    private lateinit var pref: Pref

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Before
    fun setUp() {
        `when`(mockContext.getSharedPreferences(anyString(), anyInt())).thenReturn(mockPrefs)
        `when`(mockPrefs.edit()).thenReturn(mockEditor)
        `when`(mockEditor.putString(anyString(), anyString())).thenReturn(mockEditor)
        `when`(mockEditor.remove(anyString())).thenReturn(mockEditor)
        `when`(mockEditor.putBoolean(anyString(), anyBoolean())).thenReturn(mockEditor)
        // mockEditor.apply() is void, so no need to mock further for it unless verifying calls

        pref = Pref(mockContext)
    }

    // Test scenarios will be implemented here

    @Test
    fun `initial state - getServerProfiles returns empty list`() {
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(null)
        val profiles = pref.getServerProfiles()
        assertTrue(profiles.isEmpty())
    }

    @Test
    fun `initial state - getActiveServerProfile returns null`() {
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(null) // No profiles
        `when`(mockPrefs.getString(Pref.ACTIVE_SERVER_ID, null)).thenReturn(null)
        val activeProfile = pref.getActiveServerProfile()
        assertNull(activeProfile)
    }

    @Test
    fun `addServerProfile - add one profile`() {
        val profile = ServerProfile("id1", "name1", "server1", "8080", "pass1")
        // Simulate no profiles initially
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(null)

        pref.addServerProfile(profile)

        val expectedJson = json.encodeToString(listOf(profile))
        verify(mockEditor).putString(Pref.SERVER_PROFILES, expectedJson)
        verify(mockEditor).apply() // Important to verify that changes are saved

        // For verification, simulate the saved state
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(expectedJson)
        val profiles = pref.getServerProfiles()
        assertEquals(1, profiles.size)
        assertEquals(profile, profiles[0])
    }

    @Test
    fun `addServerProfile - add multiple profiles`() {
        val profile1 = ServerProfile("id1", "name1", "server1", "8080", "pass1")
        val profile2 = ServerProfile("id2", "name2", "server2", "8081", "pass2")

        // Simulate no profiles initially
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(null)
        pref.addServerProfile(profile1)

        // Simulate profile1 being saved
        val json1 = json.encodeToString(listOf(profile1))
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(json1)
        pref.addServerProfile(profile2)

        val expectedJson = json.encodeToString(listOf(profile1, profile2))
        verify(mockEditor).putString(Pref.SERVER_PROFILES, expectedJson)
        verify(mockEditor, times(2)).apply() // Apply called for each add

        // For verification, simulate the saved state
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(expectedJson)
        val profiles = pref.getServerProfiles()
        assertEquals(2, profiles.size)
        assertTrue(profiles.contains(profile1))
        assertTrue(profiles.contains(profile2))
    }

    @Test
    fun `setActiveServer - set and get active profile`() {
        val profile1 = ServerProfile("id1", "name1", "server1", "8080", "pass1")
        // Simulate profile1 being the only profile
        val profilesJson = json.encodeToString(listOf(profile1))
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(profilesJson)

        pref.setActiveServer(profile1.id)
        verify(mockEditor).putString(Pref.ACTIVE_SERVER_ID, profile1.id)
        verify(mockEditor).apply()

        // Simulate active ID being saved
        `when`(mockPrefs.getString(Pref.ACTIVE_SERVER_ID, null)).thenReturn(profile1.id)
        val activeProfile = pref.getActiveServerProfile()
        assertNotNull(activeProfile)
        assertEquals(profile1, activeProfile)
    }

    @Test
    fun `setActiveServer - set active to non-existent ID`() {
        val profile1 = ServerProfile("id1", "name1", "server1", "8080", "pass1")
        // Simulate profile1 being the only profile
        val profilesJson = json.encodeToString(listOf(profile1))
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(profilesJson)
        // Active server is initially null or some other ID
        `when`(mockPrefs.getString(Pref.ACTIVE_SERVER_ID, null)).thenReturn(null)


        pref.setActiveServer("nonExistentId")
        verify(mockEditor).putString(Pref.ACTIVE_SERVER_ID, "nonExistentId")
        verify(mockEditor).apply()

        // Simulate nonExistentId being saved as active
        `when`(mockPrefs.getString(Pref.ACTIVE_SERVER_ID, null)).thenReturn("nonExistentId")
        val activeProfile = pref.getActiveServerProfile()
        assertNull(activeProfile) // Because "nonExistentId" does not match any profile in the list
    }

    @Test
    fun `updateServerProfile - update existing profile`() {
        val originalProfile = ServerProfile("id1", "name1", "server1", "8080", "pass1")
        val updatedProfile = originalProfile.copy(name = "newName", serverPort = "8081")

        // Simulate originalProfile being saved
        val originalJson = json.encodeToString(listOf(originalProfile))
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(originalJson)

        pref.updateServerProfile(updatedProfile)

        val expectedJson = json.encodeToString(listOf(updatedProfile))
        verify(mockEditor).putString(Pref.SERVER_PROFILES, expectedJson)
        verify(mockEditor).apply()

        // For verification, simulate the updated state
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(expectedJson)
        val profiles = pref.getServerProfiles()
        assertEquals(1, profiles.size)
        assertEquals(updatedProfile, profiles[0])
    }

    @Test
    fun `updateServerProfile - attempt to update non-existent profile`() {
        val existingProfile = ServerProfile("id1", "name1", "server1", "8080", "pass1")
        val profileToUpdate = ServerProfile("nonExistentId", "name2", "server2", "8081", "pass2")

        // Simulate existingProfile being saved
        val existingJson = json.encodeToString(listOf(existingProfile))
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(existingJson)

        pref.updateServerProfile(profileToUpdate) // This profile's ID is not in the list

        // Verify that putString was NOT called with a list containing profileToUpdate if it's not found
        // (Current implementation of updateServerProfile only saves if index != -1)
        verify(mockEditor, never()).putString(Pref.SERVER_PROFILES, json.encodeToString(listOf(existingProfile, profileToUpdate)))
        verify(mockEditor, never()).putString(Pref.SERVER_PROFILES, json.encodeToString(listOf(profileToUpdate)))
        // Verify that apply was not called if no change was made
        verify(mockEditor, never()).apply()


        // For verification, ensure the list remains unchanged
        val profiles = pref.getServerProfiles() // Should still return existingJson
        assertEquals(1, profiles.size)
        assertEquals(existingProfile, profiles[0])
    }

    @Test
    fun `deleteServerProfile - delete existing profile`() {
        val profile1 = ServerProfile("id1", "name1", "server1", "8080", "pass1")
        val profile2 = ServerProfile("id2", "name2", "server2", "8081", "pass2")

        // Simulate profiles being saved
        val initialJson = json.encodeToString(listOf(profile1, profile2))
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(initialJson)

        pref.deleteServerProfile(profile1.id)

        val expectedJson = json.encodeToString(listOf(profile2))
        verify(mockEditor).putString(Pref.SERVER_PROFILES, expectedJson)
        verify(mockEditor).apply()

        // For verification, simulate the updated state
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(expectedJson)
        val profiles = pref.getServerProfiles()
        assertEquals(1, profiles.size)
        assertEquals(profile2, profiles[0])
    }

    @Test
    fun `deleteServerProfile - delete active profile`() {
        val profile1 = ServerProfile("id1", "name1", "server1", "8080", "pass1")
        val profile2 = ServerProfile("id2", "name2", "server2", "8081", "pass2")

        // Simulate profiles being saved and profile1 being active
        val initialJson = json.encodeToString(listOf(profile1, profile2))
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(initialJson)
        `when`(mockPrefs.getString(Pref.ACTIVE_SERVER_ID, null)).thenReturn(profile1.id)

        pref.deleteServerProfile(profile1.id)

        val expectedJson = json.encodeToString(listOf(profile2))
        verify(mockEditor).putString(Pref.SERVER_PROFILES, expectedJson)
        verify(mockEditor).remove(Pref.ACTIVE_SERVER_ID) // Verify active ID is removed
        verify(mockEditor, times(2)).apply() // Apply for profiles string and remove active_id

        // For verification
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(expectedJson)
        `when`(mockPrefs.getString(Pref.ACTIVE_SERVER_ID, null)).thenReturn(null) // Simulate active ID cleared

        val profiles = pref.getServerProfiles()
        assertEquals(1, profiles.size)
        assertEquals(profile2, profiles[0])
        assertNull(pref.getActiveServerProfile())
    }

    @Test
    fun `deleteServerProfile - attempt to delete non-existent profile`() {
        val profile1 = ServerProfile("id1", "name1", "server1", "8080", "pass1")

        // Simulate profile1 being saved
        val initialJson = json.encodeToString(listOf(profile1))
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(initialJson)

        pref.deleteServerProfile("nonExistentId")

        // Verify that putString was NOT called again with the same list if no change
        // (Current implementation removes all matching, so if none match, list is same, but still saves)
        // We should verify it was called with the original list, or that the list remains the same.
        verify(mockEditor).putString(Pref.SERVER_PROFILES, initialJson)
        verify(mockEditor).apply() // Apply is called even if list content is identical after removal attempt


        // For verification, ensure the list remains unchanged
        val profiles = pref.getServerProfiles()
        assertEquals(1, profiles.size)
        assertEquals(profile1, profiles[0])
    }

    @Test
    fun `getEasyssInfo - no active server`() {
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(null) // No profiles
        `when`(mockPrefs.getString(Pref.ACTIVE_SERVER_ID, null)).thenReturn(null) // No active server

        val info = pref.getEasyssInfo()
        assertFalse(info.valid)
        assertTrue(info.cmdList.isEmpty())
    }

    @Test
    fun `getEasyssInfo - with active server`() {
        val activeProfile = ServerProfile(
            id = "activeId", name = "activeName", server = "active.server.com", serverPort = "1234",
            password = "activePassword", encryption = "aes-256-gcm", proxyRule = "bypass_lan",
            outbound = "direct", logLevel = "debug", disableQuic = "true", ipv6Rule = "prefer_ipv6",
            serverNameIndication = "active.sni.com", customCa = "ACTIVE_CA_CONTENT"
        )
        val profilesJson = json.encodeToString(listOf(activeProfile))
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(profilesJson)
        `when`(mockPrefs.getString(Pref.ACTIVE_SERVER_ID, null)).thenReturn(activeProfile.id)
        `when`(mockContext.cacheDir).thenReturn(java.io.File(".")) // Mock cacheDir for custom CA file

        val info = pref.getEasyssInfo()

        assertTrue(info.valid)
        assertEquals("${activeProfile.server}:${activeProfile.serverPort}", info.info)
        assertFalse(info.cmdList.isEmpty())

        // Check some key parameters in cmdList
        assertTrue(info.cmdList.contains("-s"))
        assertTrue(info.cmdList.contains(activeProfile.server))
        assertTrue(info.cmdList.contains("-p"))
        assertTrue(info.cmdList.contains(activeProfile.serverPort))
        assertTrue(info.cmdList.contains("-k"))
        assertTrue(info.cmdList.contains(activeProfile.password))
        assertTrue(info.cmdList.contains("-m"))
        assertTrue(info.cmdList.contains(activeProfile.encryption))
        assertTrue(info.cmdList.contains("-proxy-rule"))
        assertTrue(info.cmdList.contains(activeProfile.proxyRule))
        assertTrue(info.cmdList.contains("-outbound-proto"))
        assertTrue(info.cmdList.contains(activeProfile.outbound))
        assertTrue(info.cmdList.contains("-log-level"))
        assertTrue(info.cmdList.contains(activeProfile.logLevel))
        assertTrue(info.cmdList.contains("-disable-quic=true"))
        assertTrue(info.cmdList.contains("-ipv6-rule"))
        assertTrue(info.cmdList.contains(activeProfile.ipv6Rule))
        assertTrue(info.cmdList.contains("-sn"))
        assertTrue(info.cmdList.contains(activeProfile.serverNameIndication))
        assertTrue(info.cmdList.contains("-ca-path"))
        // Filename is "easyss_custom_ca.conf" in cacheDir, so path will contain it
        assertTrue(info.cmdList.any { it.contains("easyss_custom_ca.conf") })

        // Clean up the dummy file if created - though mocking should prevent actual file creation
        // java.io.File(mockContext.cacheDir, "easyss_custom_ca.conf").delete()
    }
     @Test
    fun `getEasyssInfo - active server with blank SNI`() {
        val activeProfile = ServerProfile(
            id = "activeId", name = "activeName", server = "active.server.com", serverPort = "1234",
            password = "activePassword", serverNameIndication = "" // Blank SNI
        )
        val profilesJson = json.encodeToString(listOf(activeProfile))
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(profilesJson)
        `when`(mockPrefs.getString(Pref.ACTIVE_SERVER_ID, null)).thenReturn(activeProfile.id)
        `when`(mockContext.cacheDir).thenReturn(java.io.File("."))

        val info = pref.getEasyssInfo()

        assertTrue(info.valid)
        // SNI should fall back to server address
        assertTrue(info.cmdList.contains("-sn"))
        assertTrue(info.cmdList.contains(activeProfile.server))
    }

    @Test
    fun `migration - migrates old config when no new profiles exist`() {
        // Simulate old config
        `when`(mockPrefs.contains("easyss_server")).thenReturn(true)
        `when`(mockPrefs.getString("easyss_server", null)).thenReturn("old.server.com")
        `when`(mockPrefs.getString("easyss_serverport", "")).thenReturn("1234")
        `when`(mockPrefs.getString("easyss_password", "")).thenReturn("oldPass")
        `when`(mockPrefs.getString("easyss_encryption", "chacha20-poly1305")).thenReturn("aes-128-gcm")
        // ... mock other old preferences as needed, or assume defaults if not explicitly mocked

        // Simulate no existing server profiles
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(null)


        // Re-initialize Pref to trigger init block with migration
        pref = Pref(mockContext) // This calls migrateOldConfig()

        // Verify a new profile was created and saved
        verify(mockEditor).putString(eq(Pref.SERVER_PROFILES), anyString())
        // Verify active server was set (it will be a UUID, so match anyString())
        verify(mockEditor).putString(eq(Pref.ACTIVE_SERVER_ID), anyString())

        // Verify old keys were removed
        verify(mockEditor).remove("easyss_server")
        verify(mockEditor).remove("easyss_serverport")
        verify(mockEditor).remove("easyss_password")
        verify(mockEditor).remove("easyss_encryption")
        // ... verify removal of other old keys

        verify(mockEditor, atLeastOnce()).apply()


        // Setup mocks for getServerProfiles and getActiveServerProfile to read the migrated data
        // This requires capturing the arguments passed to putString or making broad assumptions
        // For simplicity, we'll assume the migration logic inside Pref.kt correctly uses addServerProfile and setActiveServer
        // and those methods correctly interact with the mocked editor.

        // To actually test the *result* of migration, we need to capture the generated profile
        // This is tricky with current setup. A more direct test would be to call migrateOldConfig() and
        // then use the mocked SharedPreferences to see what was written.

        // Let's refine the verification for what was written.
        // We expect one profile to be added.
        val captor = argumentCaptor<String>()
        verify(mockEditor).putString(eq(Pref.SERVER_PROFILES), captor.capture())
        val savedProfilesJson = captor.firstValue
        val savedProfiles = json.decodeFromString<List<ServerProfile>>(savedProfilesJson)
        assertEquals(1, savedProfiles.size)
        assertEquals("old.server.com", savedProfiles[0].server)
        assertEquals("1234", savedProfiles[0].serverPort)
        assertEquals("oldPass", savedProfiles[0].password)
        assertEquals("aes-128-gcm", savedProfiles[0].encryption)

        // Verify active ID was set to the ID of the migrated profile
        verify(mockEditor).putString(Pref.ACTIVE_SERVER_ID, savedProfiles[0].id)
    }

    @Test
    fun `migration - does not migrate if new profiles already exist`() {
        // Simulate old config present
        `when`(mockPrefs.contains("easyss_server")).thenReturn(true)
        `when`(mockPrefs.getString("easyss_server", null)).thenReturn("old.server.com")

        // Simulate new profiles already exist
        val existingProfile = ServerProfile("idExisting", "existing", "e.server", "80", "ePass")
        val existingProfilesJson = json.encodeToString(listOf(existingProfile))
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(existingProfilesJson)

        // Re-initialize Pref
        pref = Pref(mockContext)

        // Verify that no attempt was made to save new profiles (meaning migration didn't run addServerProfile)
        // We check that putString for SERVER_PROFILES was not called as part of migration.
        // Since getServerProfiles itself might be called during init (it is),
        // we need to be careful. Migration adds a *new* profile.
        // The key is that old keys are not removed and no *new* profile based on old keys is added.
        verify(mockEditor, never()).remove("easyss_server")
        // Verify that addServerProfile was not called with a profile derived from old settings
        // This is hard to verify directly without deeper mocking or argument capturing of addServerProfile itself.
        // A simpler check: if migration happened, it would call putString for SERVER_PROFILES.
        // If it didn't, putString for SERVER_PROFILES would only be called if other methods are invoked.
        // Since Pref constructor triggers migration, if it *doesn't* migrate, it shouldn't call putString to save a migrated profile.
        // This test needs careful thought on verification.
        // The easiest is to check that old keys are NOT removed.
        verify(mockEditor, never()).remove("easyss_serverport") // if one is not removed, others likely too.

        // And no new profile was added *from migration*
        // (the existing one is still there - this is not testing that, but that no *additional* one was added)
        // This is also tricky. If `getServerProfiles` is called in `init` after `migrateOldConfig`, it will return `existingProfilesJson`.
        // The core idea is that `migrateOldConfig` should effectively do nothing in this case.
    }

     @Test
    fun `migration - does not migrate if old server config is missing or blank`() {
        // Case 1: "easyss_server" key does not exist
        `when`(mockPrefs.contains("easyss_server")).thenReturn(false)
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(null) // No new profiles

        pref = Pref(mockContext) // Re-initialize

        verify(mockEditor, never()).putString(eq(Pref.SERVER_PROFILES), anyString()) // No profile should be added
        verify(mockEditor, never()).remove(anyString()) // No old keys should be removed

        // Reset mocks for next part of test or use a new test method
        reset(mockEditor) // Reset interactions on editor for a clean verification slate
        `when`(mockPrefs.edit()).thenReturn(mockEditor) // Re-associate editor after reset

        // Case 2: "easyss_server" is blank
        `when`(mockPrefs.contains("easyss_server")).thenReturn(true)
        `when`(mockPrefs.getString("easyss_server", null)).thenReturn("") // Blank server
        `when`(mockPrefs.getString(Pref.SERVER_PROFILES, null)).thenReturn(null) // No new profiles

        pref = Pref(mockContext) // Re-initialize

        verify(mockEditor, never()).putString(eq(Pref.SERVER_PROFILES), anyString())
        verify(mockEditor, never()).remove(anyString())
    }

}

// Helper for argument capturing with Mockito-Kotlin
inline fun <reified T> argumentCaptor(): org.mockito.ArgumentCaptor<T> = org.mockito.ArgumentCaptor.forClass(T::class.java)
