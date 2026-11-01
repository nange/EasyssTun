package com.easysstun

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import io.github.nange.easyss.mobile.Mobile
import hev.htproxy.TProxyService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale


/**
 * 拥有 TUN 网卡与 native 隧道的前台服务，也是唯一的事实上报方：状态一律经 [VpnController] 发布；绝不写 [Pref.userWantsVpn]（意图归 controller），否则启动失败会静默关掉意图、下次渲染又自动重连。
 */
class EasyssVpnService : VpnService() {
    private var tunFd: ParcelFileDescriptor? = null

    private lateinit var pref: Pref

    /** 配置档仓库：随时可读，不像 [pref] 要等 startService 初始化。 */
    private val profiles: ProfileStore
        get() = ProfileStore.get(this)

    private var mobileJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val statsClient = StatsClient()
    private var notificationUpdaterJob: Job? = null
    // 启动互斥：同一时刻只允许一个 Mobile.start（AAR 会拒绝「already started」），
    // 热重启必须等上一次启动完全退出；onStartCommand 重入也由它挡住。
    @Volatile
    private var isStarting: Boolean = false
    // 拆除中标志：从请求拆除到完成期间为 true，期间第二次 disconnect 不得再起一套
    // 拆除，否则 finalizeStop 会执行两次、服务被重复收尾。
    @Volatile
    private var isStopping: Boolean = false
    // controller 在收尾期间已排好后续启动（切服务器）时置位，onDestroy 据此不把
    // 即将接替的新实例当成已消失而回收。
    @Volatile
    private var replacedByController: Boolean = false

