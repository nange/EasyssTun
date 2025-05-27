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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerializationException
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
        Log.i(TAG, "onStartCommand: Service starting with intent: $intent, action: ${intent?.action}, flags: $flags, startId: $startId")

        when (intent?.action) {
            Constants.ACTION_DISCONNECT -> {
                Log.i(TAG, "onStartCommand: Handling ACTION_DISCONNECT.")
                receivedProfileJson = null // Clear any old JSON on disconnect
                stopService()
                return START_NOT_STICKY
            }
            Constants.ACTION_CONNECT -> {
                Log.i(TAG, "onStartCommand: Handling ACTION_CONNECT.")
                receivedProfileJson = intent.getStringExtra(Constants.EXTRA_ACTIVE_SERVER_PROFILE_JSON)
                Log.i(TAG, "onStartCommand: Received profile JSON (length: ${receivedProfileJson?.length ?: "null"}) from Intent.")
            }
            else -> {
                // Handles null intent, null action, or other actions (e.g., service restart by system)
                Log.w(TAG, "onStartCommand: Intent is null, or action is not CONNECT/DISCONNECT (action: ${intent?.action}). Clearing receivedProfileJson.")
                receivedProfileJson = null
            }
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
            if (intent?.action == Constants.ACTION_PREFS_UPDATED) {
                Log.i(TAG, "prefsUpdatedReceiver: Received ACTION_PREFS_UPDATED.")
                if (tunFd != null) {
                    Log.i(TAG, "prefsUpdatedReceiver: Service is active (tunFd is not null), stopping service due to preference update.")
                    stopService()
                } else {
                    Log.i(TAG, "prefsUpdatedReceiver: Service is not active (tunFd is null), no action needed for preference update.")
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(prefsUpdatedReceiver, IntentFilter(Constants.ACTION_PREFS_UPDATED))
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

        val loadedProfile = loadProfile()
        val easyssInfo = pref.getEasyssInfo(loadedProfile) // Call modified getEasyssInfo

        Log.i(TAG, "Constructed easyssInfo.valid: ${easyssInfo.valid}")
        if (easyssInfo.valid) {
            val serverInCmd = easyssInfo.cmdList.indexOf("-s")
            val serverAddressInCmd = if (serverInCmd != -1 && serverInCmd + 1 < easyssInfo.cmdList.size) easyssInfo.cmdList[serverInCmd + 1] else "N/A"
            val portInCmd = easyssInfo.cmdList.indexOf("-p")
            val serverPortInCmd = if (portInCmd != -1 && portInCmd + 1 < easyssInfo.cmdList.size) easyssInfo.cmdList[portInCmd + 1] else "N/A"
            Log.i(TAG, "Constructed easyssInfo command params: Effective Server='${serverAddressInCmd}', Effective Port='${serverPortInCmd}'")
        }

        if (!easyssInfo.valid){
            Log.w(TAG, "startService: Constructed easyssInfo is invalid. Stopping service.")
            pref.isServiceEnabled = false // Ensure state is updated
            stopSelf() // Explicitly stop the service
            return
        }

        val builder = setupVpnBuilder() ?: run {
            Log.e(TAG, "startService: VPN Builder setup failed. Stopping service.")
            stopSelf()
            return
        }

        tunFd = builder.establish()
        if (tunFd == null) {
            Log.e(TAG, "startService: Failed to establish new tunFd (it is null). Stopping service.")
            stopSelf()
            return
        }
        Log.i(TAG, "startService: Successfully established new tunFd: ${tunFd!!.fd}")

        processEasyJob = launchEasyssProcess(easyssInfo.cmdList)

        val tproxyConfigFile = createTProxyConfig() ?: run {
            Log.e(TAG, "startService: Failed to create TProxy config file. Stopping service.")
            stopService() // Call full stopService to clean up tunFd etc.
            return
        }

        Log.i(TAG, "startService: Attempting to call TProxyStartService with tunFd: ${tunFd?.fd} and config: ${tproxyConfigFile.absolutePath}.")
        TProxyStartService(tproxyConfigFile.absolutePath, tunFd!!.fd)
        pref.isServiceEnabled = true // Use the property setter
        initNotificationChannel(Constants.NOTIFICATION_CHANNEL_NAME)
        createNotification(Constants.NOTIFICATION_CHANNEL_NAME)
    }

    fun stopService() {
        Log.i(TAG, "stopService: Initiating shutdown. Current tunFd: ${tunFd?.fd}, processEasyJob active: ${if (::processEasyJob.isInitialized) processEasyJob.isActive else "N/A"}}")

        if (tunFd == null && (!::processEasyJob.isInitialized || !processEasyJob.isActive)) {
            Log.i(TAG, "stopService: Service appears to be already stopped or not fully started.")
            if (::pref.isInitialized && (pref.isServiceEnabled || tunFd != null)) { // Check if state might be inconsistent
                 Log.w(TAG, "stopService: Service state (isServiceEnabled=${pref.isServiceEnabled}, tunFd=${tunFd?.fd}) indicates potential inconsistency. Forcing finalization.")
                 actualFinalizeStop()
            } else if (!::pref.isInitialized && tunFd != null) { // Pref not init but tunFd exists (edge case)
                Log.w(TAG, "stopService: Pref not initialized but tunFd exists. Forcing finalization.")
                actualFinalizeStop()
            }
            return
        }

        Log.i(TAG, "stopService: Proceeding with full shutdown sequence.")
        stopForeground(true) // Remove notification immediately

        serviceScope.launch {
            Log.i(TAG, "stopService [Coroutine]: Starting native cleanup tasks.")
            try {
                // 1. Stop TProxy (hev-socks5-tunnel)
                Log.i(TAG, "stopService [Coroutine]: Initiating TProxyStopService.")
                val tproxyStopJob = launch(Dispatchers.IO) { // Ensure native call is on IO
                    try {
                        TProxyStopService()
                        Log.i(TAG, "stopService [TProxyStop Coroutine]: TProxyStopService() completed.")
                    } catch (e: Throwable) {
                        Log.e(TAG, "stopService [TProxyStop Coroutine]: Exception during TProxyStopService: ${e.message}", e)
                    }
                }

                // 2. Stop libeasyss process
                Log.i(TAG, "stopService [Coroutine]: Checking processEasyJob state (initialized: ${::processEasyJob.isInitialized}, active: ${if (::processEasyJob.isInitialized) processEasyJob.isActive else "N/A"}).")
                if (::processEasyJob.isInitialized && processEasyJob.isActive) {
                    Log.i(TAG, "stopService [Coroutine]: processEasyJob is active. Cancelling and joining.")
                    try {
                        processEasyJob.cancelAndJoin()
                        Log.i(TAG, "stopService [Coroutine]: processEasyJob successfully cancelled and joined.")
                    } catch (e: Exception) {
                        Log.e(TAG, "stopService [Coroutine]: Exception during processEasyJob.cancelAndJoin(): ${e.message}", e)
                        // Fallback to direct process destruction if cancelAndJoin fails or if process is still alive
                        if (::process.isInitialized && process.isAlive) {
                            Log.w(TAG, "stopService [Coroutine]: Fallback - process still alive. Destroying directly.")
                            withContext(Dispatchers.IO) { process.destroyForcibly(); process.waitFor() }
                            Log.i(TAG, "stopService [Coroutine]: Fallback - Direct process destruction complete.")
                        }
                    }
                } else {
                    Log.i(TAG, "stopService [Coroutine]: processEasyJob was not active or not initialized.")
                    if (::process.isInitialized && process.isAlive) {
                         Log.w(TAG, "stopService [Coroutine]: processEasyJob not active, but process is alive. Destroying directly.")
                         try {
                            withContext(Dispatchers.IO) { // Ensure direct destruction happens on an IO-like context
                                process.destroyForcibly() // Use destroyForcibly for more immediate effect if needed
                                val exitCode = process.waitFor()
                                Log.i(TAG, "stopService [Coroutine]: Direct process destruction complete. Exit code: $exitCode")
                            }
                         } catch (e: Exception) {
                             Log.e(TAG, "stopService [Coroutine]: Exception during direct process destruction: ${e.message}", e)
                         }
                    } else {
                        Log.i(TAG, "stopService [Coroutine]: No active easyss process to destroy directly.")
                    }
                }
                
                // 3. Wait for TProxy to stop (join its job)
                Log.i(TAG, "stopService [Coroutine]: Waiting for TProxyStopService job to complete.")
                tproxyStopJob.join()
                Log.i(TAG, "stopService [Coroutine]: TProxyStopService job completed.")

            } catch (e: Exception) {
                Log.e(TAG, "stopService [Coroutine]: Unhandled exception during native cleanup phase: ${e.message}", e)
            } finally {
                Log.i(TAG, "stopService [Coroutine]: Native cleanup phase complete or errored. Proceeding with final Java-level cleanup on Main.immediate.")
                withContext(Dispatchers.Main.immediate) {
                     actualFinalizeStop()
                }
            }
        }
    }
    
    private fun actualFinalizeStop() {
        Log.i(TAG, "actualFinalizeStop: Finalizing service stop. Current tunFd: ${tunFd?.fd}")
        try {
            if (tunFd != null) {
                Log.i(TAG, "actualFinalizeStop: Closing tunFd (${tunFd!!.fd}).")
                tunFd?.close()
                Log.i(TAG, "actualFinalizeStop: tunFd closed.")
            } else {
                Log.i(TAG, "actualFinalizeStop: tunFd was already null.")
            }
        } catch (e: IOException) {
            Log.e(TAG, "actualFinalizeStop: IOException closing tunFd: ${e.message}", e)
        }
        tunFd = null 
        Log.i(TAG, "actualFinalizeStop: tunFd explicitly set to null.")

        if (::pref.isInitialized) {
            if (pref.isServiceEnabled) {
                pref.isServiceEnabled = false // Use the property setter
                Log.i(TAG, "actualFinalizeStop: Set pref.isServiceEnabled to false.")
            } else {
                Log.i(TAG, "actualFinalizeStop: pref.isServiceEnabled was already false.")
            }
        } else {
            Log.w(TAG, "actualFinalizeStop: Pref not initialized, cannot set isServiceEnabled to false.")
        }
        
        Log.i(TAG, "actualFinalizeStop: Calling stopSelf() to ensure service instance is terminated.")
        stopSelf()
        Log.i(TAG, "actualFinalizeStop: Broadcasting ACTION_SERVICE_STOPPED.")
        sendBroadcast(Intent(Constants.ACTION_SERVICE_STOPPED))
        Log.i(TAG, "actualFinalizeStop: Service fully stopped and broadcast sent.")
    }

    private fun setupVpnBuilder(): Builder? {
        val builder: Builder = Builder()
        var session = "IPv4/v6" // Initialize session string

        try {
            // General VPN parameters
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                builder.setMetered(false) // Allow usage on metered networks
            }
            builder.setBlocking(false) // Use non-blocking mode for establish() to avoid ANR
            builder.setMtu(Constants.DEFAULT_MTU) // Set Maximum Transmission Unit

            // Configure VPN interface addresses and DNS
            builder.addAddress("198.18.0.1", 32) // Private IP address for the VPN interface itself
            builder.addDnsServer(Constants.DEFAULT_DNS_SERVER)

            resources.getStringArray(R.array.bypass_private_route).forEach {
                val parts = it.split('/', limit = 2)
                if (parts.size == 2) {
                    builder.addRoute(parts[0], parts[1].toInt())
                } else {
                    Log.w(TAG, "setupVpnBuilder: Invalid route format in bypass_private_route: $it")
                }
            }

            builder.addAddress("2001:0db8:0:f101::1", 64)

            val currentPackageName = applicationContext.packageName
            val selectedApps = pref.getApps() ?: emptySet()

            // Add disallowed applications
            selectedApps.forEach { appName ->
                try {
                    if (appName != currentPackageName) { // Ensure service app itself is not added to disallowed list
                        builder.addDisallowedApplication(appName)
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.w(TAG, "setupVpnBuilder: Package not found for disallowed app: $appName", e)
                }
            }
            // Always disallow the service's own package to prevent VPN loops
            try {
                builder.addDisallowedApplication(currentPackageName)
                Log.i(TAG, "setupVpnBuilder: Successfully added own package ($currentPackageName) to disallowed applications.")
            } catch (e: PackageManager.NameNotFoundException) {
                Log.e(TAG, "setupVpnBuilder: CRITICAL - Could not find self package ($currentPackageName) to disallow. This could lead to VPN loops.", e)
                return null // Critical error, cannot proceed
            }

            if (selectedApps.isNotEmpty()) {
                session += "/per-App"
            }

            builder.setSession(session)
            Log.i(TAG, "setupVpnBuilder: VPN parameters configured with session: $session")
            return builder
        } catch (e: Exception) {
            Log.e(TAG, "setupVpnBuilder: Exception during VPN builder setup", e)
            return null
        }
    }

    private fun launchEasyssProcess(easyssCmdList: List<String>): Job {
        return easyScope.launch {
            // This outer while(true) loop implies an auto-restart mechanism for the easyss process.
            // If the process exits for any reason (crash or normal termination not initiated by stopService),
            // it will be restarted unless the managing coroutine (processEasyJob) itself is cancelled.
            // This ensures the easyss core process is kept alive as long as the VPN service is intended to be active.
            while (isActive) { // Loop only if the coroutine itself is active
                try {
                    val libraryPath = applicationInfo.nativeLibraryDir.toString() + "/libeasyss.so"
                    val fullCmdList = listOf(libraryPath) + easyssCmdList
                    Log.i(TAG, "launchEasyssProcess: Attempting to start libeasyss.so. Server: ${easyssCmdList.getOrNull(easyssCmdList.indexOf("-s") + 1)}, Local Port: ${easyssCmdList.getOrNull(easyssCmdList.indexOf("-l") + 1)}")
                    process = ProcessBuilder(fullCmdList).start()
                    Log.i(TAG, "launchEasyssProcess: libeasyss.so process started. isAlive: ${process.isAlive}")

                    // Log output from easyss process
                    val bufferedReader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String? = null // Explicitly initialized to null
                    while (isActive && bufferedReader.readLine().also { line = it } != null) { // Check isActive and readLine
                        Log.i("easyss", line!!) // Use "easyss" tag for its own logs; line is non-null here
                    }
                    Log.i(TAG, "launchEasyssProcess: Finished reading from easyss process output stream (isActive: $isActive).")

                    // Ensure to wait for process exit after loop breaks or is cancelled
                    if (::process.isInitialized && process.isAlive) {
                         val exitCode = process.waitFor()
                         Log.i(TAG, "launchEasyssProcess: libeasyss.so process exited with code (after log reading loop): $exitCode")
                    } else if (::process.isInitialized) {
                        Log.i(TAG, "launchEasyssProcess: easyss process was already exited before explicit waitFor(). Exit code: ${process.exitValue()}")
                    }

                } catch (e: IOException) {
                    Log.e(TAG, "launchEasyssProcess: IOException - ${e.message}", e)
                } catch (e: InterruptedException) {
                    Log.w(TAG, "launchEasyssProcess: Interrupted - ${e.message}", e)
                    Thread.currentThread().interrupt() // Restore interrupt status
                    if (!isActive) { // If interruption is due to coroutine cancellation
                        Log.i(TAG, "launchEasyssProcess: Interruption due to coroutine cancellation. Breaking loop.")
                        break // Exit while loop
                    }
                } catch (e: Exception) { // Catch any other unexpected exceptions
                    Log.e(TAG, "launchEasyssProcess: Unexpected error - ${e.message}", e)
                } finally {
                    Log.i(TAG, "launchEasyssProcess: 'finally' block. Process initialized: ${::process.isInitialized}")
                    if (::process.isInitialized) {
                        if (process.isAlive) {
                            Log.i(TAG, "launchEasyssProcess: Process was still alive in finally, destroying.")
                            process.destroy()
                        }
                        // It's good practice to wait for the process to ensure resources are cleaned up,
                        // but be mindful of blocking if this 'finally' is on a critical path without Dispatchers.IO
                        // However, this whole coroutine is on easyScope (Default dispatcher).
                        val exitCode = process.waitFor()
                        Log.i(TAG, "launchEasyssProcess: libeasyss.so process exited in 'finally' with code: $exitCode")
                    } else {
                        Log.w(TAG, "launchEasyssProcess: Process was not initialized in 'finally'.")
                    }

                    if (!isActive) { // If the coroutine (processEasyJob) was cancelled (e.g., by stopService)
                        Log.i(TAG, "launchEasyssProcess: Coroutine no longer active, breaking while(true) loop. This is expected during service stop.")
                        break // Exit the while(true) loop
                    }
                    // If the process died but the service is still supposed to be running,
                    // a small delay can prevent rapid, tight restart loops in case of persistent failure.
                    Log.w(TAG, "launchEasyssProcess: Process loop ended. If coroutine is still active, it will restart after a short delay.")
                    kotlinx.coroutines.delay(1000) // Delay 1 second before restarting
                }
            }
            Log.i(TAG, "launchEasyssProcess: Coroutine has completed (isActive: $isActive).")
        }
    }

    private fun createTProxyConfig(): File? {
        val socksPortForTProxy = pref.prefs.getString(Constants.PREF_SOCKS_PORT, Constants.DEFAULT_SOCKS_PORT)
        Log.i(TAG, "createTProxyConfig: Preparing tproxy.conf with SOCKS port: $socksPortForTProxy")
        val tproxyFile = File(cacheDir, Constants.TPROXY_CONF_FILE_NAME)
        try {
            tproxyFile.createNewFile() // Ensure file exists
            FileOutputStream(tproxyFile, false).use { fos -> // Overwrite if exists
                val tproxyConf = """misc:
  task-stack-size: 81920
  read-write-timeout: 1800000
tunnel:
  mtu: ${Constants.DEFAULT_MTU}
socks5:
  port: ${socksPortForTProxy?.toInt()}
  address: '127.0.0.1'
  udp: 'udp'
"""
                fos.write(tproxyConf.toByteArray())
            }
            Log.i(TAG, "createTProxyConfig: Successfully wrote tproxy.conf to ${tproxyFile.absolutePath}")
            return tproxyFile
        } catch (e: IOException) {
            Log.e(TAG, "createTProxyConfig: Error writing tproxy.conf", e)
            return null
        } catch (e: NumberFormatException) {
            Log.e(TAG, "createTProxyConfig: Error parsing SOCKS port for tproxy.conf: $socksPortForTProxy", e)
            return null
        }
    }

    private fun loadProfile(): ServerProfile? {
        var loadedProfile: ServerProfile? = null
        var profileSource = "Unknown" // For logging

        if (receivedProfileJson != null && receivedProfileJson!!.isNotBlank()) {
            Log.i(TAG, "Attempting to deserialize profile from Intent JSON.")
            val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
            try {
                loadedProfile = json.decodeFromString(ServerProfile.serializer(), receivedProfileJson!!)
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
            loadedProfile = pref.getActiveServerProfile() // Use existing Pref method
            if (loadedProfile != null) {
                Log.i(TAG, "Fallback: Successfully loaded profile from SharedPreferences. ID: ${loadedProfile.id}")
                // If profile was null before due to deserialization error, but fallback succeeded, update source
                if (profileSource.startsWith("Intent JSON Deserialization Error")) profileSource = "SharedPreferences Fallback (after Deserialization Error)"
                else profileSource = "SharedPreferences" // Correct source if loaded from Prefs directly
            } else {
                Log.w(TAG, "Fallback: No Active Server ID found or profile not found in SharedPreferences.")
            }
        }

        // Update Diagnostic Logging
        if (loadedProfile == null) {
            Log.w(TAG, "最终: Loaded ServerProfile is null (Source evaluation: $profileSource).")
        } else {
            Log.i(TAG, "最终: Loaded ServerProfile (Source: $profileSource) - ID: ${loadedProfile.id}, Name: '${loadedProfile.name}', Server: '${loadedProfile.server}', Port: '${loadedProfile.serverPort}'")
        }
        return loadedProfile
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
        startForeground(Constants.NOTIFICATION_ID, notify)
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
        // TODO: Implement or remove unused native method TProxyGetStats
        @JvmStatic
        private external fun TProxyGetStats(): LongArray

        // Moved to Constants.kt: ACTION_CONNECT, ACTION_DISCONNECT, ACTION_SERVICE_STOPPED
        private const val TAG = "TProxyServiceDiag"

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }
}