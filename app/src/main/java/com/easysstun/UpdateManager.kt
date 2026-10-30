package com.easysstun

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

private const val TAG = "Updater"

/** Automatic checks run at most once per this interval. */
internal const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

/** After a failed automatic check, do not retry on every Activity recreation. */
internal const val AUTO_CHECK_RETRY_INTERVAL_MS = 10L * 60 * 1000

internal const val CHECK_TIMEOUT_MS = 15_000L
internal const val DOWNLOAD_TIMEOUT_MS = 10L * 60 * 1000

internal enum class UpdateAction { CHECK, DOWNLOAD }

internal sealed interface UpdateError {
    data object Network : UpdateError
    data class Http(val code: Int) : UpdateError
    data object RateLimited : UpdateError
    data object NoCompatibleAsset : UpdateError
    data object MetadataMismatch : UpdateError
    data object ChecksumMismatch : UpdateError
    data object Storage : UpdateError
    data object InvalidApk : UpdateError
    data object SignerMismatch : UpdateError
    data class Io(val message: String?) : UpdateError
}

internal class UpdateException(
    val error: UpdateError,
    message: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause)

internal data class AvailableRelease(
    val tag: String,
    val versionName: String,
    val versionCode: Int,
    val notes: String,
    val notesUrl: String,
    val asset: ApkAsset,
    val manual: Boolean,
)

internal sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class UpToDate(val versionName: String) : UpdateState
    data class Available(val release: AvailableRelease) : UpdateState
    data class Downloading(val downloaded: Long, val total: Long) : UpdateState
    data class ReadyToInstall(val file: File, val release: AvailableRelease) : UpdateState
    data class Failed(val action: UpdateAction, val error: UpdateError, val manual: Boolean) : UpdateState
}

/**
 * Application-scoped update orchestration: throttled check against the GitHub
 * release API, download + verification, and exposure of the progress as a
 * [StateFlow] so an Activity/Fragment can render it and survive rotation.
 *
 * Every dependency is injectable for unit tests; production code builds an
 * instance through [create] from [EasyssTunApplication].
 */
