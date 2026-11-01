package com.easysstun

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

/**
 * Repository of the user's server profiles.
 *
 * Owns everything about profiles that used to live in [Pref]: the serialized
 * list, the active profile pointer, and the rule that the pointer never dangles
 * ([select] ignores unknown ids, and deleting the active profile falls back to
 * the first survivor, clearing the pointer only when the last profile goes).
 * Adding a profile does not change the selection.
 *
 * The list is stored under [SERVER_PROFILES]. [LEGACY_SERVER_PROFILES] is the key
 * the pre-store builds wrote; it is copied into [SERVER_PROFILES] when the store
 * is created and then left untouched, so a build that is downgraded (and only
 * understands the old, string-typed JSON) still finds its profiles.
 *
 * That snapshot is frozen: a profile deleted by this build can reappear after a
 * downgrade, and a profile change made while downgraded is not carried back. The
 * snapshot is dropped as soon as the last profile is deleted — downgrade
 * compatibility is meaningless then, and the snapshot still holds the profiles'
 * plaintext passwords.
 *
 * Mutations are persisted through the key/value layer of [Pref] and bump
 * [changes] only after every related key has been written, so a collector
 * resumed inline by the change event always observes the post-mutation snapshot.
 * The store never broadcasts anything itself: whether a change means the running
 * tunnel has to reload its configuration is a separate decision, taken
 * explicitly by the caller through [ConfigChangeNotifier].
 */
internal class ProfileStore private constructor(private val pref: Pref) {

    /** Bumped after every mutation so observers can reload the profile list. */
    private val revision = MutableStateFlow(0L)

    /**
     * Change event for the stored profiles. The current revision is emitted to
     * every new collector, so a screen that starts observing after the change
     * still refreshes once.
     */
    val changes: StateFlow<Long> = revision.asStateFlow()

    /**
     * Raw payload that already failed to decode. Keeps a corrupt store from
     * logging on every read (the log viewer reads the active profile regularly).
     */
    @Volatile
    private var corruptPayload: String? = null

    /**
     * All stored profiles in insertion order. A corrupt payload reads as an
     * empty list (and is logged once) instead of crashing every screen that
     * lists profiles; the user can still create a new profile from the form.
     */
    fun all(): List<Profile> {
        val json = pref.getString(SERVER_PROFILES) ?: return emptyList()
        return try {
            appJson.decodeFromString<List<Profile>>(json).also { corruptPayload = null }
        } catch (e: Exception) {
            logCorruptOnce(json, e)
            emptyList()
        }
    }

    fun find(id: String): Profile? = all().firstOrNull { it.id == id }

    /** The profile the tunnel would connect to, or null when none is selected. */
    fun active(): Profile? = activeId()?.let(::find)

    fun activeId(): String? = pref.getString(ACTIVE_SERVER_ID)

    fun add(profile: Profile) {
        save(all() + profile)
    }

    fun update(profile: Profile) {
        val profiles = all().toMutableList()
        val index = profiles.indexOfFirst { it.id == profile.id }
        if (index == -1) {
            Log.w(TAG, "update(${profile.id}): no such profile, ignoring")
            return
        }
        profiles[index] = profile
        save(profiles)
    }

    /**
     * Deletes [id]. When it was the active profile, the first remaining profile
     * becomes active so a configured app never ends up with profiles but no
     * selection; only deleting the last profile clears the pointer.
     */
    fun delete(id: String) {
        val profiles = all()
        val remaining = profiles.filterNot { it.id == id }
        if (remaining.size == profiles.size) {
            Log.w(TAG, "delete($id): no such profile, ignoring")
            return
        }
        // Move the pointer before emitting: a collector resumed inline by the
        // change event must observe the post-delete snapshot, not the id that
        // just disappeared.
        if (activeId() == id) {
            pref.putString(ACTIVE_SERVER_ID, remaining.firstOrNull()?.id)
        }
        save(remaining)
    }

    /**
     * Points the active-profile pointer at [id]. Unknown ids are ignored so the
     * pointer can never dangle.
     */
    fun select(id: String) {
        if (find(id) == null) {
            Log.w(TAG, "select($id): no such profile, ignoring")
            return
        }
        pref.putString(ACTIVE_SERVER_ID, id)
        notifyChanged()
    }

    /**
     * One-time migration of the pre-store key, run while the process-wide
     * instance is being created. Doing it there (instead of on the first read)
     * keeps [all] a pure read and rules out a background reader overwriting a
     * profile that another thread wrote between its read and its write.
     *
     * The legacy key is deliberately left in place: it is the frozen snapshot a
     * downgraded build reads.
     */
    private fun migrateLegacyStore() {
        if (pref.getString(SERVER_PROFILES) != null) return
        val legacy = pref.getString(LEGACY_SERVER_PROFILES) ?: return
        Log.i(TAG, "migrating the legacy profile store into $SERVER_PROFILES")
        pref.putString(SERVER_PROFILES, legacy)
    }

    private fun logCorruptOnce(json: String, error: Exception) {
        if (corruptPayload == json) return
        corruptPayload = json
        Log.e(TAG, "stored profiles are unreadable, treating them as empty: ${error.message}", error)
    }

    private fun save(profiles: List<Profile>) {
        pref.putString(SERVER_PROFILES, appJson.encodeToString(profiles))
        if (profiles.isEmpty()) {
            // Downgrade compatibility is meaningless without profiles, and the
            // frozen snapshot still holds the profiles' plaintext passwords:
            // drop it once the user has deleted everything.
            pref.remove(LEGACY_SERVER_PROFILES)
        }
        notifyChanged()
    }

    private fun notifyChanged() {
        revision.value = revision.value + 1
    }

    companion object {
        private const val TAG = LogTags.PROFILE_STORE

        /** Current profile-store key (typed JSON: numbers and booleans). */
        const val SERVER_PROFILES = "server_profiles_v2"

        /**
         * Key written by builds before the typed model. Read once for migration
         * and kept so a downgraded build can still read its profiles.
         */
        const val LEGACY_SERVER_PROFILES = "server_profiles"

        /** Unchanged: the pointer is a plain profile id in both formats. */
        const val ACTIVE_SERVER_ID = "active_server_id"

        @Volatile
        private var instance: ProfileStore? = null

        /** Process-wide instance: a mutation must be visible to every screen. */
        fun get(context: Context): ProfileStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: run {
                    val created = ProfileStore(Pref(context.applicationContext))
                    // Migrate before publishing: a caller that observes the
                    // instance must never see a half-migrated store.
                    created.migrateLegacyStore()
                    instance = created
                    created
                }
            }
        }

        internal fun resetForTests() {
            synchronized(this) { instance = null }
        }
    }
}
