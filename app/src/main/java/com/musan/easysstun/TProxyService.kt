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
        if (tunFd == null) return
        stopForeground(true)

        /* TProxy */
        try {
            TProxyStopService()
        } catch (e: Exception) {
            Log.e("TProxyStopService", e.message.toString())
        }
        try {

            process.destroy()
            processEasyJob.cancel()
//            val exitCode = process.waitFor()
//            Log.i("easyss", "msg=[EasyssTun] Command exited with code: $exitCode")
        } catch (e: Exception) {
            Log.e("easyJob", e.message.toString())
        }
        /* VPN */
        try {
            tunFd!!.close()
        } catch (e: Exception) {
            Log.e("tunFd", e.message.toString())
        }
        tunFd = null

        stopSelf()
        System.exit(0)
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

        init {
            System.loadLibrary("hev-socks5-tunnel")
        }
    }
}