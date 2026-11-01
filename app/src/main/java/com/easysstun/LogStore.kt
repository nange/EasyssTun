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
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicBoolean
import java.util.regex.Pattern

data class LogItem(val message: String, var time: String, var source: String, var level: String)

object LogParser {

    // 采集启动前先写这一行；缓冲区里它之前的内容属于更早的进程，全部丢弃。
    const val MARKER = "===== log capture started ====="

    // 按此顺序尝试：slog 行外面也套着 logcat 包装，先匹配 slog 才能拿到结构化字段。
    private val LOG_PATTERN_SLOG =
        Pattern.compile("time=([^ ]+) level=([^ ]+) source=([^ ]+) msg=(.*)")

    private val LOG_PATTERN_FALLBACK =
        Pattern.compile("^(\\d{2}-\\d{2})\\s(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+easyss\\s+:\\s+msg=(.*)$")

    private val LOG_PATTERN_STANDARD =
        Pattern.compile("^(\\d{2}-\\d{2})\\s+(\\d{2}:\\d{2}:\\d{2}\\.\\d{3})\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+(\\S+)\\s*:\\s*(.*)$")

    fun parseLine(line: String): LogItem? {
        var matcher = LOG_PATTERN_SLOG.matcher(line)
        if (matcher.find()) {
            val isoTime = matcher.group(1) ?: ""
            val level = matcher.group(2) ?: ""
            val source = matcher.group(3) ?: ""
            val msg = unquoteSlogValue(matcher.group(4) ?: "")
            return LogItem(msg, formatTime(isoTime), source, level)
        }
        matcher = LOG_PATTERN_FALLBACK.matcher(line)
        if (matcher.find()) {
            val logDate = matcher.group(1) ?: ""
            val logTime = matcher.group(2) ?: ""
            val levelChar = matcher.group(3) ?: ""
            val msg = matcher.group(4) ?: ""
            return LogItem(msg, "$logDate $logTime", "", mapLevelChar(levelChar))
        }
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

    /** 还原 slog 的引号转义；值含空格、`=` 或引号时 slog 会加引号，未加引号的值原样返回。 */
    fun unquoteSlogValue(value: String): String {
        if (!value.startsWith('"')) return value
        val out = StringBuilder(value.length)
        var i = 1
        while (i < value.length) {
            val c = value[i]
            if (c == '\\' && i + 1 < value.length) {
                when (value[i + 1]) {
                    '"' -> { out.append('"'); i += 2; continue }
                    '\\' -> { out.append('\\'); i += 2; continue }
                    'n' -> { out.append('\n'); i += 2; continue }
                    't' -> { out.append('\t'); i += 2; continue }
                    'r' -> { out.append('\r'); i += 2; continue }
                }
            } else if (c == '"') {
                // 闭合引号之后是 slog 追加的其它属性，原样保留。
                out.append(value, i + 1, value.length)
                return out.toString()
            }
            out.append(c)
            i++
        }
        // 引号未闭合说明该行被截断，原样返回。
        return value
    }

    /** ISO 8601 转显示格式 "MM-DD HH:MM:SS.mmm"；不含 'T' 的时间串原样返回。 */
    fun formatTime(isoTime: String): String {
        val t = isoTime.indexOf('T')
        if (t < 0) return isoTime
        val datePart = isoTime.substring(5, 10)
        val timePart = isoTime.substring(t + 1).takeWhile { c -> c != '+' && c != '-' && c != 'Z' }
        return "$datePart $timePart"
    }

    fun mapLevelChar(c: String): String = when (c) {
        "V" -> "VERBOSE"
        "D" -> "DEBUG"
        "I" -> "INFO"
        "W" -> "WARN"
        "E" -> "ERROR"
        "F" -> "FATAL"
        else -> c
    }

    /** 级别转过滤用数值，越大越严重；标度必须与 [LogStore] 的阈值一致，改一处要同步另一处。 */
    fun levelSeverity(level: String): Int = when (level.uppercase()) {
        "VERBOSE" -> 2
        "DEBUG" -> 3
        "INFO" -> 4
        "WARN", "WARNING" -> 5
        "ERROR" -> 6
        "FATAL" -> 7
        else -> 4
    }

    fun isStartMarker(line: String): Boolean = line.contains(MARKER)
}

// 定长环形缓冲：条目保留采集流中的逻辑下标，淘汰最旧项时 frontIndex 前移，
// 读取方据此判断丢了多少历史。
class LogBuffer(private val capacity: Int) {
    private val items = ArrayDeque<LogItem>()
    private var droppedCount = 0

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

