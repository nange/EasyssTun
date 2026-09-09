package com.easysstun

import android.annotation.SuppressLint
import android.content.Context
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

data class LogItem(val message: String, var time: String, var source: String, var level: String)

/**
 * Pure logcat line parsing, extracted from the UI layer so it can be unit
 * tested. The regexes and formats are unchanged from the original LogFragment
 * implementation.
 */
object LogParser {

    // App log tags to display in the log viewer
    val APP_LOG_TAGS = arrayOf(
        "GoLog", "TProxyServiceDiag", "MainFragment", "AppState",
        "Pref", "Profile", "LogFragment", "AppListAdapter", "LogStore"
    )

    // Written to logcat right before the LogStore reader starts; everything
    // before it belongs to an earlier process run and is dropped.
    const val MARKER = "===== log capture started ====="

    // Pattern A: Go slog format within the logcat message payload
    // Matches: time=... level=... source=... msg=...
    private val LOG_PATTERN_SLOG =
        Pattern.compile("time=([^ ]+) level=([^ ]+) source=([^ ]+) msg=(.*)")

    // Pattern B: Fallback for TProxyService direct log lines
    // Captures logcat wrapper: date, time, level char, and msg=... content
    private val LOG_PATTERN_FALLBACK =
        Pattern.compile("^(\\d{2}-\\d{2})\\s(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+easyss\\s+:\\s+msg=(.*)$")

    // Pattern C: Standard android.util.Log logcat output format
    // Captures: date, time, level char, tag, message
    // Example: "07-25 10:30:45.123  1234  5678 I TProxyServiceDiag: onStartCommand..."
    private val LOG_PATTERN_STANDARD =
        Pattern.compile("^(\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+(\\S+)\\s*:\\s*(.*)$")

    /** Parse one raw logcat line, or null when no known format matches. */
    fun parseLine(line: String): LogItem? {
        // Try Go slog pattern first
        var matcher = LOG_PATTERN_SLOG.matcher(line)
        if (matcher.find()) {
            val isoTime = matcher.group(1) ?: ""
            val level = matcher.group(2) ?: ""
            val source = matcher.group(3) ?: ""
            val msg = matcher.group(4) ?: ""
            return LogItem(msg, formatTime(isoTime), source, level)
        }
        // Try fallback pattern for TProxyService lines
        matcher = LOG_PATTERN_FALLBACK.matcher(line)
        if (matcher.find()) {
            val logDate = matcher.group(1) ?: ""
            val logTime = matcher.group(2) ?: ""
            val levelChar = matcher.group(3) ?: ""
            val msg = matcher.group(4) ?: ""
            return LogItem(msg, "$logDate $logTime", "", mapLevelChar(levelChar))
        }
        // Try standard android.util.Log pattern for app-side logs
        matcher = LOG_PATTERN_STANDARD.matcher(line)
        if (matcher.find()) {
            val logDate = matcher.group(1) ?: ""
            val logTime = matcher.group(2) ?: ""
            val levelChar = matcher.group(3) ?: ""
            val tag = matcher.group(4) ?: ""
            val msg = matcher.group(5) ?: ""
            return LogItem(msg, "$logDate $logTime", tag, mapLevelChar(levelChar))
        }
        return null
    }

    /**
     * Convert ISO 8601 timestamp to display format: "MM-DD HH:MM:SS.mmm"
     * Input:  "2026-07-19T19:08:48.135+08:00"
     * Output: "07-19 19:08:48.135"
     */
    fun formatTime(isoTime: String): String {
        val t = isoTime.indexOf('T')
        if (t < 0) return isoTime
        val datePart = isoTime.substring(5, 10)  // "07-19"
        val timePart = isoTime.substring(t + 1).takeWhile { c -> c != '+' && c != '-' && c != 'Z' }
        return "$datePart $timePart"
    }

    /** Map logcat level character to readable level string. */
    fun mapLevelChar(c: String): String = when (c) {
        "V" -> "VERBOSE"
        "D" -> "DEBUG"
        "I" -> "INFO"
        "W" -> "WARN"
        "E" -> "ERROR"
        "F" -> "FATAL"
        else -> c
    }

    /** Map a level string to numeric severity for filtering (higher = more severe). */
    fun levelSeverity(level: String): Int = when (level.uppercase()) {
        "VERBOSE" -> 2
        "DEBUG" -> 3
        "INFO" -> 4
        "WARN", "WARNING" -> 5
        "ERROR" -> 6
        "FATAL" -> 7
        else -> 4  // Default to INFO
    }

    /** True when the line carries the capture-start marker (see [MARKER]). */
    fun isStartMarker(line: String): Boolean = line.contains(MARKER)
}

/**
 * Thread-safe bounded buffer for parsed log items. Items keep a logical index
 * in the capture stream; when the buffer is full the oldest items are dropped
 * and [frontIndex] advances, so readers can tell how much history was lost.
 */
class LogBuffer(private val capacity: Int) {
    private val items = ArrayDeque<LogItem>()
    private var droppedCount = 0

    /** Append one item; drops the oldest when over capacity. Returns the new size. */
    @Synchronized
    fun append(item: LogItem): Int {
        items.addLast(item)
        if (items.size > capacity) {
            items.removeFirst()
            droppedCount++
        }
        return items.size
    }

    @Synchronized
    fun clear() {
        items.clear()
        droppedCount = 0
    }

    val size: Int
        @Synchronized get() = items.size

    /** Capture-stream index of the oldest retained item (0 until eviction). */
    val frontIndex: Int
        @Synchronized get() = droppedCount

