package com.easysstun

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.core.net.toUri
import androidx.fragment.app.FragmentActivity
import com.easysstun.databinding.DialogUpdateAvailableBinding
import com.easysstun.databinding.DialogUpdateProgressBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File

private const val TAG = "Updater"

/**
 * Owns every updater dialog for one [FragmentActivity]: the "update available"
 * prompt, the download progress, and the error/permission guidance. Created by
 * MainActivity, which is also the host of the settings screen, so the same
 * dialogs serve both the launch-time check and a manual check from settings.
 */
internal class UpdateDialogs(
    private val activity: FragmentActivity,
    private val manager: UpdateManager,
) {
    private var availableDialog: AlertDialog? = null
    private var progressDialog: AlertDialog? = null
    private var progressBinding: DialogUpdateProgressBinding? = null
    private var errorDialog: AlertDialog? = null
    private var permissionDialog: AlertDialog? = null
    private var lastFailedState: UpdateState.Failed? = null
    private var pendingInstall: File? = null
    private var unknownSourcesPrompted = false

    fun render(state: UpdateState) {
        if (state !is UpdateState.Failed) lastFailedState = null
        when (state) {
            UpdateState.Idle, UpdateState.Checking -> dismissProgress()
            is UpdateState.UpToDate -> Unit
            is UpdateState.Available -> {
                if (manager.consumePrompt(state.release.versionCode)) {
                    showAvailable(state.release)
                }
            }
            is UpdateState.Downloading -> showProgress(state)
            is UpdateState.ReadyToInstall -> {
                dismissProgress()
                if (pendingInstall != state.file) unknownSourcesPrompted = false
                pendingInstall = state.file
                maybeLaunchInstall()
            }
            is UpdateState.Failed -> {
                dismissProgress()
                // StateFlow replays the latest value on resume; only surface a
                // failure once (a retry goes through Checking and resets this).
                if (state != lastFailedState) {
                    lastFailedState = state
                    if (state.manual) {
                        showError(state.action, state.error)
                    } else {
                        Log.w(TAG, "automatic update check failed: ${state.error}")
                    }
                }
            }
        }
    }

    /** Called from the host's onResume: continues an install after the user returns from settings. */
    fun onResume() {
        if (pendingInstall != null) {
            // Returning from the "install unknown apps" screen (or simply
            // resuming) gives the user another chance to grant the permission.
            unknownSourcesPrompted = false
            maybeLaunchInstall()
        }
    }

    fun dismiss() {
        availableDialog?.dismiss()
        availableDialog = null
        errorDialog?.dismiss()
        errorDialog = null
        permissionDialog?.dismiss()
        permissionDialog = null
        dismissProgress()
    }

    private fun showAvailable(release: AvailableRelease) {
        if (!isAlive()) return
        val binding = DialogUpdateAvailableBinding.inflate(activity.layoutInflater)
        binding.updateDialogVersion.text = activity.getString(
            R.string.update_dialog_version,
            release.versionName,
            release.versionCode,
        )
        binding.updateDialogNotes.text = release.notes.trim()
            .ifBlank { activity.getString(R.string.update_dialog_no_notes) }
        binding.updateDialogNotesLink.setOnClickListener { openUrl(release.notesUrl) }

        availableDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_dialog_title)
            .setView(binding.root)
            .setPositiveButton(R.string.update_dialog_update) { _, _ ->
                manager.downloadAndInstall()
            }
            .setNeutralButton(R.string.update_dialog_skip) { _, _ ->
                manager.dismissVersion(release.versionCode)
            }
            .setNegativeButton(R.string.update_dialog_later, null)
            .setOnDismissListener { availableDialog = null }
            .show()
    }

    private fun showProgress(state: UpdateState.Downloading) {
        if (!isAlive()) return
        if (progressDialog == null) {
            val binding = DialogUpdateProgressBinding.inflate(activity.layoutInflater)
            progressBinding = binding
            val release = manager.activeRelease
            binding.updateProgressVersion.text = if (release != null) {
                activity.getString(
                    R.string.update_dialog_version,
                    release.versionName,
                    release.versionCode,
                )
            } else {
                ""
            }
            binding.updateProgressText.text = activity.getString(R.string.update_progress_starting)
            if (state.total <= 0L) {
                binding.updateProgressBar.isIndeterminate = true
            }
            progressDialog = MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.update_progress_title)
                .setView(binding.root)
                .setNegativeButton(R.string.update_cancel) { _, _ -> manager.cancelDownload() }
                .setCancelable(false)
                .setOnDismissListener {
                    progressDialog = null
                    progressBinding = null
                }
                .show()
        }

        val binding = progressBinding ?: return
        if (state.total > 0L) {
            val percent = ((state.downloaded * 100) / state.total).toInt().coerceIn(0, 100)
            binding.updateProgressBar.setProgressCompat(percent, true)
            binding.updateProgressText.text = activity.getString(
                R.string.update_progress_percent,
                percent,
                formatBytes(state.downloaded),
                formatBytes(state.total),
            )
        } else {
            binding.updateProgressText.text = activity.getString(R.string.update_progress_starting)
        }
    }

    private fun dismissProgress() {
        progressDialog?.dismiss()
        progressDialog = null
        progressBinding = null
    }

    private fun maybeLaunchInstall() {
        val file = pendingInstall ?: return
        if (!isAlive()) return
        if (!file.isFile) {
            pendingInstall = null
            showInstallError()
            return
        }
        if (!UpdateInstaller.canInstallPackages(activity)) {
            if (!unknownSourcesPrompted) {
                unknownSourcesPrompted = true
                showUnknownSources()
            }
            return
        }
        try {
            activity.startActivity(UpdateInstaller.installIntent(activity, file))
            pendingInstall = null
            unknownSourcesPrompted = false
            manager.markInstallLaunched()
        } catch (e: Exception) {
            Log.e(TAG, "cannot open the package installer", e)
            pendingInstall = null
            showInstallError()
        }
    }

    private fun showUnknownSources() {
        if (!isAlive()) return
        permissionDialog?.dismiss()
        permissionDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_unknown_sources_title)
            .setMessage(R.string.update_unknown_sources_message)
            .setPositiveButton(R.string.update_unknown_sources_settings) { _, _ ->
                runCatching {
                    activity.startActivity(UpdateInstaller.unknownSourcesSettingsIntent(activity))
                }.onFailure { Log.e(TAG, "cannot open unknown-sources settings", it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { permissionDialog = null }
            .show()
    }

    private fun showError(action: UpdateAction, error: UpdateError) {
        if (!isAlive()) return
        val builder = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_failed_title)
            .setMessage(updateErrorText(activity, error))
            .setNeutralButton(R.string.update_open_releases) { _, _ -> openUrl(RELEASES_PAGE) }
            .setNegativeButton(android.R.string.cancel, null)
        if (action == UpdateAction.CHECK || manager.activeRelease != null) {
            builder.setPositiveButton(R.string.update_retry) { _, _ -> manager.retryLastAction() }
        }
        errorDialog?.dismiss()
        errorDialog = builder
            .setOnDismissListener { errorDialog = null }
            .show()
    }

    private fun showInstallError() {
        if (!isAlive()) return
        errorDialog?.dismiss()
        errorDialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.update_failed_title)
            .setMessage(R.string.update_install_failed)
            .setPositiveButton(R.string.update_open_releases) { _, _ -> openUrl(RELEASES_PAGE) }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { errorDialog = null }
            .show()
    }

    private fun openUrl(url: String) {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "no activity to open $url", e)
        }
    }

    private fun isAlive(): Boolean = !activity.isFinishing && !activity.isDestroyed
}

