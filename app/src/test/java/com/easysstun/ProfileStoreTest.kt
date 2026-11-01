package com.easysstun

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
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

    /** Recreates the process-wide store, as a fresh process start would. */
    private fun recreateStore(): ProfileStore {
        ProfileStore.resetForTests()
        return ProfileStore.get(context).also { store = it }
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
        val legacyJson = """
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
        """.trimIndent()
        pref.putString(ProfileStore.LEGACY_SERVER_PROFILES, legacyJson)
        recreateStore()

        val profile = store.all().single()

        assertEquals(443, profile.serverPort)
        assertEquals(4080, profile.socksPort)
        assertEquals(45, profile.timeout)
        assertTrue(profile.enableQuic)
    }

    @Test
    fun all_migratesTheLegacyStoreOnceAndKeepsItForDowngrades() {
        val legacyJson = """[{"id":"legacy","name":"Legacy","server":"s","serverPort":"443","password":"pw"}]"""
        pref.putString(ProfileStore.LEGACY_SERVER_PROFILES, legacyJson)

        recreateStore()

        assertEquals(
            "the legacy payload is copied to the current key when the store is created",
            legacyJson,
            pref.getString(ProfileStore.SERVER_PROFILES),
        )
        assertEquals(
            "the legacy key is kept so a downgraded build still reads its profiles",
            legacyJson,
            pref.getString(ProfileStore.LEGACY_SERVER_PROFILES),
        )
    }

    @Test
    fun all_isAPureReadAfterTheInitialMigration() {
        pref.putString(
            ProfileStore.LEGACY_SERVER_PROFILES,
            """[{"id":"legacy","name":"Legacy","server":"s","serverPort":"443","password":"pw"}]""",
        )
        recreateStore()
        // Drop the migrated copy: a read must not migrate (write) again.
        pref.remove(ProfileStore.SERVER_PROFILES)

        assertTrue(store.all().isEmpty())
        assertNull(
            "all() must not write the store",
            pref.getString(ProfileStore.SERVER_PROFILES),
        )
    }

    @Test
    fun all_prefersTheCurrentKeyOverTheLegacyOne() {
        pref.putString(
            ProfileStore.LEGACY_SERVER_PROFILES,
            """[{"id":"old","name":"Old","server":"s","serverPort":"443","password":"pw"}]""",
        )
        pref.putString(
            ProfileStore.SERVER_PROFILES,
            """[{"id":"new","name":"New","server":"s","serverPort":443,"password":"pw"}]""",
        )
        recreateStore()

        assertEquals("new", store.all().single().id)
        assertEquals(
            "an existing current store must not be overwritten by the legacy one",
            """[{"id":"new","name":"New","server":"s","serverPort":443,"password":"pw"}]""",
            pref.getString(ProfileStore.SERVER_PROFILES),
        )
    }

    @Test
    fun save_writesOnlyTheCurrentKey() {
        pref.putString(ProfileStore.LEGACY_SERVER_PROFILES, "legacy payload")
        recreateStore()
        store.add(profile("id1"))

        assertEquals(
            "a write must not touch the downgrade-compatible legacy key",
            "legacy payload",
            pref.getString(ProfileStore.LEGACY_SERVER_PROFILES),
        )
        assertTrue(pref.getString(ProfileStore.SERVER_PROFILES)?.contains("\"id\":\"id1\"") == true)
    }

    @Test
    fun all_withCorruptedPayload_logsOncePerPayload() {
        pref.putString(ProfileStore.SERVER_PROFILES, "NOT VALID JSON {{{")
        ShadowLog.clear()

        assertTrue(store.all().isEmpty())
        assertTrue(store.all().isEmpty())
        assertTrue(store.all().isEmpty())

        assertEquals(
            "a corrupt store must not log on every read",
            1,
            ShadowLog.getLogsForTag("ProfileStore").size,
        )
    }

    @Test
    fun delete_notifiesOnlyAfterTheActivePointerMoved() {
        store.add(profile("id1"))
        store.add(profile("id2"))
        store.select("id2")

        // An undispatched collector resumes inline inside the change event, which
        // is exactly the case where a pointer written after the event is missed.
        val seen = mutableListOf<String?>()
        val job = CoroutineScope(Dispatchers.Unconfined).launch {
            store.changes.drop(1).collect { seen += store.activeId() }
        }
        store.delete("id2")
        job.cancel()

        assertEquals(listOf("id1"), seen)
    }

    @Test
    fun delete_unknownId_doesNotNotify() {
        val before = store.changes.value

        store.delete("does-not-exist")

        assertEquals(before, store.changes.value)
    }

    @Test
    fun deletingTheLastProfile_dropsTheLegacySnapshot() {
        pref.putString(
            ProfileStore.LEGACY_SERVER_PROFILES,
            """[{"id":"only","name":"Only","server":"s","serverPort":"443","password":"pw"}]""",
        )
        recreateStore()
        assertEquals("only", store.all().single().id)

        store.delete("only")

        assertNull(
            "the frozen snapshot holds plaintext passwords and has no downgrade value anymore",
            pref.getString(ProfileStore.LEGACY_SERVER_PROFILES),
        )
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun deletingOneOfSeveralProfiles_keepsTheLegacySnapshot() {
        pref.putString(
            ProfileStore.LEGACY_SERVER_PROFILES,
            """[{"id":"id1","name":"One","server":"s","serverPort":"443","password":"pw"}]""",
        )
        recreateStore()
        store.add(profile("id2"))

        store.delete("id2")

        assertTrue(
            "the snapshot is only dropped once there are no profiles left",
            pref.getString(ProfileStore.LEGACY_SERVER_PROFILES) != null,
        )
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
