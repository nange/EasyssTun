package com.easysstun

/** VPN 在做什么的唯一描述；[VpnController] 持有它的唯一 [kotlinx.coroutines.flow.StateFlow]，UI 只渲染、服务只上报事实。 */
internal sealed interface VpnState {

    /** 无隧道、无启动在途；进程重建后即使 [Pref.userWantsVpn] 为 true 也先回到这里。 */
    data object Idle : VpnState

    /** 正在为 [profileId] 建隧道；此时可能仍在等系统授权。 */
    data class Connecting(val profileId: String?) : VpnState

    /** 隧道已为 [profileId] 建成并转发流量；本地 stats 端点此时才可用。 */
    data class Running(val profileId: String?) : VpnState

    /** 隧道正在替换：先拆 [fromProfileId]，再建 [toProfileId]。 */
    data class Switching(val fromProfileId: String?, val toProfileId: String) : VpnState

    /** 正在拆除；期间到达的启动/运行上报都会被丢弃。 */
    data object Stopping : VpnState

    /** 上次启动失败（[message] 已可直接展示）；服务已停，保留此态以便 UI 只提示一次。 */
    data class Failed(val message: String, val profileId: String?) : VpnState

    /** 隧道在启动、运行或替换中（用于锁定下拉框与命令）；刻意不含 [Stopping]，此时已无流量在服务。 */
    val isEngaged: Boolean
        get() = this is Connecting || this is Running || this is Switching
}
