package com.easysstun

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "Stats"

/**
 * One sample of the local easyss stats endpoint (`GET /stats`).
 *
 * The endpoint is served by libeasyss on `127.0.0.1:<socksPort + 1000>`; the
 * JSON keys are the wire contract and are parsed once here instead of being
 * read ad hoc by every consumer (home screen and notification).
 */
internal data class StatsSnapshot(
    val avgRttMs: Double = 0.0,
    val conns: Int = 0,
    val bytesSent: Long = 0L,
    val bytesRecv: Long = 0L,
    val priorityConns: Int = 0,
    val bulkConns: Int = 0,
    val priorityActiveStreams: Int = 0,
    val bulkActiveStreams: Int = 0,
    val uptimeSeconds: Double = 0.0,
    val activeStreams: Int = 0,
    val slotRetiredDegraded: Int = 0,
    val connRotated: Int = 0,
    val slotProbeSlow: Int = 0,
    val downloadSpeed: String = "",
    val uploadSpeed: String = "",
    val peakDownloadSpeed: String = "",
    val peakUploadSpeed: String = "",
) {
    companion object {
        /** Parses a response body; null when it is not a JSON object. */
        fun parse(body: String): StatsSnapshot? =
            runCatching { parse(JSONObject(body)) }.getOrNull()

        /** Parses an already-decoded JSON object; missing keys fall back to zero/empty. */
        fun parse(json: JSONObject): StatsSnapshot = StatsSnapshot(
            avgRttMs = json.optDouble("avg_rtt_ms", 0.0),
            conns = json.optInt("conns", 0),
            bytesSent = json.optLong("bytes_sent", 0L),
            bytesRecv = json.optLong("bytes_recv", 0L),
            priorityConns = json.optInt("priority_conns", 0),
            bulkConns = json.optInt("bulk_conns", 0),
            priorityActiveStreams = json.optInt("priority_active_streams", 0),
            bulkActiveStreams = json.optInt("bulk_active_streams", 0),
            uptimeSeconds = json.optDouble("uptime_seconds", 0.0),
            activeStreams = json.optInt("active_streams", 0),
            slotRetiredDegraded = json.optInt("slot_retired_degraded", 0),
            connRotated = json.optInt("conn_rotated", 0),
            slotProbeSlow = json.optInt("slot_probe_slow", 0),
            downloadSpeed = json.optString("download_speed_human", ""),
            uploadSpeed = json.optString("upload_speed_human", ""),
            peakDownloadSpeed = json.optString("peak_download_speed_human", ""),
            peakUploadSpeed = json.optString("peak_upload_speed_human", ""),
        )
    }
}

/**
 * Fetches [StatsSnapshot]s from the local easyss HTTP endpoint.
 *
 * A failed request is a normal condition while the tunnel is starting or
 * stopping, so every transport/parse error is mapped to `null` and logged;
 * only coroutine cancellation is rethrown.
 */
internal class StatsClient(
    private val connectTimeoutMs: Int = CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = READ_TIMEOUT_MS,
) {
    suspend fun fetch(url: String): StatsSnapshot? = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                requestMethod = "GET"
            }
            val status = conn.responseCode
            if (status != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "stats endpoint returned HTTP $status")
                return@withContext null
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            StatsSnapshot.parse(body).also {
                if (it == null) Log.w(TAG, "stats endpoint returned malformed JSON")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "failed to fetch stats: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2_000
        const val READ_TIMEOUT_MS = 2_000
    }
}
