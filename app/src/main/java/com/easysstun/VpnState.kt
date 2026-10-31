package com.easysstun

/**
 * The one and only description of what the VPN is doing.
 *
 * Before this type existed the home screen reconstructed the tunnel state from
 * `Pref.userWantsVpn` (the user's *intent*, which outlives the tunnel),
 * three ad-hoc broadcasts, and a handful of fragment fields
 * (`isConnecting` / `isSwitchingServer` / `pendingServerProfileId` /
 * `statsServiceReady`). Those sources could disagree — most visibly after a
 * rotation during a server switch, when the pending target lived only in the
 * fragment.
 *
 * [VpnController] owns the single [kotlinx.coroutines.flow.StateFlow] of this
 * type; the UI only renders it and the service only reports facts into it.
 */
internal sealed interface VpnState {

    /** No tunnel, no startup in flight; the user has not asked for one (or it failed). */
    data object Idle : VpnState

    /** A tunnel is being built for [profileId]. */
    data class Connecting(val profileId: String?) : VpnState

    /** The tunnel is up and serving traffic for [profileId]. */
    data class Running(val profileId: String?) : VpnState

    /** The tunnel is being replaced: torn down for [fromProfileId], rebuilt for [toProfileId]. */
    data class Switching(val fromProfileId: String?, val toProfileId: String) : VpnState

    /** The tunnel is being torn down after the user asked to stop. */
    data object Stopping : VpnState

    /**
     * The last start attempt failed with [message] (native error text, or a
     * localised precondition message). The service is already down; the state
     * is kept so the UI can surface the error exactly once instead of silently
     * falling back to "stopped".
     */
    data class Failed(val message: String, val profileId: String?) : VpnState

    /**
     * True while a tunnel is coming up, is up, or is being replaced. Used to
     * decide whether the spinner/commands must be locked; it deliberately does
     * not include [Stopping] (nothing is being served any more).
     */
    val isEngaged: Boolean
        get() = this is Connecting || this is Running || this is Switching
}
