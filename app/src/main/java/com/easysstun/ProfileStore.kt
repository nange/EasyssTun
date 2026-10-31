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
 * list, the active profile pointer, and the invariant that the pointer always
 * names an existing profile while profiles remain (deleting the active profile
 * falls back to the first survivor instead of leaving a dangling id).
 *
 * Mutations are persisted through the key/value layer of [Pref] and bump
 * [changes]. The store never broadcasts anything itself: whether a change means
 * the running tunnel has to reload its configuration is a separate decision,
 * taken explicitly by the caller through [ConfigChangeNotifier].
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
     * All stored profiles in insertion order. A corrupt payload reads as an
     * empty list (and is logged) instead of crashing every screen that lists
     * profiles; the user can still create a new profile from the form.
     */
    fun all(): List<Profile> {
        val json = pref.getString(SERVER_PROFILES) ?: return emptyList()
        return try {
            appJson.decodeFromString<List<Profile>>(json)
        } catch (e: Exception) {
            Log.e(TAG, "stored profiles are unreadable, treating them as empty: ${e.message}", e)
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
        val remaining = all().filterNot { it.id == id }
        save(remaining)
        if (activeId() == id) {
            pref.putString(ACTIVE_SERVER_ID, remaining.firstOrNull()?.id)
        }
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

    private fun save(profiles: List<Profile>) {
        pref.putString(SERVER_PROFILES, appJson.encodeToString(profiles))
        notifyChanged()
    }

    private fun notifyChanged() {
        revision.value = revision.value + 1
    }

    companion object {
        private const val TAG = "ProfileStore"

        /**
         * SharedPreferences keys. Kept byte-for-byte identical to the ones the
         * pre-store builds wrote, so an upgrade reads the existing profiles.
         */
        const val SERVER_PROFILES = "server_profiles"
        const val ACTIVE_SERVER_ID = "active_server_id"

        @Volatile
        private var instance: ProfileStore? = null

        /** Process-wide instance: a mutation must be visible to every screen. */
        fun get(context: Context): ProfileStore {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: ProfileStore(Pref(context.applicationContext)).also { instance = it }
            }
        }

        internal fun resetForTests() {
            synchronized(this) { instance = null }
        }
    }
}
