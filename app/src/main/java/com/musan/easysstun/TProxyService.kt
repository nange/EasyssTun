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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStreamReader


class TProxyService : VpnService() {
    private var tunFd: ParcelFileDescriptor? = null

    private lateinit var pref: Pref
    private val easyJob = Job()
    private val easyScope = CoroutineScope(Dispatchers.Default + easyJob)
    private lateinit var processEasyJob: Job
    lateinit var process: Process

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        if (intent != null && ACTION_DISCONNECT == intent.action) {
            stopService()
            return START_NOT_STICKY
        }
        try {
            startService()
        } catch (e: IOException) {
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
        stopSelf()
    }

    override fun onRevoke() {
        super.onRevoke()
        stopService()
    }

    @Throws(IOException::class)
    fun startService() {
        if (tunFd != null) return

        pref = Pref(this)
        var easyssInfo = pref.getEasyssInfo()
        if (!easyssInfo.valid){
            pref.isServiceEnabled = false
            return
        }

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
        if (tunFd == null) {
            stopSelf()
            return
        }

        processEasyJob = easyScope.launch {
            while (true) {

                try {
                    val libraryPath = applicationInfo.nativeLibraryDir.toString() + "/libeasyss.so"
                    var cmdList = listOf(libraryPath) + easyssInfo.cmdList
                    Log.i("easyss", cmdList.toString())
                    process = ProcessBuilder(cmdList).start()

                    Log.d("easyss", "msg=[EasyssTun] Connected to the service successfully.")
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
                    process.destroy()
                    val exitCode = process.waitFor()
                    Log.i("easyss", "msg=[EasyssTun] Command exited with code: $exitCode")
                    break
                }

            }
        }



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
        TProxyStartService(tproxy_file.absolutePath, tunFd!!.fd)
        pref.prefs.edit { apply { putBoolean("enable", true) } }
        val channelName = "easysstun"
        initNotificationChannel(channelName)
        createNotification(channelName)
    }

    fun stopService() {
        if (tunFd == null) {
            Log.i("easyss", "stopService: called but tunFd is null, already stopped or not started.")
            return
        }
        Log.i("easyss", "stopService: Initiating stop sequence.")
        stopForeground(true) // Consider stopForeground(STOP_FOREGROUND_REMOVE) for API 24+

        // Launch TProxyStopService in a separate coroutine
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Log.i("easyss", "TProxyStopService coroutine: Calling TProxyStopService()")
                TProxyStopService()
                Log.i("easyss", "TProxyStopService coroutine: TProxyStopService() completed.")
            } catch (e: Throwable) { // Catch Throwable for native errors
                Log.e("TProxyStopService", "Exception during TProxyStopService: " + (e.message ?: "Unknown error"), e)
            }
        }

        // Cleanup libeasyss.so process and related job
        try {
            if (::process.isInitialized && process.isAlive) {
                Log.i("easyss", "stopService: Destroying libeasyss.so process.")
                process.destroy()
            } else {
                Log.i("easyss", "stopService: libeasyss.so process not initialized or not alive.")
            }

            if (::processEasyJob.isInitialized && processEasyJob.isActive) {
                Log.i("easyss", "stopService: Cancelling processEasyJob coroutine.")
                processEasyJob.cancel()
            } else {
                Log.i("easyss", "stopService: processEasyJob not initialized or not active.")
            }
        } catch (e: Exception) {
            Log.e("easyss", "Exception during process/job cleanup: " + (e.message ?: "Unknown error"), e)
        }

        // Close VPN tunnel file descriptor
        try {
            Log.i("easyss", "stopService: Closing tunFd.")
            tunFd?.close() // Use safe call
        } catch (e: IOException) { // Catch specific IOException
            Log.e("easyss", "Exception closing tunFd: " + (e.message ?: "Unknown error"), e)
        }
        tunFd = null

        // Update preference state
        if (::pref.isInitialized) { // Ensure pref is initialized
            pref.isServiceEnabled = false
            Log.i("easyss", "stopService: Set pref.isServiceEnabled to false.")
        } else {
            Log.i("easyss", "stopService: Pref not initialized, cannot set isServiceEnabled.")
        }
        
        Log.i("easyss", "stopService: Calling stopSelf().")
        stopSelf()
        Log.i("easyss", "stopService: Sequence fully dispatched.")

        // Broadcast that the service has fully stopped
        Log.i("easyss", "stopService: Broadcasting ACTION_SERVICE_STOPPED")
        val broadcastIntent = Intent(ACTION_SERVICE_STOPPED)
        sendBroadcast(broadcastIntent)
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

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }
}