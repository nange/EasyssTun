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

private const val TAG = LogTags.UPDATER

/** 自动检查的最短间隔：24h 窗口，单位毫秒。 */
internal const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

/** 失败后的重试节流：避免每次 Activity 重建都打网络，单位毫秒。 */
internal const val AUTO_CHECK_RETRY_INTERVAL_MS = 10L * 60 * 1000

/** 冷启动等待隧道就绪的上限（60s）；超时后直连兜底，覆盖从不连 VPN 的用户。 */
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
    data class Downloading(
        val downloaded: Long,
        val total: Long,
        val release: AvailableRelease,
    ) : UpdateState
    data class ReadyToInstall(val file: File, val release: AvailableRelease) : UpdateState
    data class Failed(
        val action: UpdateAction,
        val error: UpdateError,
        val manual: Boolean,
        /** 下载失败的 release；检查失败时为 null。 */
        val release: AvailableRelease? = null,
    ) : UpdateState
}

/** UpdateManager 所需的全部宿主依赖；测试可用一个命名对象整体构造假环境。 */
internal data class UpdateEnvironment(
    val context: Context,
    val pref: Pref,
    val scope: CoroutineScope,
    val sourceFactory: () -> UpdateSource,
    val verifier: (File, Int) -> InstallCheck,
    val clock: () -> Long,
    val isDebugBuild: Boolean,
    val currentVersionCode: Int,
    val currentVersionName: String,
    val supportedAbis: List<String>,
    val tunnelReady: StateFlow<Boolean>,
)

/** 进程级更新编排：节流检查、下载校验，状态经 [StateFlow] 交给 UI。
 * 隧道未就绪时挂起等 [TunnelState.ready]；[job]/[fallbackJob] 等字段只在持锁（`@Synchronized`）时读写，
 * [observeTunnel] 从 IO 线程回调。 */
