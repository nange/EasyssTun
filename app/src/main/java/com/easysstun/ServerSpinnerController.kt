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
 * Restoring the active entry after a refresh is not a user action, so the
 * selection is applied while the listener is suppressed; [onSelected] only fires
 * for entries the user actually picks.
 */
internal class ServerSpinnerController(
    private val context: Context,
    private val spinner: Spinner,
    private val profiles: ProfileStore,
    private val onSelected: (Profile) -> Unit,
) {
    /** Profiles currently backing the adapter, indexed by spinner position. */
    private var boundProfiles: List<Profile> = emptyList()

    /** True while [refresh] changes the selection on the user's behalf. */
    private var restoring = false

    init {
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?,
                view: View?,
                position: Int,
                id: Long,
            ) {
                if (restoring) return
                boundProfiles.getOrNull(position)?.let(onSelected)
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
        if (current != boundProfiles) {
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
        if (activePosition < 0 || activePosition == spinner.selectedItemPosition) return
        restoring = true
        spinner.setSelection(activePosition, false)
        restoring = false
    }
}
