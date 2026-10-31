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
import kotlinx.coroutines.delay
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

/**
 * How long a launch-time check waits for the tunnel to come up before falling
 * back to a direct connection. Covers users who never start the VPN at all.
 */
internal const val LAUNCH_FALLBACK_DELAY_MS = 60L * 1000

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
 * Automatic checks are tunnel-aware: the request goes through the local
 * libeasyss HTTP proxy, so when the tunnel is not serving yet the check waits
 * for [TunnelState.ready] instead of racing a possibly censored direct
 * connection at cold start.
 *
 * Threading: the Activity drives [autoCheck]/[checkNow]/[downloadAndInstall]
 * from the main thread while the tunnel watcher ([observeTunnel]) calls
 * [onTunnelReady] from an IO thread, so every entry point that touches
 * [job]/[fallbackJob]/[lastAutoAttemptAt]/[pendingTunnelCheck] is
 * `@Synchronized`; helpers that read them must only be called while holding
 * that monitor.
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
    private val tunnelReady: StateFlow<Boolean>,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null
    private var fallbackJob: Job? = null

    /** Set when the tunnel became ready while another check was still in flight. */
    private var pendingTunnelCheck = false

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
            val manager = UpdateManager(
                context = appContext,
                pref = pref,
                scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                sourceFactory = { GitHubUpdateSource(pref, ProfileStore.get(appContext)) },
                verifier = { file, versionCode -> UpdateInstaller.verify(appContext, file, versionCode) },
                clock = { System.currentTimeMillis() },
                isDebugBuild = BuildConfig.DEBUG,
                currentVersionCode = BuildConfig.VERSION_CODE,
                currentVersionName = BuildConfig.VERSION_NAME,
                supportedAbis = Build.SUPPORTED_ABIS.toList(),
                tunnelReady = TunnelState.ready,
            )
            // The updater outlives any Activity, so it cannot rely on the
            // receivers MainFragment registers: watch the process-wide tunnel
            // state instead and run the deferred check the moment it is up.
            manager.observeTunnel()
            return manager
        }
    }

    /**
     * Subscribes to [tunnelReady] and runs the deferred check whenever the
     * tunnel becomes available. Returns the watcher job so tests can stop it.
     */
    internal fun observeTunnel(): Job = scope.launch {
        tunnelReady.collect { ready -> if (ready) onTunnelReady() }
    }

    /**
     * Silent launch-time check. Skipped in debug builds, when the user turned
     * automatic checks off, while another check/download is running, or when
     * the last successful check is younger than [AUTO_CHECK_INTERVAL_MS].
     *
     * When the tunnel is already serving the check runs immediately through the
     * local proxy. Otherwise it is deferred to [onTunnelReady] so the request
     * does not race a blocked direct connection at cold start; a direct attempt
     * still happens after [LAUNCH_FALLBACK_DELAY_MS] for users who never start
     * the VPN.
     */
    @Synchronized
    fun autoCheck() {
        if (isDebugBuild) return
        if (!pref.updateAutoCheck) return
        if (isBusy()) return
        if (!checkDue()) return
        if (tunnelReady.value) {
            if (isRetryThrottled()) return
            lastAutoAttemptAt = clock()
            Log.i(TAG, "auto update check (tunnel up, last check ${pref.updateLastCheckAt})")
            startCheck(manual = false)
            return
        }
        // Do not arm a grace period that is already known to end in a no-op.
        if (isRetryThrottled()) return
        // Already waiting for the tunnel: an Activity recreation must not
        // restart the grace period.
        if (fallbackJob?.isActive == true) return
        Log.i(TAG, "auto update check deferred until the tunnel is up")
        fallbackJob = scope.launch {
            delay(LAUNCH_FALLBACK_DELAY_MS)
            fireFallbackCheck()
        }
    }

    /**
     * Runs the deferred automatic check once the tunnel is serving.
     *
     * This is the moment the proxy route is known to work, so it supersedes the
     * grace period in [autoCheck] and bypasses [isRetryThrottled]: a direct
     * launch-time attempt that just failed must not block the retry that now has
     * a working route. When another check is still in flight the request is
     * queued instead of dropped, because that in-flight check is most likely the
     * doomed direct one started before the tunnel came up.
     */
    @Synchronized
    internal fun onTunnelReady() {
        if (isDebugBuild || !pref.updateAutoCheck) return
        if (!checkDue()) return
        if (isBusy()) {
            // An in-flight check started before the tunnel came up is probably
            // the doomed direct one; queue a proxy retry for when it finishes.
            pendingTunnelCheck = true
            return
        }
        fallbackJob?.cancel()
        fallbackJob = null
        lastAutoAttemptAt = clock()
        Log.i(TAG, "auto update check triggered by tunnel readiness")
        startCheck(manual = false)
    }

    /** The grace period expired without the tunnel: fall back to a direct check. */
    @Synchronized
    private fun fireFallbackCheck() {
        if (tunnelReady.value || isDebugBuild || !pref.updateAutoCheck) return
        if (isBusy() || !checkDue() || isRetryThrottled()) return
        lastAutoAttemptAt = clock()
        Log.i(TAG, "auto update check falling back to a direct connection")
        startCheck(manual = false)
    }

    /**
     * Clears [job] and runs the tunnel-readiness retry that arrived while a check
     * was in flight. The flag is consumed before re-entering so it cannot loop,
     * and [onTunnelReady] re-checks [checkDue] so a successful in-flight check
     * makes the retry a no-op.
     */
    @Synchronized
    private fun onJobFinished(finished: Job?) {
        if (job === finished) job = null
        if (!pendingTunnelCheck) return
        pendingTunnelCheck = false
        // The tunnel may have gone away while the check was in flight; retrying
        // now would silently fall back to a direct, throttle-bypassing check.
        if (!tunnelReady.value) return
        // A verified APK owns the UI state: a retry must not replace it with
        // Checking (only reachable if checkDue() flipped during a long download).
        if (_state.value is UpdateState.ReadyToInstall) return
        onTunnelReady()
    }

    /** User-triggered check; bypasses the throttle and the "already prompted" guard. */
    @Synchronized
    fun checkNow() {
        if (isDebugBuild) return
        if (isBusy()) return
        fallbackJob?.cancel()
        fallbackJob = null
        promptedVersionCode = 0
        startCheck(manual = true)
    }

    /** Downloads the release found by the last check, verifies it and hands it to the UI. */
    @Synchronized
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
                onJobFinished(selfJob)
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
    @Synchronized
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

    /** Only called while holding this instance's monitor (see the class KDoc). */
    private fun isBusy(): Boolean = job?.isActive == true

    /**
     * True once [AUTO_CHECK_INTERVAL_MS] has elapsed since the last successful
     * check. Only called while holding this instance's monitor (see the class KDoc).
     */
    private fun checkDue(): Boolean {
        val lastCheck = pref.updateLastCheckAt
        return lastCheck <= 0 || clock() - lastCheck >= AUTO_CHECK_INTERVAL_MS
    }

    /**
     * A failed check (offline, proxy down) leaves [Pref.updateLastCheckAt]
     * untouched, so rate-limit the retry instead of hitting the network on every
     * Activity recreation. Only called while holding this instance's monitor.
     */
    private fun isRetryThrottled(): Boolean {
        if (lastAutoAttemptAt == 0L) return false
        return clock() - lastAutoAttemptAt < AUTO_CHECK_RETRY_INTERVAL_MS
    }

    @Synchronized
    private fun startCheck(manual: Boolean) {
        if (isBusy()) return
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
                onJobFinished(selfJob)
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