    /**
     * Copy of the retained items whose capture-stream indices fall in
     * [fromIndex, toIndex); both bounds are clamped to the retained range.
     */
    @Synchronized
    fun sliceRange(fromIndex: Int, toIndex: Int): List<LogItem> {
        val from = maxOf(fromIndex, droppedCount)
        val to = minOf(toIndex, droppedCount + items.size)
        if (to <= from) return emptyList()
        return items.subList(from - droppedCount, to - droppedCount).toList()
    }
}

/**
 * Process-wide log capture: reads logcat for the app's tags for the whole
 * lifetime of the process, keeps the latest [MAX_BUFFER_SIZE] parsed items in
 * memory, and notifies observers so the log viewer can page through history.
 */
object LogStore {

    /** Maximum number of log items kept in memory (oldest are dropped). */
    const val MAX_BUFFER_SIZE = 2_000

    /** Number of items loaded per page in the log viewer. */
    const val PAGE_SIZE = 50

    private const val TAG = "LogStore"
    private const val RETRY_DELAY_MS = 3_000L
    private const val DEFAULT_MIN_SEVERITY = 4

    private val buffer = LogBuffer(MAX_BUFFER_SIZE)
    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Emits the current buffer size after each append (replay 1: a late
    // collector always learns the latest state). Emissions use emit() rather
    // than tryEmit(): with the small replay buffer an active collector can
    // make tryEmit drop values, and a dropped final notification would leave
    // the viewer lagging by one line instead of self-healing on the next one.
    private val _updates = MutableSharedFlow<Int>(replay = 1)
    val updates: SharedFlow<Int> = _updates.asSharedFlow()

    // The application context lives for the whole process, so holding it in a
    // process-lifetime singleton is not a leak (lint cannot see that).
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var pref: Pref? = null
    @Volatile
    private var minSeverity = DEFAULT_MIN_SEVERITY
    @Volatile
    private var minSeverityCheckedAt = 0L

    val size: Int get() = buffer.size
    val frontIndex: Int get() = buffer.frontIndex

    /**
     * Logical capture-stream index one past the newest retained item. Unlike
     * [size] (the number of retained items), this stays consistent with
     * [sliceRange] bounds even after ring eviction advances [frontIndex].
     * (Computed from two synchronized reads; a concurrent append may lag it by
     * one item, which the next update notification self-heals.)
     */
    val endIndex: Int get() = buffer.frontIndex + buffer.size

    /** Idempotent alias of [start], safe to call from UI code. */
    fun ensureStarted(context: Context) = start(context)

    /** Start capturing logcat output for the lifetime of the process. Idempotent. */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        pref = Pref(context.applicationContext)
        _updates.tryEmit(0)
        scope.launch {
            while (isActive) {
                try {
                    readLogcatLoop()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "logcat reader stopped unexpectedly (${e.message}); restarting in ${RETRY_DELAY_MS}ms")
                }
                delay(RETRY_DELAY_MS)
            }
        }
    }

    /** Copy of the retained items with capture-stream indices in [fromIndex, toIndex). */
    fun sliceRange(fromIndex: Int, toIndex: Int): List<LogItem> =
        buffer.sliceRange(fromIndex, toIndex)

    /**
     * Tails `logcat -s <tags>` without clearing the device buffer. A marker
     * line is written first; everything before it in the buffer belongs to an
     * earlier process run and is dropped, so the store only holds logs
     * produced by this process.
     */
    private suspend fun readLogcatLoop() {
        Log.i(TAG, LogParser.MARKER)
        val tags = LogParser.APP_LOG_TAGS.joinToString(" ")
        var inputStream: InputStream? = null
        var bufferedReader: BufferedReader? = null
        var process: Process? = null
        try {
            process = Runtime.getRuntime().exec("logcat -s $tags")
            inputStream = process.inputStream
            bufferedReader = BufferedReader(InputStreamReader(inputStream))
            var markerSeen = false
            while (true) {
                val line = bufferedReader.readLine() ?: break
                if (!markerSeen) {
                    if (LogParser.isStartMarker(line)) markerSeen = true
                    continue
                }
                val item = LogParser.parseLine(line) ?: continue
                if (LogParser.levelSeverity(item.level) >= currentMinSeverity()) {
                    append(item)
                }
            }
        } finally {
            inputStream?.close()
            bufferedReader?.close()
            process?.destroy()
        }
    }

    private suspend fun append(item: LogItem) {
        val newSize = buffer.append(item)
        _updates.emit(newSize)
    }

    /**
     * Minimum severity for capture-time filtering, read from the active
     * profile. Re-evaluated at most once per second: the profile lookup
     * deserializes the whole profile list and is too expensive per line.
     */
    private fun currentMinSeverity(): Int {
        val now = SystemClock.elapsedRealtime()
        if (now - minSeverityCheckedAt >= 1_000L) {
            minSeverityCheckedAt = now
            val level = pref?.getActiveProfile()?.logLevel ?: "info"
            minSeverity = when (level.lowercase()) {
                "debug" -> 3
                "info" -> 4
                "warn" -> 5
                "error" -> 6
                else -> DEFAULT_MIN_SEVERITY
            }
        }
        return minSeverity
    }

    internal fun appendForTest(item: LogItem) {
        // runBlocking is fine here: the test rule's UnconfinedTestDispatcher
        // runs the collector inline, so emit() delivers without blocking.
        val newSize = buffer.append(item)
        runBlocking { _updates.emit(newSize) }
    }

    internal fun resetForTest() {
        buffer.clear()
        _updates.tryEmit(0)
    }
}
