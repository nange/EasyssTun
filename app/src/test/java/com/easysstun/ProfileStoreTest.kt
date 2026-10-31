package com.easysstun

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.UUID

/**
 * Tests for the server profile repository: CRUD, the active-profile invariant
 * and the change event observers use to reload the list.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class ProfileStoreTest {

    private lateinit var context: Context
    private lateinit var pref: Pref
    private lateinit var store: ProfileStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        ProfileStore.resetForTests()
        pref = Pref(context)
        store = ProfileStore.get(context)
    }

    @After
    fun tearDown() {
        PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit()
        ProfileStore.resetForTests()
    }

    private fun profile(
        id: String = UUID.randomUUID().toString(),
        name: String = "Test Profile",
    ): Profile = Profile(
        id = id,
        name = name,
        server = "test.server.com",
        serverPort = 1234,
        password = "password",
        encryption = "chacha20-poly1305",
        proxyRule = "auto",
        outbound = "native",
        logLevel = "info",
        enableQuic = false,
        ipv6Rule = "auto",
        serverNameIndication = "test.sni.com",
        customCa = "",
    )

    @Test
    fun get_isProcessWide() {
        assertSame(ProfileStore.get(context), store)
    }

    @Test
    fun addAndAll() {
        val first = profile(id = "id1", name = "Profile 1")
        val second = profile(id = "id2", name = "Profile 2")

        assertTrue("Initially, profiles should be empty", store.all().isEmpty())

        store.add(first)
        assertEquals(listOf(first), store.all())

        store.add(second)
        assertEquals(listOf(first, second), store.all())
        assertEquals("find() resolves by id", second, store.find("id2"))
        assertNull(store.find("missing"))
    }

    @Test
    fun update_replacesTheMatchingEntry() {
        val original = profile(id = "id_to_update", name = "Original Name")
        store.add(original)

        val updated = original.copy(name = "Updated Name", server = "new.server.com")
        store.update(updated)

        assertEquals(listOf(updated), store.all())
    }

    @Test
    fun update_nonexistentId_doesNothing() {
        val existing = profile(id = "existing")
        store.add(existing)

        store.update(profile(id = "nonexistent", name = "Ghost"))

        assertEquals(listOf(existing), store.all())
    }

    @Test
    fun delete_removesTheEntry() {
        val first = profile("id1")
        val second = profile("id2")
        store.add(first)
        store.add(second)

        store.delete("id1")
        assertEquals(listOf(second), store.all())

        store.delete("id2")
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun deleteActiveProfile_clearsThePointerWithTheLastProfile() {
        val active = profile("active_id")
        store.add(active)
        store.select(active.id)
        assertEquals(active.id, pref.getString(ProfileStore.ACTIVE_SERVER_ID))

        store.delete(active.id)

        assertNull(
            "the pointer must be cleared when the last profile is deleted",
            pref.getString(ProfileStore.ACTIVE_SERVER_ID),
        )
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun deleteActiveProfile_fallsBackToTheFirstRemaining() {
        val first = profile("id1")
        val second = profile("id2")
        store.add(first)
        store.add(second)
        store.select("id2")

        store.delete("id2")

        assertEquals("id1", pref.getString(ProfileStore.ACTIVE_SERVER_ID))
        assertEquals(first, store.active())
        assertEquals(listOf(first), store.all())
    }

    @Test
    fun deleteNonActiveProfile_keepsThePointer() {
        val first = profile("id1")
        val second = profile("id2")
        store.add(first)
        store.add(second)
        store.select("id1")

        store.delete("id2")

        assertEquals("id1", pref.getString(ProfileStore.ACTIVE_SERVER_ID))
        assertEquals(first, store.active())
    }

    @Test
    fun select_switchesTheActiveProfile() {
        val first = profile("s1", "Server 1")
        val second = profile("s2", "Server 2")
        val third = profile("s3", "Server 3")
        store.add(first)
        store.add(second)
        store.add(third)

        store.select("s1")
        assertEquals(first, store.active())

        store.select("s3")
        assertEquals(third, store.active())

        store.select("s2")
        assertEquals(second, store.active())
    }

    @Test
    fun select_unknownId_isIgnoredAndKeepsThePointerValid() {
        val first = profile("id1")
        store.add(first)
        store.select("id1")

        store.select("does-not-exist")

        assertEquals("id1", store.activeId())
        assertEquals(first, store.active())
    }

    @Test
    fun active_whenNothingSelected_returnsNull() {
        store.add(profile("id1"))

        assertNull("no pointer yet", store.activeId())
        assertNull("active() must not guess a profile", store.active())
    }

    @Test
    fun all_withCorruptedJson_returnsEmptyList() {
        pref.putString(ProfileStore.SERVER_PROFILES, "NOT VALID JSON {{{")

        assertTrue(store.all().isEmpty())
        assertNull(store.active())
    }

    @Test
    fun all_readsProfilesWrittenByLegacyBuilds() {
        // Exact shape written by builds before the typed model: every numeric
        // field is a string and the QUIC flag is "true"/"false".
        pref.putString(
            ProfileStore.SERVER_PROFILES,
            """
            [{
                "id": "legacy",
                "name": "Legacy",
                "server": "legacy.example.com",
                "serverPort": "443",
                "password": "pw",
                "enableQuic": "true",
                "socksPort": "4080",
                "timeout": "45"
            }]
            """.trimIndent(),
        )

        val profile = store.all().single()

        assertEquals(443, profile.serverPort)
        assertEquals(4080, profile.socksPort)
        assertEquals(45, profile.timeout)
        assertTrue(profile.enableQuic)
    }

    @Test
    fun add_withDuplicateId_appendsBoth() {
        val original = profile(id = "dup", name = "Original")
        store.add(original)
        val duplicate = profile(id = "dup", name = "Duplicate").copy(server = "new.server")
        store.add(duplicate)

        assertEquals("duplicate ids are appended (not deduplicated)", 2, store.all().size)
    }

    @Test
    fun changes_areEmittedForEveryMutation() {
        val start = store.changes.value

        val added = profile("id1")
        store.add(added)
        assertEquals(start + 1, store.changes.value)

        store.update(added.copy(name = "Renamed"))
        assertEquals(start + 2, store.changes.value)

        store.select("id1")
        assertEquals(start + 3, store.changes.value)

        store.delete("id1")
        assertEquals(start + 4, store.changes.value)
    }

    @Test
    fun mutationsArePersistedForANewStoreInstance() {
        val added = profile("id1")
        store.add(added)
        store.select("id1")

        // A fresh process would build a new store over the same prefs file.
        ProfileStore.resetForTests()
        val reloaded = ProfileStore.get(context)

        assertEquals(listOf(added), reloaded.all())
        assertEquals(added, reloaded.active())
    }
}
