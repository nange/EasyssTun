package hev.htproxy

/**
 * JNI bridge shim for the prebuilt hev-socks5-tunnel AAR.
 *
 * The AAR registers its natives to this exact class at load time
 * (JNI_OnLoad -> FindClass("hev/htproxy/TProxyService") + RegisterNatives).
 * This is a fixed contract: do NOT rename/repackage this class or change the
 * native method names/signatures when upgrading the AAR.
 *
 * Business code should call through com.easysstun.TProxyService, which
 * forwards to this shim.
 */
object TProxyService {
    external fun TProxyStartService(config_path: String, fd: Int): Boolean
    external fun TProxyStopService(): Boolean
    external fun TProxyIsRunning(): Boolean
    external fun TProxyGetStats(): LongArray

    init {
        System.loadLibrary("hev-socks5-tunnel")
    }
}