internal class UpdateManager internal constructor(
    private val env: UpdateEnvironment,
) {
    private val context get() = env.context
    private val pref get() = env.pref
    private val scope get() = env.scope
    private val sourceFactory get() = env.sourceFactory
    private val verifier get() = env.verifier
    private val clock get() = env.clock
    private val isDebugBuild get() = env.isDebugBuild
    private val currentVersionCode get() = env.currentVersionCode
    private val currentVersionName get() = env.currentVersionName
    private val supportedAbis get() = env.supportedAbis
    private val tunnelReady get() = env.tunnelReady

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private var job: Job? = null
    private var fallbackJob: Job? = null

    /** 检查进行中隧道就绪时置位；当前任务结束后补一次走代理的检查。 */
    private var pendingTunnelCheck = false

    private var promptedVersionCode = 0
    private var lastAutoAttemptAt = 0L

    companion object {
        fun create(context: Context): UpdateManager {
            val appContext = context.applicationContext
            val manager = UpdateManager(
                UpdateEnvironment(
                    context = appContext,
                    pref = Pref(appContext),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
                    sourceFactory = { GitHubUpdateSource(ProfileStore.get(appContext)) },
                    verifier = { file, versionCode ->
                        UpdateInstaller.verify(appContext, file, versionCode)
                    },
                    clock = { System.currentTimeMillis() },
                    isDebugBuild = BuildConfig.DEBUG,
                    currentVersionCode = BuildConfig.VERSION_CODE,
                    currentVersionName = BuildConfig.VERSION_NAME,
                    supportedAbis = Build.SUPPORTED_ABIS.toList(),
                    tunnelReady = TunnelState.ready,
                )
            )
            // 更新器生命周期长于 Activity，不能依赖 MainFragment 注册的接收器：改订阅进程级隧道状态。
            manager.observeTunnel()
            return manager
        }
    }

    /** 订阅 [tunnelReady]，就绪即补跑挂起的检查；返回 watcher Job 供测试取消。 */
    internal fun observeTunnel(): Job = scope.launch {
        tunnelReady.collect { ready -> if (ready) onTunnelReady() }
    }

    /** 启动时静默检查：debug、关闭自动检查、已有任务或未满 [AUTO_CHECK_INTERVAL_MS] 均跳过。
     * 隧道已就绪立刻走本地代理；否则挂起到 [onTunnelReady]（避免冷启动直连被墙），[LAUNCH_FALLBACK_DELAY_MS] 后仍无隧道才直连兜底。 */
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
        // 已被节流时不要起一个注定空跑的宽限期。
        if (isRetryThrottled()) return
        // 已在等隧道：Activity 重建不能重置宽限期。
        if (fallbackJob?.isActive == true) return
        Log.i(TAG, "auto update check deferred until the tunnel is up")
        fallbackJob = scope.launch {
            delay(LAUNCH_FALLBACK_DELAY_MS)
            fireFallbackCheck()
        }
    }

    /** 隧道就绪时补跑自动检查：此刻代理路由可用，故取消宽限期并绕过 [isRetryThrottled]，
     * 刚失败的直连尝试不得挡住这次重试；已有检查在飞则排队。 */
    @Synchronized
    internal fun onTunnelReady() {
        if (isDebugBuild || !pref.updateAutoCheck) return
        if (!checkDue()) return
        if (isBusy()) {
            // 在飞的检查多半是隧道起来前的直连，排队等它结束后补一次代理重试。
            pendingTunnelCheck = true
            return
        }
        fallbackJob?.cancel()
        fallbackJob = null
        lastAutoAttemptAt = clock()
        Log.i(TAG, "auto update check triggered by tunnel readiness")
        startCheck(manual = false)
    }

    /** 宽限期到点仍无隧道：直连兜底检查。 */
    @Synchronized
    private fun fireFallbackCheck() {
        if (tunnelReady.value || isDebugBuild || !pref.updateAutoCheck) return
        if (isBusy() || !checkDue() || isRetryThrottled()) return
        lastAutoAttemptAt = clock()
        Log.i(TAG, "auto update check falling back to a direct connection")
        startCheck(manual = false)
    }

    /** 清空 [job] 并补跑在飞期间到达的隧道就绪重试；先消费标志再重入以免自循环，
     * [onTunnelReady] 会重查 [checkDue]，在飞检查已成功时补跑自动落空。 */
    @Synchronized
    private fun onJobFinished(finished: Job?) {
        if (job === finished) job = null
        if (!pendingTunnelCheck) return
        pendingTunnelCheck = false
        // 隧道可能在检查期间断开；此时重试会静默退回绕过节流的直连检查。
        if (!tunnelReady.value) return
        // 已校验的 APK 占用 UI 状态：重试不得把它覆盖成 Checking（长下载期间 checkDue 可能翻转）。
        if (_state.value is UpdateState.ReadyToInstall) return
        onTunnelReady()
    }

    /** 用户手动检查：绕过节流与「已提示」守卫。 */
    @Synchronized
    fun checkNow() {
        if (isDebugBuild) return
        if (isBusy()) return
        fallbackJob?.cancel()
        fallbackJob = null
        promptedVersionCode = 0
        startCheck(manual = true)
    }

    /** 下载上次检查到的 release，校验通过后交给 UI。 */
    @Synchronized
    fun downloadAndInstall() {
        val release = releaseOf(_state.value) ?: return
        if (isBusy()) return
        _state.value = UpdateState.Downloading(0L, release.asset.size, release)
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
                        _state.value = UpdateState.Downloading(downloaded, total, release)
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
            } catch (e: Exception) {
                // 真正的取消必须向上传播；超时按失败处理。
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                failDownload(errorOf(e), release)
            } finally {
                if (!keepFile) dest.delete()
                onJobFinished(selfJob)
            }
        }
    }

    /** 系统安装器打开后清掉 [UpdateState.ReadyToInstall]：文件仍在缓存，但恢复/旋转重渲染不得再次拉起安装器。 */
    @Synchronized
    fun markInstallLaunched() {
        if (_state.value is UpdateState.ReadyToInstall) {
            _state.value = UpdateState.Idle
        }
    }

    /** 取消在飞下载，半成品由协程的 finally 清理。 */
    @Synchronized
    fun cancelDownload() {
        job?.cancel()
        job = null
        _state.value = UpdateState.Idle
    }

    /** 记录用户选择跳过的 versionCode，持久化后不再提示。 */
    @Synchronized
    fun dismissVersion(versionCode: Int) {
        pref.updateDismissedVersionCode = versionCode
    }

    /** 每个版本（进程内）只返回一次 true，避免旋转重开「有更新」弹窗；已跳过的版本恒为 false。 */
    @Synchronized
    fun consumePrompt(versionCode: Int): Boolean {
        if (pref.updateDismissedVersionCode == versionCode) return false
        if (promptedVersionCode == versionCode) return false
        promptedVersionCode = versionCode
        return true
    }

    /** 重试上次失败的动作：检查，或已知 release 的下载。 */
    @Synchronized
    fun retryLastAction() {
        when (val failed = _state.value as? UpdateState.Failed) {
            null -> Unit
            else -> when (failed.action) {
                UpdateAction.CHECK -> checkNow()
                UpdateAction.DOWNLOAD ->
                    if (failed.release != null) downloadAndInstall() else checkNow()
            }
        }
    }

    /** release 只存在于状态里，避免额外的 `activeRelease` 字段与 UI 显示不一致。 */
    private fun releaseOf(state: UpdateState): AvailableRelease? = when (state) {
        is UpdateState.Available -> state.release
        is UpdateState.Downloading -> state.release
        is UpdateState.ReadyToInstall -> state.release
        is UpdateState.Failed -> state.release
        UpdateState.Idle, UpdateState.Checking, is UpdateState.UpToDate -> null
    }

    /** 只在持有本实例锁时调用（见类注释）。 */
    private fun isBusy(): Boolean = job?.isActive == true

    /** 距上次成功检查满 [AUTO_CHECK_INTERVAL_MS] 才到期；只在持锁时调用。 */
    private fun checkDue(): Boolean {
        val lastCheck = pref.updateLastCheckAt
        return lastCheck <= 0 || clock() - lastCheck >= AUTO_CHECK_INTERVAL_MS
    }

    /** 检查失败不写 [Pref.updateLastCheckAt]，故用 [AUTO_CHECK_RETRY_INTERVAL_MS] 节流重试，避免每次 Activity 重建都打网络。 */
    private fun isRetryThrottled(): Boolean {
        if (lastAutoAttemptAt == 0L) return false
        return clock() - lastAutoAttemptAt < AUTO_CHECK_RETRY_INTERVAL_MS
    }

    /** [TimeoutCancellationException] 是 [CancellationException] 子类，必须在这里先归为失败，否则会被当成取消吞掉。 */
    private fun errorOf(e: Throwable): UpdateError = when (e) {
        is TimeoutCancellationException -> UpdateError.Network
        is UpdateException -> e.error
        else -> UpdateError.Io(e.message)
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
            } catch (e: Exception) {
                // 真正的取消必须向上传播；超时按失败处理。
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                _state.value = UpdateState.Failed(UpdateAction.CHECK, errorOf(e), manual)
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
            codeNewer && nameNewer -> UpdateState.Available(available)
            !codeNewer && !nameNewer -> UpdateState.UpToDate(currentVersionName)
            else -> {
                // versionCode 与 versionName 不一致：拒绝提示安装，否则包管理器会拒装。
                Log.w(
                    TAG,
                    "inconsistent release ${release.tagName}: codeNewer=$codeNewer nameNewer=$nameNewer",
                )
                UpdateState.Failed(UpdateAction.CHECK, UpdateError.MetadataMismatch, manual)
            }
        }
    }

    private fun failDownload(error: UpdateError, release: AvailableRelease) {
        Log.w(TAG, "update download failed: $error")
        _state.value = UpdateState.Failed(
            UpdateAction.DOWNLOAD,
            error,
            release.manual,
            release,
        )
    }
}