    private val controller: VpnController
        get() = VpnController.get(this)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ACTION_DISCONNECT == intent?.action) {
            Log.i(TAG, "onStartCommand: Received ACTION_DISCONNECT.")
            stopService()
            return START_NOT_STICKY
        }

        if (intent == null) {
            // 系统重启（START_STICKY）不重放 Intent：配置一律从仓库重新读取。
            Log.i(TAG, "onStartCommand: no Intent (system restart), loading configuration from the stores.")
        } else if (intent.action != ACTION_CONNECT) {
            Log.w(TAG, "onStartCommand: unexpected action '${intent.action}', treating it as a connect request.")
        }

        return try {
            if (startService()) START_STICKY else START_NOT_STICKY
        } catch (e: Exception) {
            // 未进到启动协程就失败也必须上报，否则首页会永远停在「连接中」。
            Log.e(TAG, "onStartCommand: unexpected start failure", e)
            controller.onServiceFailed(
                e.message ?: getString(R.string.error_start_service_failed),
                profiles.active()?.id
            )
            stopSelf()
            START_NOT_STICKY
        }
    }

    private val configChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (tunFd != null && !isStopping) {
                restartService()
            } else {
                Log.d(
                        TAG,
                        "configChangeReceiver: tunFd=${tunFd?.fd}, isStopping=$isStopping. Ignoring the config change."
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ConfigChangeNotifier.register(this, configChangeReceiver)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(configChangeReceiver)
        serviceScope.cancel()
        // 系统销毁会跳过 stop 路径，必须通知 controller，否则它仍宣称隧道存在；
        // 但切换服务器时新实例已启动，不能被当作消失回收。
        if (!replacedByController) controller.onServiceDestroyed()
        Log.i(TAG, "onDestroy: service destroyed, cancelling the service scope.")
    }

    override fun onRevoke() {
        super.onRevoke()
        // 系统撤销 VPN 授权（被其他 VPN 顶替或在设置里撤销）是明确动作，
        // 持久化意图由 controller 在此清除。
        controller.onPermissionRevoked()
        stopService()
    }

    /**
     * 启动隧道：返回 true 表示启动流程已在进行或隧道已在运行；false 表示无法启动，此时已上报失败并自行停止。
     */
    fun startService(): Boolean {
        if (tunFd != null || isStarting) {
            Log.d(TAG, "startService: already running or starting (tunFd=${tunFd?.fd}, isStarting=$isStarting). Ignoring.")
            return true
        }
        if (isStopping) {
            // 拆除期间 native 栈归拆除方所有，此时启动会撞上 start/stop 竞态；controller
            // 在 Stopping 已挡住 connect，这里只是兜底，拆除结束自会上报状态。
            Log.w(TAG, "startService: teardown in progress, dropping the start request.")
            return false
        }

        pref = Pref(this)

        // 服务与 UI 同进程：活动配置档、代理模式和分应用列表直接读仓库，不经 Intent 序列化。
        val profile = profiles.active()
        if (profile == null) {
            Log.w(TAG, "startService: no active server profile, not starting.")
            // 不留无隧道无通知的已启动服务；用 startForegroundService() 的调用方须先校验配置档，
            // 平台要求 FGS 实例先 startForeground() 再停止（ServiceReceiver 已做该检查）。
            controller.onServiceFailed(getString(R.string.error_no_active_profile), null)
            stopSelf()
            return false
        }
        controller.onServiceStarting(profile.id)
        Log.i(TAG, "startService: loaded active profile '${profile.name}' (${profile.id})")

        // VPN builder 现在只构建，establish() 要等 SOCKS5 代理确认就绪：先建 TUN 会把 DNS
        // 查询和连通性检查丢进无人服务的隧道，表现为开启后首页卡 10s+。
        val builder = buildVpnBuilder()

        // 必须在建立 VPN 前写完 native 需要的配置文件；写失败要上报，不能带着过期或缺失的
        // CA/路由规则静默启动。
        val configWriter = ProfileConfigWriter(cacheDir)
        val configPaths = configWriter.writeProfileFiles(profile).getOrElse { error ->
            Log.e(TAG, "startService: could not write the profile config files", error)
            controller.onServiceFailed(getString(R.string.error_write_profile_config), profile.id)
            stopSelf()
            return false
        }
        val config = profile.buildSimpleConfig(configPaths)
        Log.i(TAG, "startService: Starting Mobile proxy - server=${config.getServer()}:${config.getServerPort()}, localPort=${config.getLocalPort()}")

        val socksPort = profile.socksPort ?: Profile.DEFAULT_SOCKS_PORT
        val proxyFile = configWriter.writeTproxyConfig(socksPort).getOrElse { error ->
            Log.e(TAG, "startService: could not write the tproxy config", error)
            controller.onServiceFailed(getString(R.string.error_write_tproxy_config), profile.id)
            stopSelf()
            return false
        }

        val channelName = "easysstun"
        initNotificationChannel(channelName)

        isStarting = true
        val startElapsed = SystemClock.elapsedRealtime()
        val startupElapsed: () -> Long = { SystemClock.elapsedRealtime() - startElapsed }
        mobileJob = serviceScope.launch {
            try {
                // libeasyss 只在 CLI 打印版本横幅，gomobile 绑定不会输出，故在此补一条供 App 内日志查看。
                logVersionBanner("profile='${profile.name}' logLevel=${profile.logLevel}")

                // 立刻进前台并显示「连接中」：启动要等代理就绪、可能耗时数秒，而 Android 要求尽快公告前台服务。
                showForeground(
                    buildNotification(channelName, profile.name, getString(R.string.notification_connecting))
                )

                // Mobile.start() 对应 Go 的 `func Start(cfg) error`：失败时 gomobile 绑定抛 Exception
                // 并带 Go 错误文本，须透传给用户，不能静默拆除。
                try {
                    Mobile.start(config)
                    Log.i(TAG, "startup: Mobile.start() done, t+${startupElapsed()}ms")
                } catch (e: CancellationException) {
                    // 启动途中用户已停止：交给外层 catch 重抛，清理归拆除路径。
                    throw e
                } catch (e: Exception) {
                    val message = e.message ?: e.toString()
                    Log.e(TAG, "startup: Mobile.start() failed: $message", e)
                    finishStartupFailure(message, profile.id)
                    return@launch
                }

                coroutineContext.ensureActive()
                val socksReady = waitForSocksReady(socksPort, STARTUP_READY_TIMEOUT_MS)
                Log.i(TAG, "startup: socks ready=$socksReady, t+${startupElapsed()}ms")
                if (!socksReady) {
                    Log.e(TAG, "startup: SOCKS5 proxy not ready within ${STARTUP_READY_TIMEOUT_MS}ms, aborting")
                    finishStartupFailure(getString(R.string.error_socks_not_ready), profile.id)
                    return@launch
                }
                coroutineContext.ensureActive()

                // 只有上面的 SOCKS5 就绪检查放行 VPN：此时 native 核心已起，不会有流量掉进死 TUN。
                val newTunFd = builder.establish()
                if (newTunFd == null) {
                    Log.w(TAG, "startService: Failed to establish new tunFd, it's null.")
                    finishStartupFailure(getString(R.string.error_establish_tun), profile.id)
                    return@launch
                }
                tunFd = newTunFd
                Log.i(TAG, "startService: Successfully established new tunFd: ${newTunFd.fd}, t+${startupElapsed()}ms")

                // 拆除方会等本协程结束后才停 native 栈，而下面几步不可挂起，动 TProxy 前必须再查一次取消。
                coroutineContext.ensureActive()

                val started = TProxyService.TProxyStartService(proxyFile.absolutePath, newTunFd.fd)
                if (!started) {
                    Log.w(TAG, "startService: TProxyStartService reported failure")
                    finishStartupFailure(getString(R.string.error_tproxy_start), profile.id)
                    return@launch
                }

                createNotification(channelName, profile.name)
                startNotificationUpdater(channelName, profile.name, profile.statsUrl())
                Log.i(TAG, "startup: service fully started, t+${startupElapsed()}ms")
                // 此时 SOCKS5 已在服务、TProxy 已启动，本地代理可用，才能上报运行中。
                controller.onServiceRunning(profile.id)
            } catch (e: CancellationException) {
                // 启动途中用户已停止：清理归拆除路径。
                Log.d(TAG, "startup: cancelled, t+${startupElapsed()}ms")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "startup: failed", e)
                finishStartupFailure(
                    e.message ?: getString(R.string.error_start_service_failed),
                    profile.id
                )
            } finally {
                isStarting = false
            }
        }
        return true
    }

    private fun buildVpnBuilder(): Builder {
        var session = String()
        val builder = Builder()

        builder.setMetered(false)
        builder.setBlocking(false)
        builder.setMtu(8500)

        builder.addAddress("198.18.0.1", 32)
        builder.addDnsServer("8.8.8.8")

        resources.getStringArray(R.array.bypass_private_route).forEach {
            val parts = it.split('/', limit = 2)
            builder.addRoute(parts[0], parts[1].toInt())
        }

        session += "IPv4/v6"

        builder.addAddress("2001:0db8:0:f101::1", 64)

        val proxyMode = pref.proxyMode
        val apps = AppSelectionStore(pref).selected(proxyMode)
        Log.i(TAG, "Per-app routing: mode=$proxyMode, ${apps.size} selected apps")

        // VpnService 禁止在同一 builder 混用 allow/disallow：bypass 模式必须显式 disallow 自身，
        // 否则出站拨号会被路由回 TUN 形成自环；proxy-only 模式只需把自身从允许列表剔除。
        val selfName = applicationContext.packageName
        if (proxyMode == Pref.PROXY_MODE_PROXY_ONLY) {
            val allowedApps = apps.filterNot { it == selfName }
            if (allowedApps.size != apps.size) {
                Log.w(TAG, "Removed easyss self from allowed apps to avoid a routing loop: $selfName")
            }
            for (appName in allowedApps) {
                try {
                    builder.addAllowedApplication(appName)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "App not found for VPN allow: $appName", e)
                }
            }
            session += "/per-App(allow)"
        } else {
            for (appName in apps) {
                try {
                    builder.addDisallowedApplication(appName)
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "App not found for VPN bypass: $appName", e)
                }
            }
            try {
                builder.addDisallowedApplication(selfName)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Self app not found for VPN bypass: $selfName", e)
            }
            session += "/per-App"
        }
        builder.setSession(session)
        return builder
    }

    /**
     * 启动失败兜底清理：停掉已起来的 native 部分、关 TUN fd、上报失败后停止服务；每步都容错，否则清理异常会吞掉失败上报。
     */
    private fun finishStartupFailure(message: String, profileId: String) {
        Log.w(TAG, "finishStartupFailure: $message")
        runCatching { TProxyService.TProxyStopService() }
            .onFailure { Log.e(TAG, "finishStartupFailure: TProxyStopService failed: ${it.message}", it) }
        runCatching { Mobile.stop() }
            .onFailure { Log.e(TAG, "finishStartupFailure: Mobile.stop failed: ${it.message}", it) }
        try {
            tunFd?.close()
        } catch (e: IOException) {
            Log.e(TAG, "finishStartupFailure: exception closing tunFd: ${e.message}", e)
        }
        tunFd = null
        notificationUpdaterJob?.cancel()
        notificationUpdaterJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        // 先上报再停止：进程若在 stopSelf() 后立刻被杀，失败信息就丢了。
        controller.onServiceFailed(message, profileId)
        stopSelf()
    }

    /**
     * 轮询本地 SOCKS5 直到完成一次真实握手（greeting→方法选择回复）或 [timeoutMs] 超时；只做 TCP 连接不够，监听端口可能已绑定但 accept 循环尚未注册。
     */
    internal fun waitForSocksReady(
        port: Int,
        timeoutMs: Long,
        nowMillis: () -> Long = SystemClock::elapsedRealtime,
        probe: (Int) -> Boolean = ::probeSocksAccept,
    ): Boolean {
        val deadline = nowMillis() + timeoutMs
        while (nowMillis() < deadline) {
            if (probe(port)) return true
            Thread.sleep(100)
        }
        return probe(port)
    }

    internal fun probeSocksAccept(port: Int): Boolean {
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress("127.0.0.1", port), 100)
                s.soTimeout = 200
                s.outputStream.write(byteArrayOf(0x05, 0x01, 0x00))
                val reply = ByteArray(2)
                val n = s.inputStream.read(reply)
                n == 2 && reply[0] == 0x05.toByte() && reply[1] == 0x00.toByte()
            }
        } catch (e: Exception) {
            false
        }
    }

    fun stopService() {
        if (isStopping) {
            Log.d(TAG, "stopService: teardown already in progress, ignoring.")
            return
        }
        val startupActive = mobileJob?.isActive == true
        if (tunFd == null && !startupActive && !isStarting) {
            // 无事可拆也要收尾：否则残留的服务实例不会 stopSelf()，变成空转服务。
            Log.d(TAG, "stopService: nothing to tear down, finalizing the idle instance.")
            isStopping = true
            finalizeStop()
            return
        }
        Log.i(TAG, "stopService() called. Initiating shutdown sequence. Current tunFd: ${tunFd?.fd}")
        isStopping = true
        shutdownTunnel { finalizeStop() }
    }

    /**
     * 按最新持久化配置重建隧道：VPN 运行中收到 [ConfigChangeNotifier] 变更时调用，服务继续运行而不是永久停止。
     */
    private fun restartService() {
        if (isStopping) {
            Log.d(TAG, "restartService: teardown already in progress, ignoring broadcast.")
            return
        }
        Log.i(TAG, "restartService() called. Tearing down tunnel to reload latest prefs.")
        isStopping = true
        // 先把重启窗口上报为 Connecting：本地代理即将不可用，TunnelState.ready 必须立刻落下，
        // 首页也要停止轮询正在拆除的 stats 端点。
        profiles.active()?.let { controller.onServiceStarting(it.id) }
        shutdownTunnel {
            if (!pref.userWantsVpn) {
                // 拆除期间用户按了停止。
                Log.w(TAG, "performRestart: user no longer wants the VPN. Finalizing stop instead.")
                finalizeStop()
                return@shutdownTunnel
            }
            Log.i(TAG, "performRestart: Reloading latest prefs and re-establishing tunnel.")
            if (!startService()) {
                Log.w(TAG, "performRestart: startService could not re-establish the tunnel; failure reported.")
            }
        }
    }

    /**
     * 拆除顺序：先取消并 join 启动协程，再停 native 栈，最后在主线程关 TUN fd 并回调 [onShutdownComplete]；`Mobile.start()` 是阻塞 native 调用，只做协作式取消就 `Mobile.stop()` 会与 libeasyss 内部的 start/stop 竞态。
     */
    private fun shutdownTunnel(onShutdownComplete: () -> Unit) {
        notificationUpdaterJob?.cancel()
        notificationUpdaterJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)

        serviceScope.launch {
            try {
                // 1. 先取消并 join 启动协程，再动 native 栈。
                val job = mobileJob
                if (job != null && job.isActive) {
                    Log.d(TAG, "shutdownTunnel: cancelling and joining mobileJob.")
                    job.cancel()
                    val finished = withTimeoutOrNull(STARTUP_JOIN_TIMEOUT_MS) { job.join() }
                    if (finished == null) {
                        Log.w(
                            TAG,
                            "shutdownTunnel: startup did not finish within ${STARTUP_JOIN_TIMEOUT_MS}ms, stopping anyway."
                        )
                    }
                }

                // 2. 先停 TProxy，再停 Mobile。
                runCatching { TProxyService.TProxyStopService() }
                    .onFailure { Log.e(TAG, "shutdownTunnel: TProxyStopService failed: ${it.message}", it) }
                runCatching { Mobile.stop() }
                    .onFailure { Log.e(TAG, "shutdownTunnel: Mobile.stop failed: ${it.message}", it) }
            } catch (e: Exception) {
                Log.e(TAG, "shutdownTunnel: unhandled exception during native cleanup: ${e.message}", e)
            } finally {
                withContext(Dispatchers.Main.immediate) {
                    Log.d(TAG, "shutdownTunnel: Closing tunFd: ${tunFd?.fd}.")
                    try {
                        tunFd?.close()
                    } catch (e: IOException) {
                        Log.e(TAG, "shutdownTunnel: exception closing tunFd: ${e.message}", e)
                    }
                    tunFd = null
                    isStopping = false
                    onShutdownComplete()
                }
            }
        }
    }

    /**
     * 最终收尾：移除通知、关闭 TUN fd、停掉本服务实例；后续是 Idle 还是切换服务器的后半程由 controller 决定。
     */
    private fun finalizeStop() {
        notificationUpdaterJob?.cancel()
        notificationUpdaterJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        try {
            tunFd?.close()
        } catch (e: IOException) {
            Log.e(TAG, "finalizeStop: exception closing tunFd: ${e.message}", e)
        }
        tunFd = null
        isStopping = false
        Log.d(TAG, "finalizeStop: calling stopSelf().")
        stopSelf()
        // 放在 stopSelf() 之后：否则切换服务器时的后续启动会被本次停止吞掉。
        replacedByController = controller.onServiceStopped()
    }

    private fun createNotification(channelName: String, profileName: String) {
        val notify = buildNotification(
            channelName,
            profileName,
            getString(R.string.notification_stats_unavailable)
        )
        showForeground(notify)
    }

    private fun showForeground(notify: Notification) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notify)
        } else {
            startForeground(NOTIFICATION_ID, notify, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }

    private fun buildNotification(channelName: String, title: String, contentText: String): Notification {
        val i = Intent(this, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelName)
            .setContentTitle(title)
            .setContentText(contentText)
            .setSilent(true)
            .setOngoing(true)
            .setSmallIcon(R.drawable.ic_launcher_foreground_big)
            .setContentIntent(pi)
            .build()
    }

    private fun startNotificationUpdater(channelName: String, profileName: String, statsUrl: String) {
        notificationUpdaterJob = serviceScope.launch {
            delay(2_000) // 等 stats 端点就绪
            while (isActive) {
                val contentText = fetchStatsContentText(statsUrl)
                    ?: getString(R.string.notification_stats_unavailable)
                val notify = buildNotification(channelName, profileName, contentText)
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                    .notify(NOTIFICATION_ID, notify)
                delay(2_000)
            }
        }
    }

    private suspend fun fetchStatsContentText(statsUrl: String): String? {
        val snapshot = statsClient.fetch(statsUrl) ?: return null
        val rttText = if (snapshot.avgRttMs > 0) {
            String.format(Locale.ROOT, "%.1fms", snapshot.avgRttMs)
        } else {
            "--"
        }
        val speedText = snapshot.downloadSpeed.ifBlank { "--" }
        return getString(R.string.notification_stats_text, rttText, speedText)
    }

    private fun initNotificationChannel(channelName: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val name: CharSequence = getString(R.string.app_name)
        val channel =
            NotificationChannel(channelName, name, NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)

    }

    companion object {
        // 启动等待 SOCKS5 完成真实握手的上限，超时即放弃（见 waitForSocksReady）。
        const val STARTUP_READY_TIMEOUT_MS = 30_000L

        // 拆除等待启动协程结束的上限，超时后照样停 native 栈（见 shutdownTunnel）。
        const val STARTUP_JOIN_TIMEOUT_MS = 10_000L

        const val ACTION_CONNECT = "CONNECT"
        const val ACTION_DISCONNECT = "DISCONNECT"
        const val NOTIFICATION_ID = 1
        private const val TAG = LogTags.SERVICE

        /**
         * 只带 action 的启动 Intent：配置绝不走 Intent，服务自己从仓库读；调用方只差在 [Context.startService]（UI）与 [Context.startForegroundService]（[ServiceReceiver]）。
         */
        fun connectIntent(context: Context): Intent =
            Intent(context, EasyssVpnService::class.java).setAction(ACTION_CONNECT)

        fun disconnectIntent(context: Context): Intent =
            Intent(context, EasyssVpnService::class.java).setAction(ACTION_DISCONNECT)
    }
}
