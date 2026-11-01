package com.easysstun

import android.widget.Spinner

/** 表单下拉框：显示文案与存储值来自两个按下标对齐的数组，`selected()` 返回存储值而非文案。 */
internal class SpinnerOption(
    private val spinner: Spinner,
    private val values: Array<String>,
) {
    fun select(value: String?) {
        val index = values.indexOf(value)
        if (index >= 0) spinner.setSelection(index)
    }

    fun selected(): String = values[spinner.selectedItemPosition]
}
