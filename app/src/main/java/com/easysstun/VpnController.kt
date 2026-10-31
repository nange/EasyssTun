package com.easysstun

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Outcome of [VpnController.connect]. */
internal sealed interface ConnectRequest {
    /** The service start was issued; the state is [VpnState.Connecting]. */
    data object Started : ConnectRequest

    /** The system must grant the VPN permission before the tunnel can start. */
    data class NeedsPermission(val intent: Intent) : ConnectRequest

    /** A tunnel is already coming up, running, being switched or stopping. */
    data object AlreadyActive : ConnectRequest

    /** No (matching) server profile is configured, so there is nothing to connect to. */
    data object NoProfile : ConnectRequest
}

/**
 * Process-wide owner of the VPN lifecycle.
 *
 * This is the single writer of [VpnState]; the UI only reads [state] and issues
 * the three commands [connect] / [disconnect] / [switchTo]. The service reports
 * facts back through the `onService*` callbacks. Keeping the state here — and
 * not in the fragment — is what makes a rotation during a server switch keep
 * showing "switching" instead of dropping back to a stale UI.
 *
 * [Pref.userWantsVpn] is the persisted *intent* and is only written on an
 * explicit user action (start, stop, denied permission) or a system revocation;
 * the service never writes it. A failed start therefore stays visible as
 * [VpnState.Failed] instead of silently flipping the intent off. [VpnState] is
 * process-local, so a failure does not outlive the process: after a restart the
 * state is [VpnState.Idle] again and [resume] may retry the connection the user
 * still asked for.
 *
 * A fresh process starts [VpnState.Idle] even when [Pref.userWantsVpn] is true:
 * the tunnel lives in this very process, so it cannot survive a process death.
 * The intent is what lets the system restart the service (`START_STICKY`, or
 * [ServiceReceiver] on boot); once that happens the service drives
 * Connecting → Running through [onServiceStarting] / [onServiceRunning].
 *
 * All mutators are `@Synchronized`: the service reports from its IO startup
 * coroutine while the UI issues commands from the main thread.
 */
