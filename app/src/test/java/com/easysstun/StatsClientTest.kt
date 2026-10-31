package com.easysstun

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class StatsClientTest {

    private lateinit var server: HttpServer
    private var status = 200
    private var body = "{}"

    @Before
    fun setUp() {
        server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/stats") { exchange: HttpExchange ->
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.stop(0)
    }

    private fun url(): String = "http://127.0.0.1:${server.address.port}/stats"

    @Test
    fun fetch_returnsParsedSnapshotOnHttp200() = runBlocking {
        body = """{"avg_rtt_ms": 8.5, "conns": 2, "download_speed_human": "1.0 MB/s"}"""

        val snapshot = StatsClient().fetch(url())

        assertEquals(8.5, snapshot!!.avgRttMs, 0.0)
        assertEquals(2, snapshot.conns)
        assertEquals("1.0 MB/s", snapshot.downloadSpeed)
    }

    @Test
    fun fetch_returnsNullOnNon200() = runBlocking {
        status = 500
        body = """{"avg_rtt_ms": 8.5}"""

        assertNull(StatsClient().fetch(url()))
    }

    @Test
    fun fetch_returnsNullOnMalformedBody() = runBlocking {
        body = "not json"

        assertNull(StatsClient().fetch(url()))
    }

    @Test
    fun fetch_returnsNullWhenNothingIsListening() = runBlocking {
        val port = server.address.port
        server.stop(0)

        assertNull(StatsClient(connectTimeoutMs = 500, readTimeoutMs = 500).fetch("http://127.0.0.1:$port/stats"))
    }
}
