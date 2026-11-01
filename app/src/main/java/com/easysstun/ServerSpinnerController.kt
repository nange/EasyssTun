package com.easysstun

import android.content.Context
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner

/** 首页服务器下拉框：adapter 跟随 `ProfileStore.all`，选中项回填 `activeId`，用户切换时回调 [onSelected]。 */
internal class ServerSpinnerController(
    private val context: Context,
    private val spinner: Spinner,
    private val profiles: ProfileStore,
    private val onSelected: (Profile) -> Unit,
) {
    /** 与 adapter 条目按下标对齐的配置档列表。 */
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
                // 恢复选中也会触发回调，且回调在下一次 layout 才派发（此时 refresh() 已返回），
                // 故不能用 setSelection 前后的标志位区分；用户点击必定指向非 active 条目
                // （active 条目就是当前选中项，点它不产生事件）。
                if (profile.id == profiles.activeId()) return
                onSelected(profile)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /** 与仓库对齐；仅在列表变化时重建 adapter，避免普通返回页面重置选中并发出多余事件。 */
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
        // 重建后的 adapter 在下一次 layout 前仍可能报告旧选中位置，
        // 因此重建时无条件指向 active 条目。
        if (!adapterRebuilt && activePosition == spinner.selectedItemPosition) return
        spinner.setSelection(activePosition, false)
    }
}