/** Inline status line for the settings card; null hides the line. */
internal fun updateStatusText(context: Context, state: UpdateState): String? = when (state) {
    UpdateState.Idle -> null
    UpdateState.Checking -> context.getString(R.string.update_checking)
    is UpdateState.UpToDate -> context.getString(R.string.update_up_to_date, state.versionName)
    is UpdateState.Available -> context.getString(
        R.string.update_available,
        state.release.versionName,
        formatBytes(state.release.asset.size),
    )
    is UpdateState.Downloading -> context.getString(
        R.string.update_downloading_status,
        if (state.total > 0L) {
            ((state.downloaded * 100) / state.total).toInt().coerceIn(0, 100)
        } else {
            0
        },
    )
    is UpdateState.ReadyToInstall -> context.getString(R.string.update_ready_status)
    is UpdateState.Failed -> updateErrorText(context, state.error)
}

internal fun updateErrorText(context: Context, error: UpdateError): String = when (error) {
    UpdateError.Network -> context.getString(R.string.update_error_network)
    is UpdateError.Http -> context.getString(R.string.update_error_http, error.code)
    UpdateError.RateLimited -> context.getString(R.string.update_error_rate_limited)
    UpdateError.NoCompatibleAsset -> context.getString(R.string.update_error_no_asset)
    UpdateError.MetadataMismatch -> context.getString(R.string.update_error_metadata)
    UpdateError.ChecksumMismatch -> context.getString(R.string.update_error_checksum)
    UpdateError.Storage -> context.getString(R.string.update_error_storage)
    UpdateError.InvalidApk -> context.getString(R.string.update_error_invalid_apk)
    UpdateError.SignerMismatch -> context.getString(R.string.update_error_signature)
    is UpdateError.Io -> error.message?.takeIf { it.isNotBlank() }
        ?.let { context.getString(R.string.update_error_io, it) }
        ?: context.getString(R.string.update_error_unknown)
}