internal class VpnController private constructor(
    private val appContext: Context,
    private val pref: Pref,
    private val profiles: ProfileStore,
) {

    private val _state = MutableStateFlow<VpnState>(VpnState.Idle)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    /** Set once the current [VpnState.Failed] has been shown to the user. */
    private var failureConsumed = false

    /** True once a start Intent was issued for the current [VpnState.Connecting]. */
    private var startIssued = false

    // ── Commands (UI → controller) ──────────────────────────────

    /**
     * Starts the tunnel for [profileId] (or the active profile when null).
     * Idempotent: a second request while the tunnel is engaged is ignored, so a
     * re-render can never issue a duplicate start.
     */
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

    /** Result of the system VPN permission dialog launched for [ConnectRequest.NeedsPermission]. */
    @Synchronized
    fun onPermissionResult(granted: Boolean) {
        val current = _state.value
        if (current !is VpnState.Connecting) {
            Log.d(TAG, "onPermissionResult(granted=$granted) ignored in state $current")
            return
        }
        if (!granted) {
            // The user explicitly declined: drop the intent instead of leaving a
            // "connecting" UI behind.
            Log.i(TAG, "onPermissionResult: permission denied, returning to Idle")
            pref.userWantsVpn = false
            setState(VpnState.Idle)
            return
        }
        startServiceNow(current.profileId)
    }

    /**
     * Recovery hook for the screen, called whenever it becomes visible:
     *
     *  - a [ConnectRequest.NeedsPermission] whose result never came back (the
     *    requesting activity was destroyed while the system dialog was up) would
     *    otherwise leave the UI in [VpnState.Connecting] forever;
     *  - a persisted intent ([Pref.userWantsVpn]) with no tunnel and no startup
     *    in flight means the process was rebuilt without the service coming
     *    back (force-stop, a system kill that skipped the `START_STICKY`
     *    restart). That is the one place the intent is turned back into a
     *    connection.
     *
     * [VpnState.Failed] is deliberately not retried: after a failure the next
     * attempt is the user's explicit call.
     */
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

    /** Stops the tunnel and clears the persisted intent. */
    @Synchronized
    fun disconnect() {
        pref.userWantsVpn = false
        when (val current = _state.value) {
            is VpnState.Running, is VpnState.Connecting, is VpnState.Switching -> {
                setState(VpnState.Stopping)
                sendDisconnectIntent()
            }
            is VpnState.Failed -> setState(VpnState.Idle)
            // Nothing to tear down, but a residual service instance may still be
            // alive (e.g. a start that aborted before reporting); the disconnect
            // intent makes it finalize itself.
            VpnState.Idle, VpnState.Stopping -> sendDisconnectIntent()
        }
    }

    /**
     * Selects [profileId]. While a tunnel is engaged this tears the current one
     * down and rebuilds it for the new profile; while idle it is a plain
     * configuration change.
     */
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
            // Selecting a server while the tunnel is down is not a VPN command.
            if (_state.value is VpnState.Failed) setState(VpnState.Idle)
            return
        }
        Log.i(TAG, "switchTo: $from -> ${target.id}")
        setState(VpnState.Switching(fromProfileId = from, toProfileId = target.id))
        sendDisconnectIntent()
    }

    // ── Service facts (service → controller) ────────────────────

    /** The service is about to build a tunnel for [profileId]. */
    @Synchronized
    internal fun onServiceStarting(profileId: String) {
        when (_state.value) {
            is VpnState.Stopping, is VpnState.Switching -> Log.d(
                TAG,
                "onServiceStarting($profileId) ignored in state ${_state.value}"
            )
            else -> {
                setState(VpnState.Connecting(profileId))
                // The service is already starting; a screen resume must not
                // re-issue the start.
                startIssued = true
            }
        }
    }

    /** The tunnel is fully up and serving traffic for [profileId]. */
    @Synchronized
    internal fun onServiceRunning(profileId: String) {
        when (_state.value) {
            is VpnState.Stopping, is VpnState.Switching -> {
                // The user stopped (or started replacing) the tunnel while the
                // last startup step was in flight.
                Log.w(TAG, "onServiceRunning($profileId) ignored in state ${_state.value}")
            }
            else -> setState(VpnState.Running(profileId))
        }
    }

    /**
     * The service finished tearing down (it is already stopping itself). This is
     * where the second half of a server switch happens: the new profile is
     * started only after the old tunnel is completely gone.
     *
     * @return true when a follow-up start was issued, i.e. the service instance
     *   that is stopping is immediately replaced. The service uses this to tell
     *   a planned replacement from a system-initiated destroy.
     */
    @Synchronized
    internal fun onServiceStopped(): Boolean {
        when (val current = _state.value) {
            is VpnState.Switching -> {
                val target = current.toProfileId
                if (profiles.find(target) == null) {
                    // The target was deleted while the switch was in flight.
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
            // Keep the failure visible: the UI still has to show its dialog.
            is VpnState.Failed -> Unit
            else -> setState(VpnState.Idle)
        }
        return false
    }

    /** The service could not start (or lost) the tunnel; [message] is user-facing. */
    @Synchronized
    internal fun onServiceFailed(message: String, profileId: String?) {
        Log.w(TAG, "service failed (profile=$profileId): $message")
        setState(VpnState.Failed(message, profileId))
    }

    /**
     * The service instance is gone without having reported a stop. This happens
     * when the system takes the service away mid-startup/mid-teardown, or when
     * the teardown coroutine was cancelled by `onDestroy` before it could
     * finalize. Falling back to [VpnState.Idle] is what keeps the home screen
     * from freezing on "connecting"/"stopping" with a disabled button.
     *
     * A service that is being replaced by a server switch does not call this
     * (see [onServiceStopped]); a failed start has already reported
     * [VpnState.Failed] and keeps it.
     */
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

    /** The system revoked the VPN permission (another VPN took over, or the user revoked it). */
    @Synchronized
    fun onPermissionRevoked() {
        Log.w(TAG, "VPN permission revoked by the system")
        pref.userWantsVpn = false
        if (_state.value.isEngaged) setState(VpnState.Stopping)
    }

    /**
     * Returns the current failure message exactly once, so a re-render or a
     * rotation cannot show the same dialog twice.
     */
    @Synchronized
    fun consumeFailure(): String? {
        val failure = _state.value as? VpnState.Failed ?: return null
        if (failureConsumed) return null
        failureConsumed = true
        return failure.message
    }

    // ── Internals ───────────────────────────────────────────────

    private fun resolveProfile(profileId: String?): Profile? =
        if (profileId == null) profiles.active() else profiles.find(profileId)

    private fun setState(next: VpnState) {
        val previous = _state.value
        if (previous == next) return
        // Every transition invalidates the one-shot failure latch. A repeated
        // report of the very same failure is a no-op above, so the dialog is
        // never shown twice for one failure.
        failureConsumed = false
        // A new Connecting phase has not issued its start Intent yet.
        if (previous !is VpnState.Connecting || next !is VpnState.Connecting) startIssued = false
        Log.i(TAG, "vpn state: $previous -> $next")
        // The local proxy is usable exactly while the tunnel is running; this is
        // the readiness signal the updater waits for.
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
        private const val TAG = "VpnController"

        @Volatile
        private var instance: VpnController? = null

        /** Process-wide instance; the VPN service and the UI share one state. */
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

        /** Drops the singleton so tests start from a clean [VpnState.Idle]. */
        internal fun resetForTests() {
            synchronized(this) { instance = null }
        }
    }
}
