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
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import io.github.nange.easyss.config.SimpleConfig
import io.github.nange.easyss.mobile.Mobile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale


class TProxyService : VpnService() {
    private var tunFd: ParcelFileDescriptor? = null
    private var receivedProfileJson: String? = null
    private var receivedProxyMode: String? = null
    private var receivedSelectedApps: ArrayList<String>? = null

    private lateinit var pref: Pref
    private val easyJob = Job()
    private val easyScope = CoroutineScope(Dispatchers.IO + easyJob)
    private lateinit var mobileJob: Job
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job())
    private var notificationUpdaterJob: Job? = null
    private var isRestarting: Boolean = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ACTION_DISCONNECT == intent?.action) {
            Log.i(TAG, "onStartCommand: Received ACTION_DISCONNECT.")
            receivedProfileJson = null
            stopService()
            return START_NOT_STICKY
        }

        if (intent != null && intent.action == ACTION_CONNECT) {
            receivedProfileJson = intent.getStringExtra("com.easysstun.ACTIVE_SERVER_PROFILE_JSON_EXTRA")
            receivedProxyMode = intent.getStringExtra(EXTRA_PROXY_MODE)
            receivedSelectedApps = intent.getStringArrayListExtra(EXTRA_SELECTED_APPS)
            Log.i(TAG, "onStartCommand: Received proxyMode=$receivedProxyMode, selectedApps=$receivedSelectedApps via Intent.")
        } else {
            Log.w(TAG, "onStartCommand: No profile JSON in Intent or unexpected action. Intent: $intent")
            receivedProfileJson = null
        }

        try {
            startService()
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start service due to IOException. Stopping service.", e)
            stopSelf()
        }
        return START_STICKY
    }

    private val prefsUpdatedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (tunFd != null && !isRestarting) {
                restartService()
            } else {
                Log.d(
                        TAG,
                        "prefsUpdatedReceiver: tunFd=${tunFd?.fd}, isRestarting=$isRestarting. Ignoring PREFS_UPDATED."
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
        Log.i(TAG, "onDestroy: Service being destroyed.")
    }

    override fun onRevoke() {
        super.onRevoke()
        stopService()
    }

    @Throws(IOException::class)
    fun startService() {
        if (tunFd != null) return

        pref = Pref(this)

        var loadedProfile: Profile? = null
        var profileSource = "Unknown"

        val profileJson = receivedProfileJson
        if (profileJson != null && profileJson.isNotBlank()) {
            Log.d(TAG, "Attempting to deserialize profile from Intent JSON.")
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            try {
                loadedProfile = json.decodeFromString<Profile>(profileJson)
                profileSource = "Intent JSON"
                loadedProfile.let { Log.i(TAG, "Successfully deserialized profile from Intent. ID: ${it.id}") }
            } catch (e: kotlinx.serialization.SerializationException) {
                Log.e(TAG, "Error deserializing profile from Intent JSON: ${e.message}. Falling back.")
                profileSource = "Intent JSON Deserialization Error -> Fallback"
            }
        } else {
            Log.d(TAG, "No profile JSON in Intent. Falling back to SharedPreferences.")
            profileSource = "SharedPreferences Fallback"
        }

        if (loadedProfile == null) {
            Log.d(TAG, "Executing fallback: Loading profile via SharedPreferences.")
            val activeIdFromPrefs = pref.prefs.getString(Pref.ACTIVE_SERVER_ID, null)
            Log.d(TAG, "Fallback: Active Server ID from SharedPreferences: '$activeIdFromPrefs'")
            if (activeIdFromPrefs != null) {
                loadedProfile = pref.getProfiles().find { it.id == activeIdFromPrefs }
                if (loadedProfile != null) {
                    loadedProfile.let { Log.i(TAG, "Fallback: Successfully loaded profile from SharedPreferences. ID: ${it.id}") }
                    if (profileSource.startsWith("Intent JSON Deserialization Error")) {
                        profileSource = "SharedPreferences Fallback (after Deserialization Error)"
                    }
                } else {
                    Log.w(TAG, "Fallback: Profile with ID '$activeIdFromPrefs' not found in SharedPreferences list.")
                }
            } else {
                Log.w(TAG, "Fallback: No Active Server ID found in SharedPreferences.")
            }
        }

        if (loadedProfile == null) {
            Log.w(TAG, "最终: Loaded Profile is null (Source evaluation: $profileSource).")
        } else {
            Log.d(TAG, "最终: Loaded Profile (Source: $profileSource) - ID: ${loadedProfile.id}, Name: '${loadedProfile.name}', Server: '${loadedProfile.server}', Port: '${loadedProfile.serverPort}'")
        }

        val easyssInfo = easyssInfo()
        if (loadedProfile == null) {
            easyssInfo.valid = false
        } else {
            easyssInfo.valid = true
            easyssInfo.info = "${loadedProfile.server}:${loadedProfile.serverPort}"
        }

        if (!easyssInfo.valid) {
            Log.w(TAG, "Constructed easyssInfo is invalid. Service will not start or will be stopped.")
            pref.isServiceEnabled = false
            return
        }

        /* VPN */
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

        val proxyMode = receivedProxyMode ?: Pref.PROXY_MODE_BYPASS
        val apps = receivedSelectedApps?.toSet() ?: emptySet()
        Log.i(TAG, "Per-app routing: mode=$proxyMode (from Intent), selectedApps=$apps")
        if (proxyMode == Pref.PROXY_MODE_PROXY_ONLY) {
            for (appName in apps) {
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
            session += "/per-App"

            val selfName = applicationContext.packageName
            try {
                builder.addDisallowedApplication(selfName)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.w(TAG, "Self app not found for VPN bypass: $selfName", e)
            }
        }

        builder.setSession(session)
        val newTunFd = builder.establish()
        tunFd = newTunFd
        if (newTunFd != null) {
            Log.i(TAG, "startService: Successfully established new tunFd: ${newTunFd.fd}")
        } else {
            Log.w(TAG, "startService: Failed to establish new tunFd, it's null.")
            stopSelf()
            return
        }

        // Build SimpleConfig and start Mobile proxy via AAR
        val config = loadedProfile!!.buildSimpleConfig(cacheDir)
        Log.i(TAG, "startService: Starting Mobile proxy - server=${config.getServer()}:${config.getServerPort()}, localPort=${config.getLocalPort()}")

        mobileJob = easyScope.launch {
            try {
                Log.d(TAG, "mobileJob: Calling Mobile.start()...")
                Mobile.start(config)
                Log.i(TAG, "mobileJob: Mobile.start() returned normally.")
            } catch (e: Exception) {
                Log.e(TAG, "mobileJob: Mobile.start() failed", e)
                stopService()
            }
        }

        /* TProxy */
        val socksPort = loadedProfile.socksPort
        Log.d(TAG, "startService: Preparing tproxy.conf with SOCKS port: $socksPort")
        val proxyFile = File(cacheDir, Pref.TPROXY_FILE)
        try {
            proxyFile.createNewFile()
            val tproxyConf = """misc:
  tcp-read-write-timeout: 300000
  udp-read-write-timeout: 15000

socks5:
  port: $socksPort
  address: '127.0.0.1'
  udp: 'udp'
"""
            FileOutputStream(proxyFile, false).use { fos ->
                fos.write(tproxyConf.toByteArray())
            }
        } catch (e: IOException) {
            Log.e(TAG, "Error writing tproxy.conf", e)
            return
        }
        Log.d(TAG, "startService: Attempting to call TProxyStartService with tunFd: ${newTunFd.fd}.")
        val started = TProxyStartService(proxyFile.absolutePath, newTunFd.fd)
        Log.d(TAG, "startService: TProxyStartService returned: $started")
        pref.prefs.edit { apply { putBoolean("enable", true) } }
        val channelName = "easysstun"
        initNotificationChannel(channelName)
        createNotification(channelName, loadedProfile.name)
        startNotificationUpdater(channelName, loadedProfile.name, loadedProfile.statsUrl())
    }

    fun stopService() {
        if (tunFd == null && (!::mobileJob.isInitialized || !mobileJob.isActive)) {
            Log.d(TAG, "stopService: called but appears already stopped or not fully started.")
            if (::pref.isInitialized && pref.isServiceEnabled || tunFd != null) {
                Log.w(TAG, "stopService: State indicates service might be partially running despite checks. Forcing finalization.")
                actualFinalizeStop()
            }
            return
        }
        Log.i(TAG, "stopService() called. Initiating shutdown sequence. Current tunFd: ${tunFd?.fd}")
        shutdownTunnel {
            actualFinalizeStop()
        }
    }

    /**
     * Tears down the current tunnel and re-establishes it with the latest
     * SharedPreferences state. Used when PREFS_UPDATED is broadcast while the
     * VPN is running (active profile edited, proxy mode toggled, app list
     * changed), so the service keeps running with the new config instead of
     * stopping for good.
     */
    private fun restartService() {
        if (isRestarting) {
            Log.d(TAG, "restartService: restart already in progress, ignoring broadcast.")
            return
        }
        Log.i(TAG, "restartService() called. Tearing down tunnel to reload latest prefs.")
        isRestarting = true
        shutdownTunnel {
            performRestart()
        }
    }

    private fun performRestart() {
        try {
            if (::pref.isInitialized && !pref.isServiceEnabled) {
                // User pressed stop while the tunnel was being torn down.
                Log.w(TAG, "performRestart: Service was disabled during restart window. Finalizing stop instead.")
                actualFinalizeStop()
                return
            }
            Log.i(TAG, "performRestart: Reloading latest prefs and re-establishing tunnel.")
            // Force reload of the active profile from SharedPreferences, which
            // startService() falls back to when receivedProfileJson is null.
            receivedProfileJson = null
            val proxyMode = pref.getProxyMode()
            receivedProxyMode = proxyMode
            receivedSelectedApps = ArrayList(pref.getAppsForMode(proxyMode))
            startService()
            if (tunFd == null) {
                // startService() failed to establish a tunnel (invalid profile,
                // establish() returned null). Finalize so the UI is notified.
                Log.w(TAG, "performRestart: startService did not establish a tunnel. Finalizing stop.")
                actualFinalizeStop()
            }
        } catch (e: IOException) {
            Log.e(TAG, "performRestart: startService failed. Finalizing stop.", e)
            actualFinalizeStop()
        } finally {
            isRestarting = false
        }
    }

    /**
     * Common teardown sequence: stops the native tunnel and Mobile proxy,
     * cancels the notification updater, closes the TUN fd, then runs
     * [onShutdownComplete] on the main thread.
     */
    private fun shutdownTunnel(onShutdownComplete: () -> Unit) {
        notificationUpdaterJob?.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)

        serviceScope.launch {
            try {
                // 1. Stop TProxy (hev-socks5-tunnel)
                val tproxyStopJob = launch {
                    try {
                        Log.d(TAG, "shutdownTunnel: TProxyStopService coroutine calling TProxyStopService()")
                        val stopped = TProxyStopService()
                        Log.d(TAG, "shutdownTunnel: TProxyStopService returned: $stopped")
                    } catch (e: Throwable) {
                        Log.e(TAG, "shutdownTunnel: Exception during TProxyStopService: ${e.message}", e)
                    }
                }

                // 2. Stop Mobile proxy (via AAR)
                try {
                    Log.d(TAG, "shutdownTunnel: Calling Mobile.stop()...")
                    Mobile.stop()
                    Log.d(TAG, "shutdownTunnel: Mobile.stop() completed.")
                } catch (e: Exception) {
                    Log.e(TAG, "shutdownTunnel: Exception during Mobile.stop(): ${e.message}", e)
                }

                // 3. Cancel and join the mobile coroutine
                if (::mobileJob.isInitialized && mobileJob.isActive) {
                    Log.d(TAG, "shutdownTunnel: Cancelling mobileJob.")
                    try {
                        mobileJob.cancel()
                    } catch (e: Exception) {
                        Log.e(TAG, "shutdownTunnel: Exception during mobileJob.cancel(): ${e.message}", e)
                    }

                    Log.d(TAG, "shutdownTunnel: Joining mobileJob.")
                    try {
                        mobileJob.join()
                        Log.d(TAG, "shutdownTunnel: mobileJob completed.")
                    } catch (e: Exception) {
                        Log.e(TAG, "shutdownTunnel: Exception during mobileJob.join(): ${e.message}", e)
                    }
                } else {
                    Log.d(TAG, "shutdownTunnel: mobileJob was not active or initialized.")
                }

                // 4. Wait for TProxy to stop
                Log.d(TAG, "shutdownTunnel: Waiting for TProxyStopService job (tproxyStopJob) to complete.")
                tproxyStopJob.join()
                Log.d(TAG, "shutdownTunnel: TProxyStopService job (tproxyStopJob) completed.")

            } catch (e: Exception) {
                Log.e(TAG, "shutdownTunnel: Unhandled exception during native cleanup phase: ${e.message}", e)
            } finally {
                Log.d(TAG, "shutdownTunnel: Native cleanup phase complete or errored. Proceeding with final Java-level cleanup.")
                withContext(Dispatchers.Main.immediate) {
                    Log.d(TAG, "shutdownTunnel: Closing tunFd: ${tunFd?.fd}.")
                    try {
                        tunFd?.close()
                    } catch (e: IOException) {
                        Log.e(TAG, "shutdownTunnel: Exception closing tunFd: ${e.message}", e)
                    }
                    tunFd = null
                    Log.d(TAG, "shutdownTunnel: tunFd set to null.")
                    onShutdownComplete()
                }
            }
        }
    }

    private fun actualFinalizeStop() {
        Log.d(TAG, "actualFinalizeStop: Finalizing service stop.")
        try {
            Log.d(TAG, "actualFinalizeStop: Attempting to close tunFd: ${tunFd?.fd}.")
            tunFd?.close()
        } catch (e: IOException) {
            Log.e(TAG, "actualFinalizeStop: Exception closing tunFd: ${e.message}", e)
        }
        tunFd = null
        Log.d(TAG, "actualFinalizeStop: tunFd set to null.")

        if (::pref.isInitialized) {
            pref.isServiceEnabled = false
        } else {
            Log.w(TAG, "actualFinalizeStop: Pref not initialized, cannot set isServiceEnabled.")
        }

        Log.d(TAG, "actualFinalizeStop: Calling stopSelf().")
        stopSelf()
        Log.d(TAG, "actualFinalizeStop: Broadcasting ACTION_SERVICE_STOPPED")
        val broadcastIntent = Intent(ACTION_SERVICE_STOPPED)
        broadcastIntent.setPackage(packageName)
        sendBroadcast(broadcastIntent)
        Log.i(TAG, "actualFinalizeStop: Service fully stopped and broadcast sent to package $packageName.")
    }

    private fun createNotification(channelName: String, profileName: String) {
        val notify = buildNotification(
            channelName,
            profileName,
            getString(R.string.notification_stats_unavailable)
        )
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

    private fun fetchStatsContentText(statsUrl: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(statsUrl)
            conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            conn.requestMethod = "GET"
            if (conn.responseCode != 200) return null
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val avgRttMs = json.optDouble("avg_rtt_ms", 0.0)
            val dlSpeed = json.optString("download_speed_human", "")
            val rttText = if (avgRttMs > 0) String.format(Locale.ROOT, "%.1fms", avgRttMs) else "--"
            val speedText = if (dlSpeed.isNotBlank()) dlSpeed else "--"
            getString(R.string.notification_stats_text, rttText, speedText)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "Failed to fetch stats for notification: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun initNotificationChannel(channelName: String) {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val name: CharSequence = getString(R.string.app_name)
        val channel =
            NotificationChannel(channelName, name, NotificationManager.IMPORTANCE_LOW)
        notificationManager.createNotificationChannel(channel)

    }

    companion object {
        // JNI natives live on hev.htproxy.TProxyService (fixed contract of the
        // prebuilt hev-socks5-tunnel AAR); these are thin forwarding wrappers.
        private fun TProxyStartService(config_path: String, fd: Int): Boolean =
            hev.htproxy.TProxyService.TProxyStartService(config_path, fd)

        private fun TProxyStopService(): Boolean =
            hev.htproxy.TProxyService.TProxyStopService()

        private fun TProxyIsRunning(): Boolean =
            hev.htproxy.TProxyService.TProxyIsRunning()

        private fun TProxyGetStats(): LongArray =
            hev.htproxy.TProxyService.TProxyGetStats()

        const val ACTION_CONNECT = "CONNECT"
        const val ACTION_DISCONNECT = "DISCONNECT"
        const val ACTION_SERVICE_STOPPED = "com.easysstun.SERVICE_FULLY_STOPPED"
        const val EXTRA_PROXY_MODE = "com.easysstun.PROXY_MODE_EXTRA"
        const val EXTRA_SELECTED_APPS = "com.easysstun.SELECTED_APPS_EXTRA"
        const val NOTIFICATION_ID = 1
        private const val TAG = "TProxyServiceDiag"
    }
}
