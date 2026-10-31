package com.easysstun

import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [Build.VERSION_CODES.TIRAMISU])
class StatsSnapshotTest {

    @Test
    fun parse_mapsEveryWireField() {
        val json = JSONObject(
            """
            {
              "avg_rtt_ms": 12.5,
              "conns": 7,
              "bytes_sent": 1500,
              "bytes_recv": 2048,
              "priority_conns": 3,
              "bulk_conns": 4,
              "priority_active_streams": 2,
              "bulk_active_streams": 5,
              "uptime_seconds": 3661,
              "active_streams": 9,
              "slot_retired_degraded": 1,
              "conn_rotated": 6,
              "slot_probe_slow": 2,
              "download_speed_human": "1.5 MB/s",
              "upload_speed_human": "2.0 KB/s",
              "peak_download_speed_human": "10 MB/s",
              "peak_upload_speed_human": "5 MB/s"
            }
            """.trimIndent()
        )

        val snapshot = StatsSnapshot.parse(json)

        assertEquals(12.5, snapshot.avgRttMs, 0.0)
        assertEquals(7, snapshot.conns)
        assertEquals(1500L, snapshot.bytesSent)
        assertEquals(2048L, snapshot.bytesRecv)
        assertEquals(3, snapshot.priorityConns)
        assertEquals(4, snapshot.bulkConns)
        assertEquals(2, snapshot.priorityActiveStreams)
        assertEquals(5, snapshot.bulkActiveStreams)
        assertEquals(3661.0, snapshot.uptimeSeconds, 0.0)
        assertEquals(9, snapshot.activeStreams)
        assertEquals(1, snapshot.slotRetiredDegraded)
        assertEquals(6, snapshot.connRotated)
        assertEquals(2, snapshot.slotProbeSlow)
        assertEquals("1.5 MB/s", snapshot.downloadSpeed)
        assertEquals("2.0 KB/s", snapshot.uploadSpeed)
        assertEquals("10 MB/s", snapshot.peakDownloadSpeed)
        assertEquals("5 MB/s", snapshot.peakUploadSpeed)
    }

    @Test
    fun parse_missingKeysFallBackToDefaults() {
        val snapshot = StatsSnapshot.parse(JSONObject("{}"))

        assertEquals(0.0, snapshot.avgRttMs, 0.0)
        assertEquals(0, snapshot.conns)
        assertEquals(0L, snapshot.bytesSent)
        assertEquals("", snapshot.downloadSpeed)
        assertEquals("", snapshot.peakUploadSpeed)
    }

    @Test
    fun parse_malformedBodyReturnsNull() {
        assertNull(StatsSnapshot.parse("not json"))
        assertNull(StatsSnapshot.parse("[]"))
        assertNull(StatsSnapshot.parse(""))
    }
}
