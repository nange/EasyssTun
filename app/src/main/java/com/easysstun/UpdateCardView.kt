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

/**
 * Update section on the home screen: app version, automatic-check toggle,
 * check/download actions and a one-line status.
 *
 * The view owns no update state; everything is rendered from
 * [UpdateManager.state]. Modal dialogs (available/progress/errors) stay in
 * [UpdateDialogs], hosted by MainActivity, so checks triggered here also keep
 * working while the user navigates to other screens.
 */
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
            // The release APK has a different applicationId/signature, so an
            // in-app update can never apply to a debug build.
            binding.updateAutoCheckSwitch.isEnabled = false
            binding.updateCheckButton.isEnabled = false
            binding.updateDownloadButton.visibility = View.GONE
            binding.updateStatus.visibility = View.VISIBLE
            binding.updateStatus.text = context.getString(R.string.update_debug_disabled)
        }
    }

    /** Binds to the process-scoped updater; called again whenever a new fragment view is created. */
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
        // Let taps on the label toggle the switch as well.
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
        super.onDetachedFromWindow()
    }
}
