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
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale


/**
 * Foreground service that owns the TUN interface and the native tunnel.
 *
 * It is also the only component that reports tunnel facts: every state change
 * is published through [VpnController], which the home screen renders. The
 * service never writes [Pref.userWantsVpn] — the user's intent is owned by the
 * controller, so a failed start stays a [VpnState.Failed] the UI can explain
 * instead of silently turning the intent off and reconnecting on the next
 * render.
 */
class EasyssVpnService : VpnService() {
    private var tunFd: ParcelFileDescriptor? = null

    private lateinit var pref: Pref
    private var mobileJob: Job? = null
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private val statsClient = StatsClient()
    private var notificationUpdaterJob: Job? = null
    // Guards the async startup sequence: onStartCommand / startService must
    // not launch a second Mobile.start while one is in flight (the AAR
    // rejects "already started"), and a hot restart must wait for the
    // previous startup to fully unwind.
    @Volatile
    private var isStarting: Boolean = false
    // True from the moment a teardown is requested until it completes. A second
    // disconnect while the first teardown is still running must not start a
    // competing shutdown (which would finalize the service twice).
    @Volatile
    private var isStopping: Boolean = false
    // Set when the controller issued a follow-up start while finalizing (server
    // switch), so onDestroy() knows the next instance is already on its way.
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
            // System restart: no Intent is replayed, so the service recovers
            // the active profile, proxy mode and app list from Pref.
            Log.i(TAG, "onStartCommand: no Intent (system restart), loading configuration from Pref.")
        } else if (intent.action != ACTION_CONNECT) {
            Log.w(TAG, "onStartCommand: unexpected action '${intent.action}', treating it as a connect request.")
        }

        return try {
            if (startService()) START_STICKY else START_NOT_STICKY
        } catch (e: Exception) {
            // A start that never reached the startup coroutine must not leave the
            // home screen waiting in "connecting" forever.
            Log.e(TAG, "onStartCommand: unexpected start failure", e)
            controller.onServiceFailed(
                e.message ?: getString(R.string.error_start_service_failed),
                if (::pref.isInitialized) pref.getActiveProfile()?.id else null
            )
            stopSelf()
            START_NOT_STICKY
        }
    }

    private val prefsUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (tunFd != null && !isStopping) {
                restartService()
            } else {
                Log.d(
                        TAG,
                        "prefsUpdatedReceiver: tunFd=${tunFd?.fd}, isStopping=$isStopping. Ignoring PREFS_UPDATED."
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(
            prefsUpdatedReceiver,
            IntentFilter(Pref.PREFS_UPDATED),
            RECEIVER_NOT_EXPORTED
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(prefsUpdatedReceiver)
        Log.d(TAG, "onDestroy: Cancelling serviceScope.")
        serviceScope.cancel()
        // Defensive net: a system-initiated destroy skips the stop path, so the
        // controller must not keep advertising a tunnel that no longer exists.
        // A planned replacement (server switch) already started the next
        // instance, so it must not be reconciled away.
        if (!replacedByController) controller.onServiceDestroyed()
        Log.i(TAG, "onDestroy: Service being destroyed.")
    }

    override fun onRevoke() {
        super.onRevoke()
        // The system revoked the VPN permission (another VPN took over, or the
        // user revoked it in Settings). That is an explicit user/system action,
        // so the persisted intent is cleared here, in the controller.
        controller.onPermissionRevoked()
        stopService()
    }

    /**
     * Builds and starts the tunnel. Returns true when a startup sequence is in
     * flight (or the tunnel is already up), false when the service could not
     * start and has already reported the failure and stopped itself.
     */
    fun startService(): Boolean {
        if (tunFd != null || isStarting) {
            Log.d(TAG, "startService: already running or starting (tunFd=${tunFd?.fd}, isStarting=$isStarting). Ignoring.")
            return true
        }
        if (isStopping) {
            // The teardown owns the native stack; starting underneath it is the
            // exact start/stop race this service is careful to avoid. The
            // controller blocks connect() while Stopping, so this is defensive:
            // the teardown's own finalize reports the state right after.
            Log.w(TAG, "startService: teardown in progress, dropping the start request.")
            return false
        }

        pref = Pref(this)

        // The service and the UI run in the same process, so the active
        // profile, proxy mode and app list are read directly from Pref
        // instead of being serialized through the start Intent.
        val profile = pref.getActiveProfile()
        if (profile == null) {
            Log.w(TAG, "startService: no active server profile in Pref, not starting.")
            // Do not leave a started service without a tunnel or notification.
            // Callers using startForegroundService() must validate the profile
            // first: the platform requires startForeground() before stopping an
            // FGS-started instance (ServiceReceiver enforces that check).
            controller.onServiceFailed(getString(R.string.error_no_active_profile), null)
            stopSelf()
            return false
        }
        controller.onServiceStarting(profile.id)
        Log.i(TAG, "startService: loaded active profile '${profile.name}' (${profile.id}) from Pref")

        /* VPN builder: built now, but establish() is deferred until the
         * easyss SOCKS5 proxy is confirmed ready (see startup sequence
         * below). Establishing the TUN first would make Android route app
         * traffic (including the first DNS queries and the connectivity
         * check) into a tunnel nobody is serving yet — the dropped packets
         * surface as 10s+ first page loads right after enabling. */
        val builder = buildVpnBuilder()

        // Build SimpleConfig and write the TProxy config before establishing
        // the VPN, so everything the native stack needs is on disk first.
        val config = profile.buildSimpleConfig(cacheDir)
        Log.i(TAG, "startService: Starting Mobile proxy - server=${config.getServer()}:${config.getServerPort()}, localPort=${config.getLocalPort()}")

        val socksPort = profile.socksPort.toIntOrNull() ?: Profile.DEFAULT_SOCKS_PORT.toInt()
        val proxyFile = writeTproxyConfig(socksPort)
        if (proxyFile == null) {
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
                // libeasyss prints a version banner in its CLI only, never
                // through the gomobile binding used here, so log it for the
                // in-app log viewer before the startup sequence begins.
                logVersionBanner("profile='${profile.name}' logLevel=${profile.logLevel}")

                // Foreground immediately with a "connecting" state: the
                // startup sequence waits for the proxy to become ready and
                // can take a few seconds, and Android requires the
                // foreground service to be announced promptly.
                showForeground(
                    buildNotification(channelName, profile.name, getString(R.string.notification_connecting))
                )

                // Mobile.start() mirrors the Go export `func Start(cfg) error`:
                // the gomobile binding throws Exception carrying the specific Go
                // error text when startup fails. Surface it to the user via a
                // dialog instead of silently tearing down.
                Log.i(TAG, "startup: Mobile.start() begin, t+${startupElapsed()}ms")
                try {
                    Mobile.start(config)
                    Log.i(TAG, "startup: Mobile.start() returned, t+${startupElapsed()}ms")
                } catch (e: CancellationException) {
                    // User stopped the service while startup was in flight; let
                    // the outer catch rethrow and the teardown own the cleanup.
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

                // Only the SOCKS5 readiness above gates the VPN, so no app
                // traffic (or Android's connectivity check) is dropped into a
                // dead TUN: the native core is already up at this point.
                val newTunFd = builder.establish()
                if (newTunFd == null) {
                    Log.w(TAG, "startService: Failed to establish new tunFd, it's null.")
                    finishStartupFailure(getString(R.string.error_establish_tun), profile.id)
                    return@launch
                }
                tunFd = newTunFd
                Log.i(TAG, "startService: Successfully established new tunFd: ${newTunFd.fd}, t+${startupElapsed()}ms")

                // The teardown waits for this coroutine before stopping the
                // native stack, but the last steps below are not suspendable, so
                // re-check cancellation right before touching TProxy.
                coroutineContext.ensureActive()

                Log.d(TAG, "startService: Attempting to call TProxyStartService with tunFd: ${newTunFd.fd}.")
                // Natives of the hev-socks5-tunnel AAR; the binding class coming
                // from hev.htproxy is imported at the top of this file.
                val started = TProxyService.TProxyStartService(proxyFile.absolutePath, newTunFd.fd)
                Log.d(TAG, "startService: TProxyStartService returned: $started, t+${startupElapsed()}ms")
                if (!started) {
                    Log.w(TAG, "startService: TProxyStartService reported failure")
                    finishStartupFailure(getString(R.string.error_tproxy_start), profile.id)
                    return@launch
                }

                createNotification(channelName, profile.name)
                startNotificationUpdater(channelName, profile.name, profile.statsUrl())
                Log.i(TAG, "startup: service fully started, t+${startupElapsed()}ms")
                // waitForSocksReady() proved the SOCKS5 listener is serving and
                // TProxy is running, so the local proxy is usable from here.
                controller.onServiceRunning(profile.id)
            } catch (e: CancellationException) {
                // User stopped the service while startup was in flight; the
                // teardown path owns the cleanup.
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

    /**
     * Builds the VpnService builder: addresses, routes, MTU and the per-app
     * routing rules of the current proxy mode.
     */
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

        val proxyMode = pref.getProxyMode()
        val apps = pref.getAppsForMode(proxyMode)
        Log.i(TAG, "Per-app routing: mode=$proxyMode, selectedApps=$apps")

        // VpnService forbids mixing addAllowedApplication and
        // addDisallowedApplication on one builder, so the easyss process's
        // self-bypass must be expressed differently per mode:
        //  - bypass mode: every app except the disallowed ones is routed, so
        //    the easyss process itself must be disallowed explicitly — its
        //    outbound dials to the proxy server would otherwise be routed
        //    back into the TUN (self-loop) and never connect.
        //  - proxy-only mode: only the allowed apps are routed; every other
        //    app (including the easyss process) already bypasses the VPN, so
        //    no self entry is needed. Guard against the user selecting the
        //    app itself in the allowed list, which would re-introduce the
        //    self-loop.
        val selfName = applicationContext.packageName
        if (proxyMode == Pref.PROXY_MODE_PROXY_ONLY) {
            val allowedApps = apps.filterNot { it == selfName }
            if (allowedApps.size != apps.size) {
                Log.w(TAG, "Removed easyss self from allowed apps to avoid a routing loop: $selfName")
            }
            for (appName in allowedApps) {
                try {
                    builder.addAllowedApplication(appName)
                    Log.i(TAG, "addAllowedApplication: $appName")
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "App not found for VPN allow: $appName", e)
                }
            }
            session += "/per-App(allow)"
        } else {
            for (appName in apps) {
                try {
                    builder.addDisallowedApplication(appName)
                    Log.i(TAG, "addDisallowedApplication: $appName")
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "App not found for VPN bypass: $appName", e)
                }
            }
            try {
                builder.addDisallowedApplication(selfName)
                Log.i(TAG, "addDisallowedApplication: $selfName (self)")
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Self app not found for VPN bypass: $selfName", e)
            }
            session += "/per-App"
        }
        builder.setSession(session)
        return builder
    }

    /**
     * Writes the hev-socks5-tunnel config for [socksPort]. Returns null when the
     * file cannot be written, so the caller can report the failure instead of
     * starting the tunnel with a stale config.
     */
    private fun writeTproxyConfig(socksPort: Int): File? {
        Log.d(TAG, "startService: Preparing tproxy.conf with SOCKS port: $socksPort")
        val proxyFile = File(cacheDir, Pref.TPROXY_FILE)
        return try {
            proxyFile.createNewFile()
            // TCP idle timeout: hev-socks5-tunnel defaults to 300000ms (5min);
            // raise it to 480000ms (8min) so idle-but-alive TCP sessions
            // (long polls, keep-alive connections) survive longer.
            val tproxyConf = """misc:
  tcp-read-write-timeout: 480000
  udp-read-write-timeout: 15000

socks5:
  port: $socksPort
  address: '127.0.0.1'
  udp: 'udp'
"""
            FileOutputStream(proxyFile, false).use { fos ->
                fos.write(tproxyConf.toByteArray())
            }
            proxyFile
        } catch (e: IOException) {
            Log.e(TAG, "Error writing tproxy.conf", e)
            null
        }
    }

    /**
     * Best-effort cleanup for a startup that could not complete: stop whatever
     * part of the native stack came up, close the TUN fd, report the failure to
     * the controller and stop the service.
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
        // Report before stopping so the failure is not lost if the process is
        // torn down right after stopSelf().
        controller.onServiceFailed(message, profileId)
        stopSelf()
    }

    /**
     * Polls the local SOCKS5 listener until it accepts a real SOCKS5
     * handshake (greeting -> method selection reply), which proves the
     * easyss proxy is serving, or [timeoutMs] elapses. Mirrors the Go side's
     * Socks5Server.waitForAccept probe. A plain TCP connect is not enough:
     * the listener may be bound at the kernel level before the accept loop
     * is registered.
     *
     * [nowMillis] and [probe] are injectable for tests.
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
            // Nothing to tear down. Finalizing anyway is what removes a residual
            // service instance: an idle ACTION_DISCONNECT used to return here
            // without stopSelf(), leaving a started (but useless) service behind.
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
     * Tears down the current tunnel and re-establishes it with the latest
     * SharedPreferences state. Used when PREFS_UPDATED is broadcast while the
     * VPN is running (active profile edited, proxy mode toggled, app list
     * changed), so the service keeps running with the new config instead of
     * stopping for good.
     */
    private fun restartService() {
        if (isStopping) {
            Log.d(TAG, "restartService: teardown already in progress, ignoring broadcast.")
            return
        }
        Log.i(TAG, "restartService() called. Tearing down tunnel to reload latest prefs.")
        isStopping = true
        // Report the restart window as Connecting: the local proxy is about to
        // be unusable, so TunnelState.ready must drop now instead of staying
        // true for the whole teardown (and the home screen must stop polling a
        // stats endpoint that is being torn down).
        pref.getActiveProfile()?.let { controller.onServiceStarting(it.id) }
        shutdownTunnel {
            if (!pref.userWantsVpn) {
                // User pressed stop while the tunnel was being torn down.
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
     * Common teardown sequence: waits for an in-flight startup to unwind, stops
     * the native stack, closes the TUN fd, then runs [onShutdownComplete] on the
     * main thread.
     *
     * The startup job is cancelled and joined *before* `Mobile.stop()` /
     * `TProxyStopService()`: `Mobile.start()` is a blocking native call, so
     * cancelling only cooperatively and stopping underneath it raced the native
     * start/stop inside libeasyss.
     */
    private fun shutdownTunnel(onShutdownComplete: () -> Unit) {
        notificationUpdaterJob?.cancel()
        notificationUpdaterJob = null
        stopForeground(STOP_FOREGROUND_REMOVE)

        serviceScope.launch {
            try {
                // 1. Cancel the in-flight startup and wait for it to finish.
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

                // 2. Stop TProxy (hev-socks5-tunnel), then Mobile.
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
     * Final Java-level stop: removes the notification, closes the TUN fd, stops
     * this service instance and lets the controller decide what comes next
     * (Idle, or the second half of a server switch).
     */
    private fun finalizeStop() {
        Log.d(TAG, "finalizeStop: finalizing service stop.")
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
        // After stopSelf() so a switch's follow-up start cannot be swallowed by
        // the stop this service instance is performing right now.
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
            delay(2_000) // brief wait for stats endpoint to be ready
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
        // How long startup waits for the easyss SOCKS5 proxy to accept a
        // real handshake before giving up (see waitForSocksReady).
        const val STARTUP_READY_TIMEOUT_MS = 30_000L

        // How long a teardown waits for the in-flight startup coroutine before
        // stopping the native stack anyway (see shutdownTunnel).
        const val STARTUP_JOIN_TIMEOUT_MS = 10_000L

        const val ACTION_CONNECT = "CONNECT"
        const val ACTION_DISCONNECT = "DISCONNECT"
        const val NOTIFICATION_ID = 1
        private const val TAG = "EasyssVpnServiceDiag"

        /**
         * Builds the action-only start Intent. Configuration is never passed
         * through the Intent: the service reads the active profile, proxy mode
         * and app list from Pref. Callers differ only in how they start the
         * service ([Context.startService] from the UI,
         * [Context.startForegroundService] from [ServiceReceiver]).
         */
        fun connectIntent(context: Context): Intent =
            Intent(context, EasyssVpnService::class.java).setAction(ACTION_CONNECT)

        fun disconnectIntent(context: Context): Intent =
            Intent(context, EasyssVpnService::class.java).setAction(ACTION_DISCONNECT)
    }
}
