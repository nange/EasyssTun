package com.musan.easysstun


import android.content.Intent
import android.os.Bundle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat


class SettingsFragment : PreferenceFragmentCompat() {

    private lateinit var pref: Pref
    private lateinit var serverProfilesCategory: PreferenceCategory

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.root_preferences, rootKey)

        pref = Pref(requireContext())
        serverProfilesCategory = findPreference("server_profiles_category")!!

        // Remove old password preference logic if it exists - this part needs careful review
        // based on what's left in root_preferences.xml.
        // For now, assuming it's removed or handled differently.

        // Remove or adapt the generic preference change listener
        // The loop below is removed as individual server settings are gone.
        // Specific listeners will be set for server profiles and the add button.

        refreshServerList()
        setupAddServerButton()
    }

    private fun refreshServerList() {
        serverProfilesCategory.removeAll() // Clear existing profiles

        val profiles = pref.getServerProfiles()
        val activeProfileId = pref.getActiveServerProfile()?.id

        profiles.forEach { profile ->
            val serverPref = Preference(requireContext()).apply {
                key = profile.id
                title = if (profile.name.isNotBlank()) profile.name else profile.server
                summary = if (profile.id == activeProfileId) {
                    "${profile.server} (Active)"
                } else {
                    profile.server
                }
                setOnPreferenceClickListener {
                    // Open ServerProfileActivity for editing this profile
                    val intent = Intent(context, ServerProfileActivity::class.java).apply {
                        putExtra("profileId", profile.id)
                    }
                    startActivity(intent)
                    true
                }
                // Note: Editing is handled by clicking the preference.
                // Activation status is also managed within ServerProfileActivity or by re-selecting.
            }
            serverProfilesCategory.addPreference(serverPref)
        }
    }

    private fun setupAddServerButton() {
        val addServerButton: Preference? = findPreference("add_new_server_button")
        addServerButton?.setOnPreferenceClickListener {
            val intent = Intent(context, ServerProfileActivity::class.java)
            startActivity(intent)
            true
        }
    }

    override fun onResume() {
        super.onResume()
        // Refresh the list in case changes were made in ServerProfileActivity
        // This is important for when we return from adding/editing a profile.
        if (::pref.isInitialized && ::serverProfilesCategory.isInitialized) {
             refreshServerList()
        }
    }
}