package com.easysstun

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal sealed interface ConnectRequest {
    /** 启动已下发，此时状态为 [VpnState.Connecting]。 */
    data object Started : ConnectRequest

    /** 需先经系统授权；结果回传给 [VpnController.onPermissionResult]。 */
    data class NeedsPermission(val intent: Intent) : ConnectRequest

    /** 隧道已在进行中（含 Stopping）：命令被忽略。 */
    data object AlreadyActive : ConnectRequest

    data object NoProfile : ConnectRequest
}

/**
 * [VpnState] 的唯一写入方：状态只在进程内有效，[Pref.userWantsVpn] 仅是用户意图（服务从不写）；所有改状态的方法都 `@Synchronized`，因为服务从 IO 协程上报、UI 从主线程下命令。
 */
internal class VpnController private constructor(
    private val appContext: Context,
    private val pref: Pref,
    private val profiles: ProfileStore,
) {

    private val _state = MutableStateFlow<VpnState>(VpnState.Idle)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    /** 当前 [VpnState.Failed] 是否已展示；每次状态迁移都会复位。 */
    private var failureConsumed = false

    /** 当前 [VpnState.Connecting] 是否已下发启动 Intent；resume 据此避免重复启动。 */
    private var startIssued = false

    // ── 命令（UI → 控制器） ──────────────────────────────

    /** 幂等：隧道已在进行时重复调用被忽略，重复渲染不会重复启动；[profileId] 为 null 时用当前配置档。 */
    @Synchronized
    fun connect(profileId: String? = null): ConnectRequest {
        val current = _state.value
        if (current.isEngaged || current is VpnState.Stopping) {
            Log.d(TAG, "connect($profileId) ignored in state $current")
            return ConnectRequest.AlreadyActive
        }
        val profile = resolveProfile(profileId)
        if (profile == null) {
            Log.w(TAG, "connect($profileId) without a configured server profile")
            return ConnectRequest.NoProfile
        }
        if (profileId != null) profiles.select(profile.id)

        pref.userWantsVpn = true
        setState(VpnState.Connecting(profile.id))

        val permissionIntent = VpnService.prepare(appContext)
        if (permissionIntent != null) {
            Log.i(TAG, "connect: VPN permission is required before starting")
            return ConnectRequest.NeedsPermission(permissionIntent)
        }
        startServiceNow(profile.id)
        return ConnectRequest.Started
    }

    /** 系统 VPN 授权结果；当前不是 Connecting 时忽略，避免覆盖更新的状态。 */
    @Synchronized
    fun onPermissionResult(granted: Boolean) {
        val current = _state.value
        if (current !is VpnState.Connecting) {
            Log.d(TAG, "onPermissionResult(granted=$granted) ignored in state $current")
            return
        }
        if (!granted) {
            // 用户明确拒绝：清掉意图，否则 UI 会停在「连接中」。
            Log.i(TAG, "onPermissionResult: permission denied, returning to Idle")
            pref.userWantsVpn = false
            setState(VpnState.Idle)
            return
        }
        startServiceNow(current.profileId)
    }

    /** 屏幕可见时的恢复钩子：补发丢失的授权结果（否则 UI 永远停在 [VpnState.Connecting]），并把持久化意图还原成连接；[VpnState.Failed] 刻意不重试。 */
    @Synchronized
    fun resume(): ConnectRequest {
        when (val current = _state.value) {
            is VpnState.Connecting -> {
                if (startIssued) return ConnectRequest.AlreadyActive
                Log.i(TAG, "resume: no start was issued yet, re-checking the VPN permission")
                val permissionIntent = VpnService.prepare(appContext)
                if (permissionIntent != null) return ConnectRequest.NeedsPermission(permissionIntent)
                startServiceNow(current.profileId)
                return ConnectRequest.Started
            }
            VpnState.Idle -> {
                if (!pref.userWantsVpn) return ConnectRequest.AlreadyActive
                Log.i(TAG, "resume: the user wants the VPN but this process has no tunnel")
                return connect()
            }
            else -> return ConnectRequest.AlreadyActive
        }
    }

    @Synchronized
    fun disconnect() {
        pref.userWantsVpn = false
        when (val current = _state.value) {
            is VpnState.Running, is VpnState.Connecting, is VpnState.Switching -> {
                setState(VpnState.Stopping)
                sendDisconnectIntent()
            }
            is VpnState.Failed -> setState(VpnState.Idle)
            // 无隧道可拆，但可能有残留服务实例（启动中途放弃）；仍发断开意图让它自清。
            VpnState.Idle, VpnState.Stopping -> sendDisconnectIntent()
        }
    }

    /** 选中 [profileId]：隧道进行中先拆旧再建新，空闲时只是改配置。 */
    @Synchronized
    fun switchTo(profileId: String) {
        val target = profiles.find(profileId)
        if (target == null) {
            Log.w(TAG, "switchTo($profileId): unknown profile, ignoring")
            return
        }
        val from = profiles.active()?.id
        if (from == target.id) {
            Log.d(TAG, "switchTo($profileId): already the active profile, ignoring")
            return
        }
        val engaged = _state.value.isEngaged
        profiles.select(target.id)
        if (!engaged) {
            // 隧道未运行时选服务器只是改配置；顺带把 Failed 复位，避免残留失败态。
            if (_state.value is VpnState.Failed) setState(VpnState.Idle)
            return
        }
        Log.i(TAG, "switchTo: $from -> ${target.id}")
        setState(VpnState.Switching(fromProfileId = from, toProfileId = target.id))
        sendDisconnectIntent()
    }

    // ── 服务上报的事实（服务 → 控制器） ────────────────────

    /** 服务即将为 [profileId] 建隧道；Stopping/Switching 期间的上报被忽略，避免把正在拆的状态拉回 Connecting。 */
    @Synchronized
    internal fun onServiceStarting(profileId: String) {
        when (_state.value) {
            is VpnState.Stopping, is VpnState.Switching -> Log.d(
                TAG,
                "onServiceStarting($profileId) ignored in state ${_state.value}"
            )
            else -> {
                setState(VpnState.Connecting(profileId))
                // 服务已在启动：置位后 resume 不会再下发一次启动。
                startIssued = true
            }
        }
    }

    /** 隧道已完全可用；Stopping/Switching 期间到达的是上一次启动的迟到上报，忽略。 */
    @Synchronized
    internal fun onServiceRunning(profileId: String) {
        when (_state.value) {
            is VpnState.Stopping, is VpnState.Switching -> {
                // 最后一次启动还在途中时用户已停止或切换：这是迟到的上报，丢弃。
                Log.w(TAG, "onServiceRunning($profileId) ignored in state ${_state.value}")
            }
            else -> setState(VpnState.Running(profileId))
        }
    }

    /** 服务已拆完并在自停。切换的第二段在此：旧隧道彻底消失后才启动新配置档；返回 true 表示已补发启动，服务据此区分计划内替换与系统销毁。 */
    @Synchronized
    internal fun onServiceStopped(): Boolean {
        when (val current = _state.value) {
            is VpnState.Switching -> {
                val target = current.toProfileId
                if (profiles.find(target) == null) {
                    // 目标配置档在切换途中被删：放弃切换，回落 Idle。
                    Log.w(
                        TAG,
                        "switch ${current.fromProfileId} -> $target abandoned: target no longer exists"
                    )
                    setState(VpnState.Idle)
                    return false
                }
                Log.i(TAG, "switch ${current.fromProfileId} -> $target: old tunnel gone, starting the new one")
                profiles.select(target)
                setState(VpnState.Connecting(target))
                startServiceNow(target)
                return true
            }
            // 保留失败态：UI 还要弹错误对话框，不能被 Idle 覆盖。
            is VpnState.Failed -> Unit
            else -> setState(VpnState.Idle)
        }
        return false
    }

    /** 服务启动失败或隧道丢失；[message] 是可直接展示给用户的文案。 */
    @Synchronized
    internal fun onServiceFailed(message: String, profileId: String?) {
        Log.w(TAG, "service failed (profile=$profileId): $message")
        setState(VpnState.Failed(message, profileId))
    }

    /** 服务实例消失却没上报停止（系统中途回收，或 onDestroy 取消了收尾协程）：回落到 Idle，否则首页会卡在连接中/停止中且按钮禁用。切换替换与已失败的启动不会走到这里。 */
    @Synchronized
    internal fun onServiceDestroyed() {
        when (val current = _state.value) {
            is VpnState.Running, is VpnState.Connecting, VpnState.Stopping -> {
                Log.w(TAG, "service destroyed in state $current, returning to Idle")
                setState(VpnState.Idle)
            }
            else -> Unit
        }
    }

    /** 系统收回 VPN 授权（其他 VPN 接管或用户撤销）：清意图，进行中的隧道转入 Stopping。 */
    @Synchronized
    fun onPermissionRevoked() {
        Log.w(TAG, "VPN permission revoked by the system")
        pref.userWantsVpn = false
        if (_state.value.isEngaged) setState(VpnState.Stopping)
    }

    /** 失败文案只返回一次：重复渲染或旋转不会重复弹框。 */
    @Synchronized
    fun consumeFailure(): String? {
        val failure = _state.value as? VpnState.Failed ?: return null
        if (failureConsumed) return null
        failureConsumed = true
        return failure.message
    }

    // ── 内部实现 ───────────────────────────────────────────────

    private fun resolveProfile(profileId: String?): Profile? =
        if (profileId == null) profiles.active() else profiles.find(profileId)

    private fun setState(next: VpnState) {
        val previous = _state.value
        if (previous == next) return
        // 任何状态迁移都复位一次性失败锁存；同一失败的重复上报被上面的相等判断挡掉，
        // 因此一次失败只弹一次框。
        failureConsumed = false
        // 新的 Connecting 阶段尚未下发启动 Intent。
        if (previous !is VpnState.Connecting || next !is VpnState.Connecting) startIssued = false
        Log.i(TAG, "vpn state: $previous -> $next")
        // 本地代理只在 Running 时可用：这是更新检查等待的就绪信号。
        TunnelState.update(next is VpnState.Running)
        _state.value = next
    }

    private fun startServiceNow(profileId: String?) {
        startIssued = true
        try {
            appContext.startService(EasyssVpnService.connectIntent(appContext))
        } catch (e: Exception) {
            Log.e(TAG, "failed to start the VPN service", e)
            setState(
                VpnState.Failed(
                    appContext.getString(R.string.error_start_service_failed),
                    profileId
                )
            )
        }
    }

    private fun sendDisconnectIntent() {
        try {
            appContext.startService(EasyssVpnService.disconnectIntent(appContext))
        } catch (e: Exception) {
            Log.e(TAG, "failed to stop the VPN service", e)
            setState(VpnState.Idle)
        }
    }

    companion object {
        private const val TAG = LogTags.VPN_CONTROLLER

        @Volatile
        private var instance: VpnController? = null

        /** 进程级单例：VPN 服务与 UI 共享同一份状态。 */
        fun get(context: Context): VpnController {
            instance?.let { return it }
            return synchronized(this) {
                instance ?: VpnController(
                    appContext = context.applicationContext,
                    pref = Pref(context.applicationContext),
                    profiles = ProfileStore.get(context.applicationContext),
                ).also { instance = it }
            }
        }

        /** 仅供测试：丢弃单例，让下一个用例从 [VpnState.Idle] 开始。 */
        internal fun resetForTests() {
            synchronized(this) { instance = null }
        }
    }
}
