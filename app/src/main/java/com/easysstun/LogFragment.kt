package com.easysstun

import android.os.Bundle
import android.annotation.SuppressLint
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.util.regex.Pattern


class LogFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var logViewModel: LogViewModel
    private lateinit var logAdapter: LogAdapter
    private var logJob: Job? = null

    private var isAtBottom = true
    private var changingState = false

    companion object {
        // App log tags to display in the log viewer
        private val APP_LOG_TAGS = arrayOf(
            "GoLog", "TProxyServiceDiag", "MainFragment", "AppState",
            "Pref", "Profile", "LogFragment", "AppListAdapter"
        )

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
    }


    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_log, container, false)
    }


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        recyclerView = view.findViewById(R.id.logRecyclerView)
        logAdapter = LogAdapter()


        // 设置布局管理器
        val layoutManager = LinearLayoutManager(requireContext())
        recyclerView.layoutManager = layoutManager

        recyclerView.recycledViewPool.setMaxRecycledViews(0, 50)
        recyclerView.adapter = logAdapter
        logViewModel = ViewModelProvider(this).get(LogViewModel::class.java)

        // 观察LiveData以更新RecyclerView
        logViewModel.logItems.observe(viewLifecycleOwner) { logItems ->
            changingState = true
            logAdapter.submitList(logItems)
            recyclerView.stopScroll()
            if (isAtBottom && logItems.size > 1) {
                recyclerView.scrollToPosition(logItems.size - 1)
            }
            changingState = false
        }

        val fabToBotton = view.findViewById<FloatingActionButton>(R.id.fabToBotton)
        recyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (changingState)
                        return
                    if (!recyclerView.canScrollVertically(1)) {
                        isAtBottom = true
                        fabToBotton.hide()
                    } else {
                        isAtBottom = false
                        fabToBotton.show()
                    }
            }
        })

        fabToBotton.setOnClickListener {
            isAtBottom = !isAtBottom
            if (isAtBottom && logAdapter.itemCount > 1) {
                recyclerView.stopScroll()
                recyclerView.scrollToPosition(logAdapter.itemCount - 1)
                fabToBotton.hide()
            }
        }


        readLogs()
    }
    override fun onDestroy() {
        super.onDestroy()
        logJob?.cancel()
    }

    /** Read the configured log level from the active profile, defaulting to INFO (severity 4). */
    private fun getMinLevelSeverity(): Int {
        val logLevel = Pref(requireContext()).getActiveProfile()?.logLevel ?: "info"
        return when (logLevel.lowercase()) {
            "debug" -> 3
            "info" -> 4
            "warn" -> 5
            "error" -> 6
            else -> 4  // Default INFO
        }
    }

    private fun readLogs() {
        val minLevelSeverity = getMinLevelSeverity()
        logJob = lifecycleScope.launch(Dispatchers.IO) {
            var inputStream: InputStream? = null
            var bufferedReader: BufferedReader? = null
            var process: Process? = null
            try {
                val cleanprocess = Runtime.getRuntime().exec("logcat -c")
                cleanprocess.waitFor()
                val tags = APP_LOG_TAGS.joinToString(" ")
                process = Runtime.getRuntime().exec("logcat -s $tags")
                inputStream = process.inputStream
                bufferedReader = BufferedReader(InputStreamReader(inputStream))
                while (isActive) {
                    val line: String? = bufferedReader.readLine()
                    if (line != null) {
                        // Try Go slog pattern first
                        var matcher = LOG_PATTERN_SLOG.matcher(line)
                        if (matcher.find()) {
                            val isoTime = matcher.group(1) ?: ""
                            val level = matcher.group(2) ?: ""
                            val source = matcher.group(3) ?: ""
                            val msg = matcher.group(4) ?: ""
                            val displayTime = formatTime(isoTime)
                            if (levelSeverity(level) >= minLevelSeverity) {
                                val logItem = LogItem(msg, displayTime, source, level)
                                logViewModel.addLog(logItem)
                            }
                        } else {
                            // Try fallback pattern for TProxyService lines
                            matcher = LOG_PATTERN_FALLBACK.matcher(line)
                            if (matcher.find()) {
                                val logDate = matcher.group(1) ?: ""
                                val logTime = matcher.group(2) ?: ""
                                val levelChar = matcher.group(3) ?: ""
                                val msg = matcher.group(4) ?: ""
                                val displayTime = "$logDate $logTime"
                                val level = mapLevelChar(levelChar)
                                if (levelSeverity(level) >= minLevelSeverity) {
                                    val logItem = LogItem(msg, displayTime, "", level)
                                    logViewModel.addLog(logItem)
                                }
                            } else {
                                // Try standard android.util.Log pattern for app-side logs
                                matcher = LOG_PATTERN_STANDARD.matcher(line)
                                if (matcher.find()) {
                                    val logDate = matcher.group(1) ?: ""
                                    val logTime = matcher.group(2) ?: ""
                                    val levelChar = matcher.group(3) ?: ""
                                    val tag = matcher.group(4) ?: ""
                                    val msg = matcher.group(5) ?: ""
                                    val displayTime = "$logDate $logTime"
                                    val level = mapLevelChar(levelChar)
                                    if (levelSeverity(level) >= minLevelSeverity) {
                                        val logItem = LogItem(msg, displayTime, tag, level)
                                        logViewModel.addLog(logItem)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: IOException) {
                Log.e("LogFragment", "Error reading logs", e)
            } finally {
                inputStream?.close()
                bufferedReader?.close()
                process?.destroy()
            }
        }
    }
}


class LogAdapter : RecyclerView.Adapter<LogAdapter.LogViewHolder>() {
    private var logItems: List<LogItem> = emptyList()

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newList: List<LogItem>) {
        logItems = newList
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_log, parent, false)
        return LogViewHolder(view)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        val logItem = logItems[position]
        holder.bind(logItem)
    }

    override fun getItemCount(): Int {
        return logItems.size
    }

    class LogViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val logTimestampTextView: TextView =
            itemView.findViewById(R.id.logTimestampTextView)
        private val logLevelTextView: TextView =
            itemView.findViewById(R.id.logLevelTextView)
        private val logSourceTextView: TextView =
            itemView.findViewById(R.id.logSourceTextView)
        private val logMessageTextView: TextView =
            itemView.findViewById(R.id.logMessageTextView)

        fun bind(logItem: LogItem) {
            logTimestampTextView.text = logItem.time
            logLevelTextView.text = logItem.level
            logSourceTextView.text = logItem.source
            logMessageTextView.text = logItem.message
        }
    }
}


data class LogItem(val message: String, var time: String, var source: String, var level: String)

class LogViewModel : ViewModel() {
    private val _logItems = MutableLiveData<List<LogItem>>()
    val logItems: LiveData<List<LogItem>> get() = _logItems

    companion object {
        private const val MAX_LOG_SIZE = 1000
    }

    fun addLog(logItem: LogItem) {
        val currentList = _logItems.value.orEmpty().toMutableList()
        currentList.add(logItem)
        if (currentList.size > MAX_LOG_SIZE) {
            currentList.subList(0, currentList.size - MAX_LOG_SIZE).clear()
        }
        _logItems.postValue(currentList)
    }

    fun clearLogs() {
        _logItems.postValue(emptyList())
    }
}
