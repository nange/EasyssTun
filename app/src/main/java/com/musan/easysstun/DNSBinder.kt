import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.lang.reflect.Method

class VpnDnsBinder(private val context: Context) {

    private val handler = Handler(Looper.getMainLooper())
    private val dnsRebindInterval = 30_000L // 30 seconds
    private lateinit var vpnNetwork: Network
    private val connectivityManager by lazy {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }

    private val dnsBinderRunnable = object : Runnable {
        override fun run() {
            bindDnsToVpnNetwork()
            handler.postDelayed(this, dnsRebindInterval)
        }
    }

    fun setVpnNetwork(network: Network) {
        vpnNetwork = network
    }

    fun startPeriodicBinding() {
        handler.postDelayed(dnsBinderRunnable, dnsRebindInterval)
    }

    fun stopPeriodicBinding() {
        handler.removeCallbacks(dnsBinderRunnable)
    }

    private fun bindDnsToVpnNetwork() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                // 绑定当前进程到VPN网络
                ConnectivityManager::class.java
                    .getMethod("bindProcessToNetwork", Network::class.java)
                    .invoke(connectivityManager, vpnNetwork)
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                // 兼容旧版本
                ConnectivityManager::class.java
                    .getMethod("setProcessDefaultNetwork", Network::class.java)
                    .invoke(connectivityManager, vpnNetwork)
            }
        } catch (e: Exception) {
            Log.e("VpnDnsBinder", "Failed to bind process to network", e)
        }

        // 刷新DNS缓存
        flushDnsCache()
    }

    private fun flushDnsCache() {
        try {
            // 方法1: 反射调用系统方法刷新DNS缓存
            val networkUtils = Class.forName("android.net.NetworkUtils")
            val flushMethod: Method = networkUtils.getDeclaredMethod("flushVmDnsCache")
            flushMethod.isAccessible = true
            flushMethod.invoke(null)
            Log.d("VpnDnsBinder", "DNS cache flushed via NetworkUtils")
        } catch (e: Exception) {
            Log.e("VpnDnsBinder", "Failed to flush DNS cache via NetworkUtils", e)

            try {

                val flushDnsCacheMethod: Method = ConnectivityManager::class.java
                    .getDeclaredMethod("flushDnsCache")
                flushDnsCacheMethod.isAccessible = true
                flushDnsCacheMethod.invoke(connectivityManager)  // 使用类变量connectivityManager
                Log.d("VpnDnsBinder", "DNS cache flushed via ConnectivityManager")
            } catch (ex: Exception) {
                Log.e("VpnDnsBinder", "Failed to flush DNS cache via ConnectivityManager", ex)

                try {
                    context.sendBroadcast(Intent("android.intent.action.CLEAR_DNS_CACHE"))
                    Log.d("VpnDnsBinder", "Sent CLEAR_DNS_CACHE broadcast")
                } catch (ex2: Exception) {
                    Log.e("VpnDnsBinder", "Failed to send CLEAR_DNS_CACHE broadcast", ex2)
                }
            }
        }
    }
}