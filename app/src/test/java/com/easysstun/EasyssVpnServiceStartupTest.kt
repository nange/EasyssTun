package com.easysstun

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import java.net.InetAddress
import java.net.ServerSocket

/**
 * 覆盖启动序列的就绪探测（waitForSocksReady / probeSocksAccept）：时钟与 probe 注入，无需外部 SOCKS5 服务或 Android VPN。
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.TIRAMISU])
class EasyssVpnServiceStartupTest {

    private fun newService(): EasyssVpnService =
        Robolectric.buildService(EasyssVpnService::class.java).get()

    @Test
    fun waitForSocksReady_returnsTrueWhenProbeSucceedsImmediately() {
        val service = newService()
        var clock = 0L
        val ready = service.waitForSocksReady(
            port = 2080,
            timeoutMs = 1_000,
            nowMillis = { clock },
            probe = { true }
        )
        assertTrue(ready)
    }

    @Test
    fun waitForSocksReady_returnsFalseWhenProbeNeverSucceeds() {
        val service = newService()
        var clock = 0L
        val ready = service.waitForSocksReady(
            port = 2080,
            timeoutMs = 100,
            nowMillis = { clock += 100; clock },
            probe = { false }
        )
        assertFalse(ready)
    }

    @Test
    fun waitForSocksReady_succeedsOnceProbeFlippedBeforeDeadline() {
        val service = newService()
        var ready = false
        var attempts = 0
        var clock = 0L
        val result = service.waitForSocksReady(
            port = 2080,
            timeoutMs = 1_000,
            nowMillis = { clock += 50; clock },
            probe = {
                attempts++
                if (attempts >= 3) ready = true
                ready
            }
        )
        assertTrue(result)
        assertTrue(attempts >= 3)
    }

    @Test
    fun probeSocksAccept_acceptsRealSocks5Greeting() {
        // 最小 SOCKS5 服务端：对任意问候回 "05 00"（免认证）。
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = serverSocket.localPort
        val thread = Thread {
            runCatching {
                serverSocket.accept().use { s ->
                    s.soTimeout = 2_000
                    val greeting = ByteArray(3)
                    s.inputStream.read(greeting)
                    s.outputStream.write(byteArrayOf(0x05, 0x00))
                }
            }
        }
        thread.start()
        try {
            val service = newService()
            assertTrue("probe should succeed against a real SOCKS5 responder", service.probeSocksAccept(port))
        } finally {
            thread.join(2_000)
            serverSocket.close()
        }
    }

    @Test
    fun probeSocksAccept_rejectsNonSocksReply() {
        // 回垃圾数据而非 SOCKS5 方法选择的 TCP 监听器必须让探测失败。
        val serverSocket = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port = serverSocket.localPort
        val thread = Thread {
            runCatching {
                serverSocket.accept().use { s ->
                    s.soTimeout = 2_000
                    val greeting = ByteArray(3)
                    s.inputStream.read(greeting)
                    s.outputStream.write(byteArrayOf(0x42, 0x42))
                }
            }
        }
        thread.start()
        try {
            val service = newService()
            assertFalse("garbage reply must fail the probe", service.probeSocksAccept(port))
        } finally {
            thread.join(2_000)
            serverSocket.close()
        }
    }
}
