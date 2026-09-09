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
 * Tests for the proxy-readiness probe used by the startup sequence
 * (waitForSocksReady / probeSocksAccept). The clock and probe are injected,
 * so no real SOCKS5 server or Android VPN is required; the probe test speaks
 * a minimal real SOCKS5 greeting over a local socket.
 */
@RunWith(AndroidJUnit4::class)
@Config(manifest = Config.NONE, sdk = [Build.VERSION_CODES.TIRAMISU])
class TProxyServiceStartupTest {

    private fun newService(): TProxyService =
        Robolectric.buildService(TProxyService::class.java).get()

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
        // Minimal SOCKS5 server: reply "05 00" (no auth) to any greeting.
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
        // A TCP listener that answers with garbage instead of a SOCKS5
        // method selection must fail the probe.
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
