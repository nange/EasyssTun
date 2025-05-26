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
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.join
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader


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
        if (intent != null && ACTION_DISCONNECT == intent.action) {
            Log.i(TAG, "onStartCommand: Received ACTION_DISCONNECT.")
            receivedProfileJson = null // Clear any old JSON on disconnect
            stopService()
            return START_NOT_STICKY
        }

        if (intent != null && intent.action == ACTION_CONNECT) { // Assuming ACTION_CONNECT is the trigger
            receivedProfileJson = intent.getStringExtra("com.musan.easysstun.ACTIVE_SERVER_PROFILE_JSON_EXTRA")
            Log.i(TAG, "onStartCommand: Received profile JSON (length: ${receivedProfileJson?.length ?: "null"}) via Intent.")
        } else {
            // If intent is null or action isn't connect (and not disconnect), clear receivedProfileJson
            // to ensure fallback or default behavior if service is restarted by system.
            Log.w(TAG, "onStartCommand: No profile JSON in Intent or unexpected action. Intent: $intent")
            receivedProfileJson = null
        }

        try {
            startService()
        } catch (e: java.io.IOException) { // Ensure correct import for IOException
            Log.e(TAG, "Failed to start service due to IOException", e)
            // Consider how to handle this, maybe stopSelf()
            throw RuntimeException(e)
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
        registerReceiver(prefsUpdatedReceiver, IntentFilter("prefs_updated"))
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(prefsUpdatedReceiver)
        Log.i(TAG, "onDestroy: Cancelling serviceScope.")
        serviceScope.cancel() // Cancel all coroutines launched by serviceScope
        Log.i(TAG, "onDestroy: Service being destroyed.")
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
        var profileSource = "Unknown" // For logging

        if (receivedProfileJson != null && receivedProfileJson!!.isNotBlank()) {
            Log.i(TAG, "Attempting to deserialize profile from Intent JSON.")
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true } 
            try {
                loadedProfile = json.decodeFromString(com.musan.easysstun.ServerProfile.serializer(), receivedProfileJson!!)
                profileSource = "Intent JSON"
                Log.i(TAG, "Successfully deserialized profile from Intent. ID: ${loadedProfile?.id}")
            } catch (e: SerializationException) {
                Log.e(TAG, "Error deserializing profile from Intent JSON: ${e.message}. Falling back.")
                profileSource = "Intent JSON Deserialization Error -> Fallback"
                // loadedProfile remains null, will trigger fallback
            }
        } else {
            Log.i(TAG, "No profile JSON in Intent. Falling back to SharedPreferences.")
            profileSource = "SharedPreferences Fallback"
        }

        if (loadedProfile == null) { // Fallback logic
            Log.i(TAG, "Executing fallback: Loading profile via SharedPreferences.")
            // This part uses pref to get active ID then find in list
            val activeIdFromPrefs = pref.prefs.getString(com.musan.easysstun.Pref.ACTIVE_SERVER_ID, null) 
            Log.i(TAG, "Fallback: Active Server ID from SharedPreferences: '$activeIdFromPrefs'")
            if (activeIdFromPrefs != null) {
                loadedProfile = pref.getServerProfiles().find { it.id == activeIdFromPrefs }
                if (loadedProfile != null) {
                    Log.i(TAG, "Fallback: Successfully loaded profile from SharedPreferences. ID: ${loadedProfile.id}")
                    // If profile was null before due to deserialization error, but fallback succeeded, update source
                    if (profileSource.startsWith("Intent JSON Deserialization Error")) profileSource = "SharedPreferences Fallback (after Deserialization Error)"
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
            Log.i(TAG, "最终: Loaded ServerProfile (Source: $profileSource) - ID: ${loadedProfile.id}, Name: '${loadedProfile.name}', Server: '${loadedProfile.server}', Port: '${loadedProfile.serverPort}'")
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
        Log.i(TAG, "Constructed easyssInfo.valid: ${easyssInfo.valid}")
        if (easyssInfo.valid) {
            val serverInCmd = easyssInfo.cmdList.indexOf("-s")
            val serverAddressInCmd = if (serverInCmd != -1 && serverInCmd + 1 < easyssInfo.cmdList.size) easyssInfo.cmdList[serverInCmd + 1] else "N/A"
            val portInCmd = easyssInfo.cmdList.indexOf("-p")
            val serverPortInCmd = if (portInCmd != -1 && portInCmd + 1 < easyssInfo.cmdList.size) easyssInfo.cmdList[portInCmd + 1] else "N/A"
            Log.i(TAG, "Constructed easyssInfo command params: Effective Server='${serverAddressInCmd}', Effective Port='${serverPortInCmd}'")
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
        builder.addDnsServer("1.1.1.1")

        resources.getStringArray(R.array.bypass_private_route).forEach {
            val parts = it.split('/', limit = 2)
            builder.addRoute(parts[0], parts[1].toInt())
        }

        session += "IPv4/v6"

        builder.addAddress("2001:0db8:0:f101::1", 64)

        for (appName in pref.getApps()!!) {
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

        //test
//        builder.addAllowedApplication("com.tencent.mm")

        builder.setSession(session)
        tunFd = builder.establish()
        if (tunFd != null) {
            Log.i(TAG, "startService: Successfully established new tunFd: ${tunFd!!.fd}")
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
                    Log.i(TAG, "processEasyJob: Attempting to start libeasyss.so process. Command server: ${easyssInfo.cmdList.getOrNull(easyssInfo.cmdList.indexOf("-s") + 1)}, local port: ${easyssInfo.cmdList.getOrNull(easyssInfo.cmdList.indexOf("-l") + 1)}")
                    process = ProcessBuilder(cmdList).start()
                    Log.i(TAG, "processEasyJob: libeasyss.so process started (ProcessBuilder executed). isAlive: ${process.isAlive}")

                    Log.d("easyss", "msg=[EasyssTun] Connected to the service successfully.") // This is an existing log, TAG is different
                    val bufferedReader =
                        BufferedReader(InputStreamReader(process.inputStream))

                    while (!processEasyJob.isCancelled) {
                        var line: String = bufferedReader.readLine()
                        if (line != null) {
                            Log.i("easyss", line)
                        }
                    }

//                    while (isActive) {
//                        if (bufferedReader.ready()) {
//                            val line = bufferedReader.readLine()
//                            if (line != null) {
//                                Log.i("easyss", line)
//                            }
//                        } else {
//                            delay(100)
//                        }
//                    }
                } catch (e: IOException) {
                    Log.e("easyss", "msg=[EasyssTun] IOException: " + e.message)
                } catch (e: InterruptedException) {
                    Log.e("easyss", "msg=[EasyssTun] InterruptedException: " + e.message)
                }

                finally {
                    Log.i(TAG, "processEasyJob: finally block entered. Process initialized: ${::process.isInitialized}")
                    if (::process.isInitialized) { // Check if process was even initialized
                        Log.i(TAG, "processEasyJob: About to call process.destroy(). Current process state: isAlive=${process.isAlive}")
                        process.destroy()
                        Log.i(TAG, "processEasyJob: process.destroy() called. About to call process.waitFor().")
                        val exitCode = process.waitFor()
                        Log.i(TAG, "processEasyJob: libeasyss.so process exited with code: $exitCode")
                    } else {
                        Log.w(TAG, "processEasyJob: finally block, process was not initialized.")
                    }
                    break // This break is for the while(true) loop inside processEasyJob
                }

            }
        }


        val socksPortForTProxy = pref.prefs.getString("socks_port", "2080")
        Log.i(TAG, "startService: Preparing tproxy.conf with SOCKS port: $socksPortForTProxy")
        /* TProxy */
        val tproxy_file = File(cacheDir, "tproxy.conf")
        try {
            tproxy_file.createNewFile()
            val fos = FileOutputStream(tproxy_file, false)
            var tproxy_conf = """misc:
  task-stack-size: 81920
  read-write-timeout: 1800000
tunnel:
  mtu: 8500
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
        Log.i(TAG, "startService: Attempting to call TProxyStartService with tunFd: ${tunFd?.fd}.")
        TProxyStartService(tproxy_file.absolutePath, tunFd!!.fd)
        pref.prefs.edit { apply { putBoolean("enable", true) } }
        val channelName = "easysstun"
        initNotificationChannel(channelName)
        createNotification(channelName)
    }

    fun stopService() {
        if (tunFd == null && (!::processEasyJob.isInitialized || !processEasyJob.isActive)) {
            Log.i(TAG, "stopService: called but appears already stopped or not fully started.")
            // It's possible actualFinalizeStop() was not called if a previous stop was interrupted.
            // Check pref state and call actualFinalizeStop if needed, or just return.
            if(pref.isServiceEnabled || tunFd != null) { // If state indicates it might still be "on"
                 Log.w(TAG, "stopService: State indicates service might be partially running despite checks. Forcing finalization.")
                 actualFinalizeStop() // Ensure it's fully stopped.
            }
            return
        }
        Log.i(TAG, "stopService() called. Initiating shutdown sequence. Current tunFd: ${tunFd?.fd}")
        stopForeground(true)

        serviceScope.launch { 
            try {
                // 1. Stop TProxy (hev-socks5-tunnel)
                val tproxyStopJob = launch { 
                    try {
                        Log.i(TAG, "stopService: TProxyStopService coroutine calling TProxyStopService()")
                        TProxyStopService()
                        Log.i(TAG, "stopService: TProxyStopService coroutine TProxyStopService() completed.")
                    } catch (e: Throwable) {
                        Log.e(TAG, "stopService: Exception during TProxyStopService: ${e.message}", e)
                    }
                }

                // 2. Stop libeasyss process
                if (::processEasyJob.isInitialized && processEasyJob.isActive) {
                    Log.i(TAG, "stopService: Cancelling and joining processEasyJob.")
                    try {
                        processEasyJob.cancelAndJoin() 
                        Log.i(TAG, "stopService: processEasyJob completed.")
                    } catch (e: Exception) {
                        Log.e(TAG, "stopService: Exception during processEasyJob.cancelAndJoin(): ${e.message}", e)
                    }
                } else {
                    Log.i(TAG, "stopService: processEasyJob was not active or initialized.")
                    if (::process.isInitialized && process.isAlive) {
                         Log.w(TAG, "stopService: processEasyJob not active, but process is. Destroying directly.")
                         try {
                            // Ensure direct destruction happens on an IO-like context
                            withContext(Dispatchers.IO) { 
                                process.destroy() 
                                process.waitFor() 
                            }
                            Log.i(TAG, "stopService: Direct process destruction complete.")
                         } catch (e: Exception) {
                             Log.e(TAG, "stopService: Exception during direct process destruction: ${e.message}", e)
                         }
                    }
                }
                
                // 3. Wait for TProxy to stop
                Log.i(TAG, "stopService: Waiting for TProxyStopService job (tproxyStopJob) to complete.")
                tproxyStopJob.join()
                Log.i(TAG, "stopService: TProxyStopService job (tproxyStopJob) completed.")

            } catch (e: Exception) {
                Log.e(TAG, "stopService: Unhandled exception during native cleanup phase: ${e.message}", e)
            } finally {
                Log.i(TAG, "stopService: Native cleanup phase complete or errored. Proceeding with final Java-level cleanup.")
                withContext(Dispatchers.Main.immediate) { 
                     actualFinalizeStop() 
                }
            }
        }
    }
    
    private fun actualFinalizeStop() {
        Log.i(TAG, "actualFinalizeStop: Finalizing service stop.")
        try {
            Log.i(TAG, "actualFinalizeStop: Attempting to close tunFd: ${tunFd?.fd}.")
            tunFd?.close() 
        } catch (e: IOException) {
            Log.e(TAG, "actualFinalizeStop: Exception closing tunFd: ${e.message}", e)
        }
        tunFd = null 
        Log.i(TAG, "actualFinalizeStop: tunFd set to null.")

        if (::pref.isInitialized) {
            pref.isServiceEnabled = false
        } else {
            Log.w(TAG, "actualFinalizeStop: Pref not initialized, cannot set isServiceEnabled.")
        }
        
        Log.i(TAG, "actualFinalizeStop: Calling stopSelf().")
        stopSelf()
        Log.i(TAG, "actualFinalizeStop: Broadcasting ACTION_SERVICE_STOPPED")
        sendBroadcast(Intent(ACTION_SERVICE_STOPPED))
        Log.i(TAG, "actualFinalizeStop: Service fully stopped and broadcast sent.")
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
        startForeground(1, notify)
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