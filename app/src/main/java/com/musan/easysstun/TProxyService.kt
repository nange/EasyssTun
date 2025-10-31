package com.musan.easysstun

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
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel // Added this import
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader
import android.content.pm.ServiceInfo;


class TProxyService : VpnService() {
    private var tunFd: ParcelFileDescriptor? = null
    private var receivedProfileJson: String? = null

    private lateinit var pref: Pref
    private val easyJob = Job() // Job for the easyss process coroutine
    private val easyScope = CoroutineScope(Dispatchers.Default + easyJob)
    private lateinit var processEasyJob: Job
    lateinit var process: Process
    private val serviceScope = CoroutineScope(Dispatchers.IO + Job()) // For managing shutdown and other service-level tasks

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // val TAG = TProxyService::class.java.simpleName // Using companion object TAG
        if (ACTION_DISCONNECT == intent?.action) {
            Log.i(TAG, "onStartCommand: Received ACTION_DISCONNECT.")
            receivedProfileJson = null // Clear any old JSON on disconnect
            stopService()
            return START_NOT_STICKY
        }

        if (intent != null && intent.action == ACTION_CONNECT) { // Assuming ACTION_CONNECT is the trigger
            receivedProfileJson = intent.getStringExtra("com.musan.easysstun.ACTIVE_SERVER_PROFILE_JSON_EXTRA")
            Log.i(TAG, "onStartCommand: Received profile JSON (length: ${receivedProfileJson?.length ?: "null"}) via Intent.") // Keep Log.i - User-driven action
        } else {
            // If intent is null or action isn't connect (and not disconnect), clear receivedProfileJson
            // to ensure fallback or default behavior if service is restarted by system.
            Log.w(TAG, "onStartCommand: No profile JSON in Intent or unexpected action. Intent: $intent")
            receivedProfileJson = null
        }

