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
import kotlinx.coroutines.ensureActive // Explicit import for ensureActive
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
        return easyScope.launch { // easyScope is a CoroutineScope
            // Get a reference to this coroutine's job.
            // This job instance is what `processEasyJob` (the return value of this function) will point to.
            val currentJob = coroutineContext[Job]!!

            while (true) { // Outer loop for auto-restart
                var localProcess: Process? = null // Define localProcess to be used in this iteration
                try {
                    // Check for cancellation at the start of each attempt to run the process.
                    // ensureActive() will throw a CancellationException if the job is cancelled.
                    ensureActive() // Use unqualified ensureActive from coroutine scope

                    val libraryPath = applicationInfo.nativeLibraryDir.toString() + "/libeasyss.so"
                    val fullCmdList = listOf(libraryPath) + easyssCmdList
                    Log.i(TAG, "launchEasyssProcess: Attempting to start libeasyss.so. Server: ${easyssCmdList.getOrNull(easyssCmdList.indexOf("-s") + 1)}, Local Port: ${easyssCmdList.getOrNull(easyssCmdList.indexOf("-l") + 1)}")

                    // Initialize process within the try block so it's fresh for each attempt
                    localProcess = ProcessBuilder(fullCmdList).start()
                    process = localProcess // Assign to class member `process`
                    Log.i(TAG, "launchEasyssProcess: libeasyss.so process started. isAlive: ${localProcess.isAlive}")

                    val bufferedReader = BufferedReader(InputStreamReader(localProcess.inputStream))
                    var line: String? = null

                    // Inner loop for reading logs
                    while (true) {
                        // Check for cancellation before each potentially blocking readLine call.
                        ensureActive() // Use unqualified ensureActive from coroutine scope

                        val read = bufferedReader.readLine()
                        if (read == null) { // End of stream from process
                            Log.i(TAG, "launchEasyssProcess: End of stream from easyss process.")
                            break // Exit inner log reading loop
                        }
                        line = read
                        Log.i("easyss", line!!) // Log the output from the easyss process
                    }
                    Log.i(TAG, "launchEasyssProcess: Finished reading from easyss process output stream (job cancelled: ${currentJob.isCancelled}).")

                    // If we exited the log reading loop cleanly (readLine returned null), wait for the process to terminate.
                    if (localProcess.isAlive) {
                        val exitCode = localProcess.waitFor()
                        Log.i(TAG, "launchEasyssProcess: libeasyss.so process exited cleanly with code (after log reading loop): $exitCode")
                    } else {
                        Log.i(TAG, "launchEasyssProcess: easyss process was already exited after log reading. Exit code: ${localProcess.exitValue()}")
                    }

                } catch (e: kotlinx.coroutines.CancellationException) {
                    Log.i(TAG, "launchEasyssProcess: Coroutine cancelled (e.g., service stopping). Breaking outer process loop.", e)
                    break // Exit outer while(true) loop, effectively stopping process restarts.
                } catch (e: IOException) {
                    // Log IOExceptions (e.g., pipe closed, read error)
                    Log.e(TAG, "launchEasyssProcess: IOException - ${e.message}", e)
                    // The outer loop will cause a retry after a delay.
                } catch (e: InterruptedException) {
                    Log.w(TAG, "launchEasyssProcess: Coroutine or thread interrupted - ${e.message}", e)
                    Thread.currentThread().interrupt() // Restore interrupt status
                    // Check if the interruption was due to coroutine cancellation.
                    if (currentJob.isCancelled) {
                        Log.i(TAG, "launchEasyssProcess: Interruption was due to coroutine cancellation. Breaking outer process loop.")
                        break // Exit outer while(true) loop.
                    }
                    // If not cancelled, it might be an external interrupt. The loop may retry.
                } catch (e: Exception) { // Catch any other unexpected exceptions during process management.
                    Log.e(TAG, "launchEasyssProcess: Unexpected error in process management loop - ${e.message}", e)
                    // The outer loop will cause a retry after a delay.
                } finally {
                    Log.i(TAG, "launchEasyssProcess: 'finally' block of process attempt. localProcess initialized: ${localProcess != null}")
                    // Use localProcess for cleanup if it was initialized in this iteration's try block
                    localProcess?.let {
                        if (it.isAlive) {
                            Log.i(TAG, "launchEasyssProcess (finally): localProcess was still alive, destroying it.")
                            it.destroy()
                        }
                        val exitCode = it.waitFor()
                        Log.i(TAG, "launchEasyssProcess (finally): localProcess exited in 'finally' with code: $exitCode")
                    } ?: Log.w(TAG, "launchEasyssProcess (finally): localProcess was null, nothing to clean up for this attempt.")


                    // If the coroutine (currentJob) was cancelled (e.g., by stopService),
                    // ensureActive() at the top of the outer while(true) loop will catch it on the next iteration.
                    // If we are here due to a non-cancellation error, and the job is still not cancelled, delay and retry.
                    if (currentJob.isCancelled) {
                         Log.i(TAG, "launchEasyssProcess (finally): Coroutine is cancelled, ensuring outer loop terminates on next check.")
                         // ensureActive() at the loop top will handle breaking.
                    } else {
                        Log.w(TAG, "launchEasyssProcess (finally): Process attempt loop ended. If coroutine is still active, it will restart after a short delay.")
                        kotlinx.coroutines.delay(1000) // Delay 1 second before restarting to prevent rapid failures.
                    }
                }
            }
            Log.i(TAG, "launchEasyssProcess: Coroutine (Job: $currentJob) has completed its lifecycle (cancelled: ${currentJob.isCancelled}).")
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