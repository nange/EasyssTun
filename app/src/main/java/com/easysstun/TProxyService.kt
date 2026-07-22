package com.easysstun

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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException


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
            if (tunFd != null) {
                stopService()
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

        var loadedProfile: ServerProfile? = null
        var profileSource = "Unknown"

        val profileJson = receivedProfileJson
        if (profileJson != null && profileJson.isNotBlank()) {
            Log.d(TAG, "Attempting to deserialize profile from Intent JSON.")
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            try {
                loadedProfile = json.decodeFromString<ServerProfile>(profileJson)
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
                loadedProfile = pref.getServerProfiles().find { it.id == activeIdFromPrefs }
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
            Log.w(TAG, "最终: Loaded ServerProfile is null (Source evaluation: $profileSource).")
        } else {
            Log.d(TAG, "最终: Loaded ServerProfile (Source: $profileSource) - ID: ${loadedProfile.id}, Name: '${loadedProfile.name}', Server: '${loadedProfile.server}', Port: '${loadedProfile.serverPort}'")
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
        Log.d(TAG, "startService: Preparing tproxy.conf with SOCKS port: 2080")
        val proxyFile = File(cacheDir, Pref.TPROXY_FILE)
        try {
            proxyFile.createNewFile()
            val tproxyConf = """misc:
  tcp-read-write-timeout: 300000
  udp-read-write-timeout: 15000

socks5:
  port: 2080
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
        TProxyStartService(proxyFile.absolutePath, newTunFd.fd)
        pref.prefs.edit { apply { putBoolean("enable", true) } }
        val channelName = "easysstun"
        initNotificationChannel(channelName)
        createNotification(channelName)
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
        stopForeground(STOP_FOREGROUND_REMOVE)

        serviceScope.launch {
            try {
                // 1. Stop TProxy (hev-socks5-tunnel)
                val tproxyStopJob = launch {
                    try {
                        Log.d(TAG, "stopService: TProxyStopService coroutine calling TProxyStopService()")
                        TProxyStopService()
                        Log.d(TAG, "stopService: TProxyStopService coroutine TProxyStopService() completed.")
                    } catch (e: Throwable) {
                        Log.e(TAG, "stopService: Exception during TProxyStopService: ${e.message}", e)
                    }
                }

                // 2. Stop Mobile proxy (via AAR)
                try {
                    Log.d(TAG, "stopService: Calling Mobile.stop()...")
                    Mobile.stop()
                    Log.d(TAG, "stopService: Mobile.stop() completed.")
                } catch (e: Exception) {
                    Log.e(TAG, "stopService: Exception during Mobile.stop(): ${e.message}", e)
                }

                // 3. Cancel and join the mobile coroutine
                if (::mobileJob.isInitialized && mobileJob.isActive) {
                    Log.d(TAG, "stopService: Cancelling mobileJob.")
                    try {
                        mobileJob.cancel()
                    } catch (e: Exception) {
                        Log.e(TAG, "stopService: Exception during mobileJob.cancel(): ${e.message}", e)
                    }

                    Log.d(TAG, "stopService: Joining mobileJob.")
                    try {
                        mobileJob.join()
                        Log.d(TAG, "stopService: mobileJob completed.")
                    } catch (e: Exception) {
                        Log.e(TAG, "stopService: Exception during mobileJob.join(): ${e.message}", e)
                    }
                } else {
                    Log.d(TAG, "stopService: mobileJob was not active or initialized.")
                }

                // 4. Wait for TProxy to stop
                Log.d(TAG, "stopService: Waiting for TProxyStopService job (tproxyStopJob) to complete.")
                tproxyStopJob.join()
                Log.d(TAG, "stopService: TProxyStopService job (tproxyStopJob) completed.")

            } catch (e: Exception) {
                Log.e(TAG, "stopService: Unhandled exception during native cleanup phase: ${e.message}", e)
            } finally {
                Log.d(TAG, "stopService: Native cleanup phase complete or errored. Proceeding with final Java-level cleanup.")
                withContext(Dispatchers.Main.immediate) {
                    actualFinalizeStop()
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

    private fun createNotification(channelName: String) {
        val i = Intent(this, MainActivity::class.java)
        i.flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        val pi = PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channelName)
        val notify = notification
            .setContentTitle(getString(R.string.service_running))
            .setSilent(true)
            .setSmallIcon(R.drawable.ic_launcher_foreground_big)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(1, notify)
        } else {
            startForeground(1, notify, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
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
        @JvmStatic
        private external fun TProxyStartService(config_path: String, fd: Int)
        @JvmStatic
        private external fun TProxyStopService()
        @JvmStatic
        private external fun TProxyGetStats(): LongArray

        const val ACTION_CONNECT = "CONNECT"
        const val ACTION_DISCONNECT = "DISCONNECT"
        const val ACTION_SERVICE_STOPPED = "com.easysstun.SERVICE_FULLY_STOPPED"
        const val EXTRA_PROXY_MODE = "com.easysstun.PROXY_MODE_EXTRA"
        const val EXTRA_SELECTED_APPS = "com.easysstun.SELECTED_APPS_EXTRA"
        private const val TAG = "TProxyServiceDiag"

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }
}