    val frontIndex: Int
        @Synchronized get() = droppedCount

    @Synchronized
    fun sliceRange(fromIndex: Int, toIndex: Int): List<LogItem> {
        val from = maxOf(fromIndex, droppedCount)
        val to = minOf(toIndex, droppedCount + items.size)
        if (to <= from) return emptyList()
        return items.subList(from - droppedCount, to - droppedCount).toList()
    }
}

internal class LogCapture(capacity: Int = LogStore.MAX_BUFFER_SIZE) {
    private val buffer = LogBuffer(capacity)

    // 每次追加后发出当前缓冲大小；replay 1 让后加入者也能拿到最新状态。
    // 必须用 emit() 而非 tryEmit()：后者可能对活跃收集者丢值，丢了最后一次通知日志页会一直少一行。
    private val _updates = MutableSharedFlow<Int>(replay = 1)
    val updates: SharedFlow<Int> = _updates.asSharedFlow()

    init {
        _updates.tryEmit(0)
    }

    val size: Int get() = buffer.size
    val frontIndex: Int get() = buffer.frontIndex

    // 采集流中已保留项之后的下一个下标；与 size 不同，淘汰后仍与 sliceRange 边界一致。
    // 由两次同步读拼成，并发 append 时可能滞后一项，由下一次 update 通知自愈。
    val endIndex: Int get() = buffer.frontIndex + buffer.size

    fun sliceRange(fromIndex: Int, toIndex: Int): List<LogItem> =
        buffer.sliceRange(fromIndex, toIndex)

    suspend fun append(item: LogItem) {
        _updates.emit(buffer.append(item))
    }
}

object LogStore {

    const val MAX_BUFFER_SIZE = 2_000

    const val PAGE_SIZE = 50

    private const val TAG = LogTags.LOG_STORE
    private const val RETRY_DELAY_MS = 3_000L
    private const val DEFAULT_MIN_SEVERITY = 4

    internal val capture = LogCapture(MAX_BUFFER_SIZE)

    private val started = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 应用级 context 与进程同生命周期，放在进程级单例里不会泄漏（lint 看不出来）。
    @SuppressLint("StaticFieldLeak")
    @Volatile
    private var profiles: ProfileStore? = null
    @Volatile
    private var minSeverity = DEFAULT_MIN_SEVERITY
    @Volatile
    private var minSeverityCheckedAt = 0L

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        profiles = ProfileStore.get(context.applicationContext)
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

    /** 增量跟踪 `logcat -s <tags>`，不清空设备缓冲区；先写标记行，标记之前的日志全部丢弃。 */
    private suspend fun readLogcatLoop() {
        Log.i(TAG, LogParser.MARKER)
        val tags = LogTags.ALL.joinToString(" ")
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
        capture.append(item)
    }

    /** 采集期过滤阈值，取自当前配置档；每秒最多重读一次：逐行反序列化整份档案列表太贵。 */
    private fun currentMinSeverity(): Int {
        val now = SystemClock.elapsedRealtime()
        if (now - minSeverityCheckedAt >= 1_000L) {
            minSeverityCheckedAt = now
            val level = profiles?.active()?.logLevel ?: "info"
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
}
