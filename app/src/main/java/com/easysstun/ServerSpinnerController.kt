package com.easysstun

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner

/**
 * Owns the home-screen server spinner: the adapter over [ProfileStore.all], the
 * selection that mirrors [ProfileStore.activeId], and the mapping from a spinner
 * entry back to its [Profile].
 *
 * Restoring the active entry after a refresh is not a user action. The spinner
 * dispatches its selection callback on the next layout, i.e. after [refresh] has
 * returned, so a flag toggled around `setSelection()` cannot cover it; the
 * listener instead drops any selection that resolves to the current active
 * profile. [onSelected] therefore only fires for an entry the user actually
 * picked, independent of when the callback is delivered.
 */
internal class ServerSpinnerController(
    private val context: Context,
    private val spinner: Spinner,
    private val profiles: ProfileStore,
    private val onSelected: (Profile) -> Unit,
) {
    /** Profiles currently backing the adapter, indexed by spinner position. */
    private var boundProfiles: List<Profile> = emptyList()

    init {
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                val profile = boundProfiles.getOrNull(position) ?: return
                // Programmatic restore selects the active profile; a user tap
                // always resolves to a different one (the active entry is the
                // current selection, so tapping it changes nothing).
                if (profile.id == profiles.activeId()) return
                onSelected(profile)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /**
     * Brings the spinner in line with the store. The adapter is only rebuilt
     * when the profile list actually changed, so a plain screen resume does not
     * reset the selection (and cannot emit a spurious selection event).
     */
    fun refresh() {
        val current = profiles.all()
        val adapterRebuilt = current != boundProfiles
        if (adapterRebuilt) {
            boundProfiles = current
            val adapter = ArrayAdapter(
                context,
                android.R.layout.simple_spinner_item,
                current.map { it.name },
            )
            adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
            spinner.adapter = adapter
        }

        val activePosition = current.indexOfFirst { it.id == profiles.activeId() }
        if (activePosition < 0) return
        // A rebuilt adapter may still report the previous selection until the
        // next layout, so always point it at the active entry in that case.
        if (!adapterRebuilt && activePosition == spinner.selectedItemPosition) return
        spinner.setSelection(activePosition, false)
    }
}
