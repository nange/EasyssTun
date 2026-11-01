package com.easysstun

import android.widget.Spinner

/**
 * A form spinner whose visible entries are labels and whose stored value comes
 * from a parallel `*_value` array (the two arrays are aligned by position).
 *
 * Encapsulates the entry/value pairing that used to be repeated for every
 * spinner in [ServerProfileActivity], including the no-op adapter branch that
 * could never run because the adapter is always installed first.
 */
internal class SpinnerOption(
    private val spinner: Spinner,
    private val values: Array<String>,
) {
    /** Selects [value] when it is known; an unknown value leaves the selection alone. */
    fun select(value: String?) {
        val index = values.indexOf(value)
        if (index >= 0) spinner.setSelection(index)
    }

    /** The stored value of the entry the user picked. */
    fun selected(): String = values[spinner.selectedItemPosition]
}
