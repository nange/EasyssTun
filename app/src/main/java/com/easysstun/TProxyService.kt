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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel // Added this import
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import android.content.pm.ServiceInfo
import kotlin.time.Duration.Companion.milliseconds


class TProxyService : VpnService() {
    private var tunFd: ParcelFileDescriptor? = null
    private var receivedProfileJson: String? = null
    private var receivedProxyMode: String? = null
    private var receivedSelectedApps: ArrayList<String>? = null

    private lateinit var pref: Pref
    private val easyJob = Job() // Job for the easyss process coroutine
    private val easyScope = CoroutineScope(Dispatchers.Default + easyJob)
    private lateinit var processEasyJob: Job
    lateinit var process: Process
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job()) // For managing shutdown and other service-level tasks

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ACTION_DISCONNECT == intent?.action) {
            Log.i(TAG, "onStartCommand: Received ACTION_DISCONNECT.")
            receivedProfileJson = null // Clear any old JSON on disconnect
            stopService()
            return START_NOT_STICKY
        }

        if (intent != null && intent.action == ACTION_CONNECT) { // Assuming ACTION_CONNECT is the trigger
            receivedProfileJson = intent.getStringExtra("com.easysstun.ACTIVE_SERVER_PROFILE_JSON_EXTRA")
            receivedProxyMode = intent.getStringExtra(EXTRA_PROXY_MODE)
            receivedSelectedApps = intent.getStringArrayListExtra(EXTRA_SELECTED_APPS)
            Log.i(TAG, "onStartCommand: Received proxyMode=$receivedProxyMode, selectedApps=$receivedSelectedApps via Intent.")
        } else {
            // If intent is null or action isn't connect (and not disconnect), clear receivedProfileJson
            // to ensure fallback or default behavior if service is restarted by system.
            Log.w(TAG, "onStartCommand: No profile JSON in Intent or unexpected action. Intent: $intent")
            receivedProfileJson = null
        }

        try {
            startService()
        } catch (e: IOException) { // Ensure correct import for IOException
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
        serviceScope.cancel() // Cancel all coroutines launched by serviceScope
        Log.i(TAG, "onDestroy: Service being destroyed.") // Keep Log.i - Core lifecycle
        // stopSelf() // This should be handled by actualFinalizeStop now
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
                // loadedProfile remains null, will trigger fallback
            }
        } else {
            Log.d(TAG, "No profile JSON in Intent. Falling back to SharedPreferences.")
            profileSource = "SharedPreferences Fallback"
        }

        if (loadedProfile == null) { // Fallback logic
            Log.d(TAG, "Executing fallback: Loading profile via SharedPreferences.")
            // This part uses pref to get active ID then find in list
            val activeIdFromPrefs = pref.prefs.getString(Pref.ACTIVE_SERVER_ID, null)
            Log.d(TAG, "Fallback: Active Server ID from SharedPreferences: '$activeIdFromPrefs'")
            if (activeIdFromPrefs != null) {
                loadedProfile = pref.getServerProfiles().find { it.id == activeIdFromPrefs }
                if (loadedProfile != null) {
                    loadedProfile.let { Log.i(TAG, "Fallback: Successfully loaded profile from SharedPreferences. ID: ${it.id}") } // Keep Log.i - High-level outcome
                    // If profile was null before due to deserialization error, but fallback succeeded, update source
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
        // End of new loadedProfile logic

        // Update Diagnostic Logging
        if (loadedProfile == null) {
            Log.w(TAG, "最终: Loaded ServerProfile is null (Source evaluation: $profileSource).")
        } else {
            Log.d(TAG, "最终: Loaded ServerProfile (Source: $profileSource) - ID: ${loadedProfile.id}, Name: '${loadedProfile.name}', Server: '${loadedProfile.server}', Port: '${loadedProfile.serverPort}'")
        }
        
        // Construct easyssInfo based on loadedProfile
        val easyssInfo = easyssInfo()
        if (loadedProfile == null) {
            easyssInfo.valid = false
        } else {
            // This is the logic from Pref.getEasyssInfo(), adapted
            easyssInfo.valid = true
            easyssInfo.info = "${loadedProfile.server}:${loadedProfile.serverPort}"
            easyssInfo.cmdList = loadedProfile.buildCmdList(cacheDir, Pref.DEFAULT_SOCKS_PORT)
        }
        // The existing diagnostic logs for easyssInfo.valid and cmdList should follow this.
        Log.d(TAG, "Constructed easyssInfo.valid: ${easyssInfo.valid}")
        if (easyssInfo.valid) {
            val serverInCmd = easyssInfo.cmdList.indexOf("-s")
            val serverAddressInCmd = if (serverInCmd != -1 && serverInCmd + 1 < easyssInfo.cmdList.size) easyssInfo.cmdList[serverInCmd + 1] else "N/A"
            val portInCmd = easyssInfo.cmdList.indexOf("-p")
            val serverPortInCmd = if (portInCmd != -1 && portInCmd + 1 < easyssInfo.cmdList.size) easyssInfo.cmdList[portInCmd + 1] else "N/A"
            Log.d(TAG, "Constructed easyssInfo command params: Effective Server='${serverAddressInCmd}', Effective Port='${serverPortInCmd}'")
        }

        if (!easyssInfo.valid){
            Log.w(TAG, "Constructed easyssInfo is invalid. Service will not start or will be stopped.")
            pref.isServiceEnabled = false // This uses the setter which should commit.
            return
        }

        // VPN setup, processEasyJob, TProxy config, etc. remain the same.
        // Ensure they use the 'easyssInfo' constructed above.

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
            // Only allowed apps go through VPN
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
            // Bypass selected apps (default)
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
            Log.i(TAG, "startService: Successfully established new tunFd: ${newTunFd.fd}") // Keep Log.i - Core lifecycle
        } else {
            Log.w(TAG, "startService: Failed to establish new tunFd, it's null.")
            // stopSelf() is called by the original code if tunFd is null, which is fine.
            stopSelf()
            return
        }

        processEasyJob = easyScope.launch {
            var restartCount = 0
            val maxRestarts = 3
            val minRunningTimeMs = 5000L

            while (isActive) {
                var processStartedTime = 0L
                try {
                    val libraryPath = applicationInfo.nativeLibraryDir.toString() + "/libeasyss.so"
                    val cmdList = listOf(libraryPath) + easyssInfo.cmdList
                    Log.d(TAG, "processEasyJob: Attempting to start libeasyss.so process. Command server: ${easyssInfo.cmdList.getOrNull(easyssInfo.cmdList.indexOf("-s") + 1)}, local port: ${easyssInfo.cmdList.getOrNull(easyssInfo.cmdList.indexOf("-l") + 1)}")
                    
                    processStartedTime = System.currentTimeMillis()
                    process = ProcessBuilder(cmdList).start()
                    Log.i(TAG, "processEasyJob: libeasyss.so process started (ProcessBuilder executed). isAlive: ${process.isAlive}") // Keep Log.i - Core lifecycle

                    Log.d("easyss", "msg=[EasyssTun] Connected to the service successfully.")
                    BufferedReader(InputStreamReader(process.inputStream)).use { bufferedReader ->
                        while (isActive) {
                            val line = bufferedReader.readLine() ?: break
                            Log.i("easyss", line)
                        }
                    }

                } catch (e: IOException) {
                    Log.e("easyss", "msg=[EasyssTun] IOException: " + e.message)
                } catch (e: InterruptedException) {
                    Log.e("easyss", "msg=[EasyssTun] InterruptedException: " + e.message)
                } finally {
                    Log.d(TAG, "processEasyJob: finally block entered. Process initialized: ${::process.isInitialized}")
                    var exitCode = -999
                    if (::process.isInitialized) { // Check if process was even initialized
                        Log.d(TAG, "processEasyJob: About to call process.destroy(). Current process state: isAlive=${process.isAlive}")
                        process.destroy()
                        Log.d(TAG, "processEasyJob: process.destroy() called. About to call process.waitFor().")
                        exitCode = process.waitFor()
                        Log.i(TAG, "processEasyJob: libeasyss.so process exited with code: $exitCode") // Keep Log.i - Core lifecycle
                    } else {
                        Log.w(TAG, "processEasyJob: finally block, process was not initialized.")
                    }

                    // Check if this was a manual cancellation
                    val isCancelled = coroutineContext[Job]?.isCancelled == true
                    if (isCancelled) {
                        Log.i(TAG, "processEasyJob: Coroutine was cancelled. Exiting process loop.")
                        break // Break the while(true) loop on manual stop
                    }

                    // Calculate how long it was running
                    val runningTime = System.currentTimeMillis() - processStartedTime
                    if (runningTime > minRunningTimeMs) {
                        // Reset restart count if it ran successfully for a reasonable time
                        Log.d(TAG, "processEasyJob: Process ran stably for ${runningTime}ms. Resetting restart count.")
                        restartCount = 0
                    }

                    if (restartCount < maxRestarts) {
                        restartCount++
                        val backoffDelay = restartCount * 1000L
                        Log.w(TAG, "processEasyJob: libeasyss.so process exited (code $exitCode) unexpectedly! Restarting in ${backoffDelay}ms (Attempt $restartCount/$maxRestarts)")
                        try {
                            delay(backoffDelay.milliseconds)
                        } catch (e: Exception) {
                            // If delay is interrupted/cancelled
                            break
                        }
                    } else {
                        Log.e(TAG, "processEasyJob: libeasyss.so process exited unexpectedly and reached maximum restart attempts ($maxRestarts). Stopping VPN service.")
                        stopService()
                        break
                    }
                }
            }
        }


        val socksPortForTProxy = pref.prefs.getString(Pref.SOCKS_PORT_KEY, Pref.DEFAULT_SOCKS_PORT)
        Log.d(TAG, "startService: Preparing tproxy.conf with SOCKS port: $socksPortForTProxy")
        /* TProxy */
        val proxyFile = File(cacheDir, Pref.TPROXY_FILE)
        try {
            proxyFile.createNewFile()
            val tproxyConf = """misc:
  tcp-read-write-timeout: 300000
  udp-read-write-timeout: 15000

socks5:
  port: ${pref.prefs.getString(Pref.SOCKS_PORT_KEY, Pref.DEFAULT_SOCKS_PORT)?.toInt()}
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
        if (tunFd == null && (!::processEasyJob.isInitialized || !processEasyJob.isActive)) {
            Log.d(TAG, "stopService: called but appears already stopped or not fully started.")
            // It's possible actualFinalizeStop() was not called if a previous stop was interrupted.
            // Check pref state and call actualFinalizeStop if needed, or just return.
            if(::pref.isInitialized && pref.isServiceEnabled || tunFd != null) { // If state indicates it might still be "on"
                 Log.w(TAG, "stopService: State indicates service might be partially running despite checks. Forcing finalization.")
                 actualFinalizeStop() // Ensure it's fully stopped.
            }
            return
        }
        Log.i(TAG, "stopService() called. Initiating shutdown sequence. Current tunFd: ${tunFd?.fd}") // Keep Log.i - User-driven or high-level state change
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

                // 2. Stop libeasyss process
                if (::processEasyJob.isInitialized && processEasyJob.isActive) {
                    Log.d(TAG, "stopService: Cancelling processEasyJob.")
                    try {
                        processEasyJob.cancel()
                    } catch (e: Exception) {
                        Log.e(TAG, "stopService: Exception during processEasyJob.cancel(): ${e.message}", e)
                    }

                    if (::process.isInitialized && process.isAlive) {
                        Log.d(TAG, "stopService: Destroying process directly to unblock readLine().")
                        try {
                            process.destroy()
                        } catch (e: Exception) {
                            Log.e(TAG, "stopService: Exception destroying process: ${e.message}", e)
                        }
                    }

                    Log.d(TAG, "stopService: Joining processEasyJob.")
                    try {
                        processEasyJob.join() 
                        Log.d(TAG, "stopService: processEasyJob completed.")
                    } catch (e: Exception) {
                        Log.e(TAG, "stopService: Exception during processEasyJob.join(): ${e.message}", e)
                    }
                } else {
                    Log.d(TAG, "stopService: processEasyJob was not active or initialized.")
                    if (::process.isInitialized && process.isAlive) {
                         Log.w(TAG, "stopService: processEasyJob not active, but process is. Destroying directly.")
                         try {
                            // Ensure direct destruction happens on an IO-like context
                            withContext(Dispatchers.IO) { 
                                process.destroy() 
                                process.waitFor() 
                            }
                            Log.d(TAG, "stopService: Direct process destruction complete.")
                         } catch (e: Exception) {
                             Log.e(TAG, "stopService: Exception during direct process destruction: ${e.message}", e)
                         }
                    }
                }
                
                // 3. Wait for TProxy to stop
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
        Log.i(TAG, "actualFinalizeStop: Service fully stopped and broadcast sent to package $packageName.") // Keep Log.i - High-level outcome
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
            startForeground(1, notify, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }

    //     create NotificationChannel
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