        try {
            startService()
        } catch (e: java.io.IOException) { // Ensure correct import for IOException
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
            IntentFilter("prefs_updated"),
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
        // val TAG = "TProxyServiceDiag" // Already in companion object

        var loadedProfile: com.musan.easysstun.ServerProfile? = null 
        var profileSource = "Unknown"

        if (receivedProfileJson != null && receivedProfileJson!!.isNotBlank()) {
            Log.d(TAG, "Attempting to deserialize profile from Intent JSON.")
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true } 
            try {
                loadedProfile = json.decodeFromString<com.musan.easysstun.ServerProfile>(receivedProfileJson!!)
                profileSource = "Intent JSON"
                loadedProfile?.let { Log.i(TAG, "Successfully deserialized profile from Intent. ID: ${it.id}") }
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
            val activeIdFromPrefs = pref.prefs.getString(com.musan.easysstun.Pref.ACTIVE_SERVER_ID, null) 
            Log.d(TAG, "Fallback: Active Server ID from SharedPreferences: '$activeIdFromPrefs'")
            if (activeIdFromPrefs != null) {
                loadedProfile = pref.getServerProfiles().find { it.id == activeIdFromPrefs }
                if (loadedProfile != null) {
                    loadedProfile?.let { Log.i(TAG, "Fallback: Successfully loaded profile from SharedPreferences. ID: ${it.id}") } // Keep Log.i - High-level outcome
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
        val easyssInfo = com.musan.easysstun.easyssInfo() 
        if (loadedProfile == null) {
            easyssInfo.valid = false
        } else {
            // This is the logic from Pref.getEasyssInfo(), adapted
            easyssInfo.valid = true
            easyssInfo.info = "${loadedProfile.server}:${loadedProfile.serverPort}"
            var sn = loadedProfile.serverNameIndication
            if (sn.isBlank()) {
                sn = loadedProfile.server
            }
            val cmdList = mutableListOf(
                "-s", loadedProfile.server,
                "-p", loadedProfile.serverPort,
                "-k", loadedProfile.password,
                "-m", loadedProfile.encryption,
                "-proxy-rule", loadedProfile.proxyRule,
                "-outbound-proto", loadedProfile.outbound,
                "-l", "2080", 
                "-t", "60", 
                "-log-level", loadedProfile.logLevel,
                "-disable-quic=${loadedProfile.disableQuic}",
                "-ipv6-rule", loadedProfile.ipv6Rule,
                "-sn", sn,
                "-enable-tun2socks=false",
                "-daemon=false"
            )
            if (loadedProfile.customCa.isNotBlank()) {
                val customCaFile = java.io.File(cacheDir, "easyss_custom_ca.conf") // use 'this.cacheDir' or 'applicationContext.cacheDir'
                try {
                    customCaFile.createNewFile()
                    java.io.FileOutputStream(customCaFile, false).use { fos ->
                        fos.write(loadedProfile.customCa.toByteArray())
                    }
                    cmdList.addAll(listOf("-ca-path", customCaFile.absolutePath))
                } catch (e: java.io.IOException) {
                    Log.e(TAG, "Error writing custom CA file", e)
                }
            }
            easyssInfo.cmdList = cmdList
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
        val builder: Builder = Builder()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
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

        for (appName in pref.getApps()) {
            try {
                builder.addDisallowedApplication(appName)
            } catch (e: PackageManager.NameNotFoundException) {
            }
        }
        session += "/per-App"


        val selfName = applicationContext.packageName
        try {
            builder.addDisallowedApplication(selfName)
        } catch (e: PackageManager.NameNotFoundException) {
        }

        

        builder.setSession(session)
        tunFd = builder.establish()
        if (tunFd != null) {
            Log.i(TAG, "startService: Successfully established new tunFd: ${tunFd!!.fd}") // Keep Log.i - Core lifecycle
        } else {
            Log.w(TAG, "startService: Failed to establish new tunFd, it's null.")
            // stopSelf() is called by the original code if tunFd is null, which is fine.
            stopSelf()
            return
        }

        processEasyJob = easyScope.launch {
            while (true) {

                try {
                    val libraryPath = applicationInfo.nativeLibraryDir.toString() + "/libeasyss.so"
                    var cmdList = listOf(libraryPath) + easyssInfo.cmdList
                    Log.d(TAG, "processEasyJob: Attempting to start libeasyss.so process. Command server: ${easyssInfo.cmdList.getOrNull(easyssInfo.cmdList.indexOf("-s") + 1)}, local port: ${easyssInfo.cmdList.getOrNull(easyssInfo.cmdList.indexOf("-l") + 1)}")
                    process = ProcessBuilder(cmdList).start()
                    Log.i(TAG, "processEasyJob: libeasyss.so process started (ProcessBuilder executed). isAlive: ${process.isAlive}") // Keep Log.i - Core lifecycle

                    Log.d("easyss", "msg=[EasyssTun] Connected to the service successfully.")
                    val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                    while (!processEasyJob.isCancelled) {
                        val line = bufferedReader.readLine()
                        if (line == null) break
                        Log.i("easyss", line)
                    }

                } catch (e: IOException) {
                    Log.e("easyss", "msg=[EasyssTun] IOException: " + e.message)
                } catch (e: InterruptedException) {
                    Log.e("easyss", "msg=[EasyssTun] InterruptedException: " + e.message)
                }

                finally {
                    Log.d(TAG, "processEasyJob: finally block entered. Process initialized: ${::process.isInitialized}")
                    if (::process.isInitialized) { // Check if process was even initialized
                        Log.d(TAG, "processEasyJob: About to call process.destroy(). Current process state: isAlive=${process.isAlive}")
                        process.destroy()
                        Log.d(TAG, "processEasyJob: process.destroy() called. About to call process.waitFor().")
                        val exitCode = process.waitFor()
                        Log.i(TAG, "processEasyJob: libeasyss.so process exited with code: $exitCode") // Keep Log.i - Core lifecycle
                    } else {
                        Log.w(TAG, "processEasyJob: finally block, process was not initialized.")
                    }
                    break // This break is for the while(true) loop inside processEasyJob
                }

            }
        }


        val socksPortForTProxy = pref.prefs.getString("socks_port", "2080")
        Log.d(TAG, "startService: Preparing tproxy.conf with SOCKS port: $socksPortForTProxy")
        /* TProxy */
        val tproxy_file = File(cacheDir, "tproxy.conf")
        try {
            tproxy_file.createNewFile()
            val fos = FileOutputStream(tproxy_file, false)
            var tproxy_conf = """misc:
  tcp-read-write-timeout: 300000
  udp-read-write-timeout: 15000

"""
            tproxy_conf += """socks5:
  port: ${pref.prefs.getString("socks_port", "2080")?.toInt()}
  address: '127.0.0.1'
  udp: 'udp'
"""
            fos.write(tproxy_conf.toByteArray())
            fos.close()
        } catch (e: IOException) {
            return
        }
        Log.d(TAG, "startService: Attempting to call TProxyStartService with tunFd: ${tunFd?.fd}.")
        TProxyStartService(tproxy_file.absolutePath, tunFd!!.fd)
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
            if(pref.isServiceEnabled || tunFd != null) { // If state indicates it might still be "on"
                 Log.w(TAG, "stopService: State indicates service might be partially running despite checks. Forcing finalization.")
                 actualFinalizeStop() // Ensure it's fully stopped.
            }
            return
        }
        Log.i(TAG, "stopService() called. Initiating shutdown sequence. Current tunFd: ${tunFd?.fd}") // Keep Log.i - User-driven or high-level state change
        stopForeground(true)

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
                    Log.d(TAG, "stopService: Cancelling and joining processEasyJob.")
                    try {
                        processEasyJob.cancelAndJoin() 
                        Log.d(TAG, "stopService: processEasyJob completed.")
                    } catch (e: Exception) {
                        Log.e(TAG, "stopService: Exception during processEasyJob.cancelAndJoin(): ${e.message}", e)
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
            startForeground(1, notify);
        } else {
            startForeground(1, notify, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        }
    }

    //     create NotificationChannel
    private fun initNotificationChannel(channelName: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val name: CharSequence = getString(R.string.app_name)
            val channel =
                NotificationChannel(channelName, name, NotificationManager.IMPORTANCE_LOW)
            notificationManager.createNotificationChannel(channel)

        }
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
        const val ACTION_SERVICE_STOPPED = "com.musan.easysstun.SERVICE_FULLY_STOPPED"
        private const val TAG = "TProxyServiceDiag"

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }
}