internal class UpdateManager internal constructor(
    private val context: Context,
    private val pref: Pref,
    private val scope: CoroutineScope,
    private val sourceFactory: () -> UpdateSource,
    private val verifier: (File, Int) -> InstallCheck,
    private val clock: () -> Long,
    private val isDebugBuild: Boolean,
    private val currentVersionCode: Int,
    private val currentVersionName: String,
    private val supportedAbis: List<String>,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null
    @Volatile
    private var availableRelease: AvailableRelease? = null
    private var promptedVersionCode = 0
    private var lastAutoAttemptAt = 0L

    /** Release selected by the last check; null when up to date or never checked. */
    val activeRelease: AvailableRelease? get() = availableRelease

    companion object {
        fun create(context: Context): UpdateManager {
            val appContext = context.applicationContext
            val pref = Pref(appContext)
            return UpdateManager(
                context = appContext,
                pref = pref,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                sourceFactory = { GitHubUpdateSource(pref) },
                verifier = { file, versionCode -> UpdateInstaller.verify(appContext, file, versionCode) },
                clock = { System.currentTimeMillis() },
                isDebugBuild = BuildConfig.DEBUG,
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
            )
        }
    }

    /**
     * Silent launch-time check. Skipped in debug builds, when the user turned
     * automatic checks off, while another check/download is running, or when
     * the last successful check is younger than [AUTO_CHECK_INTERVAL_MS].
     */
    fun autoCheck() {
        if (isDebugBuild) return
        if (!pref.updateAutoCheck) return
        if (isBusy()) return
        val now = clock()
        val lastCheck = pref.updateLastCheckAt
        if (lastCheck > 0 && now - lastCheck < AUTO_CHECK_INTERVAL_MS) return
        // A failed check (offline, proxy down) leaves updateLastCheckAt untouched;
        // rate-limit the retry instead of hitting the network on every rotation.
        if (now - lastAutoAttemptAt < AUTO_CHECK_RETRY_INTERVAL_MS) return
        lastAutoAttemptAt = now
        Log.i(TAG, "auto update check (last check $lastCheck)")
        startCheck(manual = false)
    }

    /** User-triggered check; bypasses the throttle and the "already prompted" guard. */
    fun checkNow() {
        if (isDebugBuild) return
        if (isBusy()) return
        promptedVersionCode = 0
        startCheck(manual = true)
    }

    /** Downloads the release found by the last check, verifies it and hands it to the UI. */
    fun downloadAndInstall() {
        val release = availableRelease ?: return
        if (isBusy()) return
        _state.value = UpdateState.Downloading(0L, release.asset.size)
        job = scope.launch {
            val selfJob = coroutineContext[Job]
            val updateDir = UpdateInstaller.updateDir(context)
            val dest = File(updateDir, "EasyssTun-${release.versionCode}.apk")
            var keepFile = false
            try {
                UpdateInstaller.cleanup(context)
                val source = sourceFactory()
                withTimeout(DOWNLOAD_TIMEOUT_MS) {
                    source.download(release.asset, dest) { downloaded, total ->
                        _state.value = UpdateState.Downloading(downloaded, total)
                    }
                }
                Log.i(TAG, "downloaded update ${release.versionName} (${dest.length()} bytes), verifying")
                when (verifier(dest, release.versionCode)) {
                    InstallCheck.Ok -> {
                        keepFile = true
                        _state.value = UpdateState.ReadyToInstall(dest, release)
                    }
                    InstallCheck.SignerMismatch ->
                        failDownload(UpdateError.SignerMismatch, release)
                    InstallCheck.InvalidApk ->
                        failDownload(UpdateError.InvalidApk, release)
                    InstallCheck.WrongPackage, InstallCheck.VersionMismatch ->
                        failDownload(UpdateError.MetadataMismatch, release)
                }
            } catch (e: TimeoutCancellationException) {
                failDownload(UpdateError.Network, release)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                failDownload(e.error, release)
            } catch (e: Exception) {
                failDownload(UpdateError.Io(e.message), release)
            } finally {
                if (!keepFile) dest.delete()
                if (job === selfJob) job = null
            }
        }
    }

    /**
     * Clears [UpdateState.ReadyToInstall] once the system installer has been
     * opened: the file stays in the cache, but re-rendering the state (resume,
     * rotation) must not relaunch the installer the user just dismissed.
     */
    fun markInstallLaunched() {
        if (_state.value is UpdateState.ReadyToInstall) {
            _state.value = UpdateState.Idle
        }
    }

    /** Cancels an in-flight download and removes the partial file. */
    fun cancelDownload() {
        job?.cancel()
        job = null
        _state.value = UpdateState.Idle
    }

    /** Remembers the version the user asked not to be prompted about. */
    fun dismissVersion(versionCode: Int) {
        pref.updateDismissedVersionCode = versionCode
    }

    /**
     * Returns true exactly once per version (per process) so rotation does not
     * re-open the "update available" dialog; skipped versions never prompt.
     */
    fun consumePrompt(versionCode: Int): Boolean {
        if (pref.updateDismissedVersionCode == versionCode) return false
        if (promptedVersionCode == versionCode) return false
        promptedVersionCode = versionCode
        return true
    }

    /** Re-runs whatever failed last: the check, or the download of the known release. */
    fun retryLastAction() {
        when ((_state.value as? UpdateState.Failed)?.action) {
            UpdateAction.CHECK -> checkNow()
            UpdateAction.DOWNLOAD ->
                if (availableRelease != null) downloadAndInstall() else checkNow()
            null -> Unit
        }
    }

    private fun isBusy(): Boolean = job?.isActive == true

    private fun startCheck(manual: Boolean) {
        _state.value = UpdateState.Checking
        job = scope.launch {
            val selfJob = coroutineContext[Job]
            try {
                val release = withTimeout(CHECK_TIMEOUT_MS) {
                    sourceFactory().fetchLatestRelease()
                }
                val result = evaluate(release, manual)
                pref.updateLastCheckAt = clock()
                _state.value = result
                Log.i(TAG, "check finished: ${result::class.simpleName}")
            } catch (e: TimeoutCancellationException) {
                _state.value = UpdateState.Failed(UpdateAction.CHECK, UpdateError.Network, manual)
            } catch (e: CancellationException) {
                throw e
            } catch (e: UpdateException) {
                _state.value = UpdateState.Failed(UpdateAction.CHECK, e.error, manual)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(UpdateAction.CHECK, UpdateError.Io(e.message), manual)
            } finally {
                if (job === selfJob) job = null
            }
        }
    }

    private fun evaluate(release: Release, manual: Boolean): UpdateState {
        val tagVersion = release.tagName.trim().trimStart('v', 'V')
        val asset = pickApk(release, supportedAbis)
            ?: return UpdateState.Failed(UpdateAction.CHECK, UpdateError.NoCompatibleAsset, manual)
        if (tagVersion.isBlank() || asset.versionName != tagVersion) {
            Log.w(TAG, "asset ${asset.name} does not match release tag ${release.tagName}")
            return UpdateState.Failed(UpdateAction.CHECK, UpdateError.MetadataMismatch, manual)
        }

        val codeNewer = asset.versionCode > currentVersionCode
        val nameNewer = isNewerVersion(currentVersionName, asset.versionName)
        val available = AvailableRelease(
            tag = release.tagName,
            versionName = asset.versionName,
            versionCode = asset.versionCode,
            notes = release.body.orEmpty(),
            notesUrl = release.htmlUrl.ifBlank { RELEASES_PAGE },
            asset = asset,
            manual = manual,
        )
        return when {
            codeNewer && nameNewer -> {
                availableRelease = available
                UpdateState.Available(available)
            }
            !codeNewer && !nameNewer -> {
                availableRelease = null
                UpdateState.UpToDate(currentVersionName)
            }
            else -> {
                // versionCode and versionName disagree: the release is inconsistent,
                // so refuse to offer an install the package manager would reject.
                Log.w(
                    TAG,
                    "inconsistent release ${release.tagName}: codeNewer=$codeNewer nameNewer=$nameNewer",
                )
                availableRelease = null
                UpdateState.Failed(UpdateAction.CHECK, UpdateError.MetadataMismatch, manual)
            }
        }
    }

    private fun failDownload(error: UpdateError, release: AvailableRelease) {
        Log.w(TAG, "update download failed: $error")
        _state.value = UpdateState.Failed(UpdateAction.DOWNLOAD, error, release.manual)
    }
}
