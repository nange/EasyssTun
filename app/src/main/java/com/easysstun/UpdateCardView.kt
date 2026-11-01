package com.easysstun

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.easysstun.databinding.ViewUpdateCardBinding
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** 首页「软件更新」卡片：版本号、自动检查开关、检查/下载与状态行。
 * 本视图不持有更新状态，全部渲染自 [UpdateManager.state]；弹窗在 [UpdateDialogs]，故离开本页后检查仍继续。 */
class UpdateCardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : MaterialCardView(context, attrs, defStyleAttr) {

    private val binding = ViewUpdateCardBinding.inflate(LayoutInflater.from(context), this)
    private var stateJob: Job? = null
    private var boundManager: UpdateManager? = null
    private var boundOwner: LifecycleOwner? = null

    init {
        binding.updateCurrentVersion.text = context.getString(
            R.string.update_current_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )
        if (BuildConfig.DEBUG) {
            // debug 包 applicationId 带后缀且签名不同，应用内更新装不上，直接禁用。
            binding.updateAutoCheckSwitch.isEnabled = false
            binding.updateCheckButton.isEnabled = false
            binding.updateDownloadButton.visibility = View.GONE
            binding.updateStatus.visibility = View.VISIBLE
            binding.updateStatus.text = context.getString(R.string.update_debug_disabled)
        }
    }

    /** 绑定进程级更新器；每次新建 Fragment 视图都会重新调用。 */
    internal fun bind(manager: UpdateManager, pref: Pref, owner: LifecycleOwner) {
        if (boundManager === manager && boundOwner === owner) return
        stateJob?.cancel()
        boundManager = manager
        boundOwner = owner
        if (BuildConfig.DEBUG) return

        binding.updateAutoCheckSwitch.isChecked = pref.updateAutoCheck
        binding.updateAutoCheckSwitch.setOnCheckedChangeListener { _, checked ->
            pref.updateAutoCheck = checked
        }
        binding.updateAutoCheckRow.setOnClickListener {
            binding.updateAutoCheckSwitch.toggle()
        }
        binding.updateCheckButton.setOnClickListener { manager.checkNow() }
        binding.updateDownloadButton.setOnClickListener { manager.downloadAndInstall() }

        stateJob = owner.lifecycleScope.launch {
            owner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                manager.state.collect { render(it) }
            }
        }
    }

    private fun render(state: UpdateState) {
        val status = updateStatusText(context, state)
        binding.updateStatus.text = status
        binding.updateStatus.visibility = if (status.isNullOrBlank()) View.GONE else View.VISIBLE
        binding.updateDownloadButton.visibility =
            if (state is UpdateState.Available) View.VISIBLE else View.GONE
        binding.updateCheckButton.isEnabled =
            state !is UpdateState.Checking && state !is UpdateState.Downloading
    }

    override fun onDetachedFromWindow() {
        stateJob?.cancel()
        stateJob = null
        // 必须在这里忘掉绑定：bind() 对相同 manager/owner 会提前返回，
        // 否则视图 detach 再 attach 后永远不会重新订阅 state。
        boundManager = null
        boundOwner = null
        super.onDetachedFromWindow()
    }
}
