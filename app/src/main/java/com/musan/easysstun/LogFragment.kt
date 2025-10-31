package com.musan.easysstun

import android.os.Bundle
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

//        val dividerItemDecoration = DividerItemDecoration(requireContext(), layoutManager.orientation)
//        dividerItemDecoration.setDrawable(ContextCompat.getDrawable(requireContext(), R.drawable.divider_drawable)!!)
//        recyclerView.addItemDecoration(dividerItemDecoration)
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
//                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    if (changingState)
                        return
                    if (!recyclerView.canScrollVertically(1)) {
                        isAtBottom = true
                        fabToBotton.hide()
                    } else {
                        isAtBottom = false
                        fabToBotton.show()
                    }
//                }
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
    private fun readLogs() {
        logJob = lifecycleScope.launch(Dispatchers.IO) {
            var inputStream: InputStream? = null
            var bufferedReader: BufferedReader? = null
            var process: Process? = null
            try {
                val cleanprocess = Runtime.getRuntime().exec("logcat -c")
                cleanprocess.waitFor()
                process = Runtime.getRuntime().exec("logcat -s easyss")
                inputStream = process.inputStream
                bufferedReader = BufferedReader(InputStreamReader(inputStream))
                while (isActive) {
                    val line: String? = bufferedReader.readLine()
                    if (line != null) {
                        val pattern =
                            Pattern.compile("\\s(\\d{2}:\\d{2}:\\d{2})\\.\\d{3}.*source=(.*) msg=(.*)")
                        val matcher = pattern.matcher(line)
                        if (matcher.find()) {
                            val timestampString = matcher.group(1)
                            val source = matcher.group(2)
                            val msg = matcher.group(3)
                            val logItem = LogItem(msg, timestampString, source)
                            logViewModel.addLog(logItem)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
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
        private val logTextView: TextView = itemView.findViewById(R.id.logTextView)
        private val logTimestampTextView: TextView =
            itemView.findViewById(R.id.logTimestampTextView)
        private val logSourceTextView: TextView = itemView.findViewById(R.id.logSourceTextView)

        fun bind(logItem: LogItem) {
            logTextView.text = logItem.message
            logTimestampTextView.text = logItem.time
            logSourceTextView.text = logItem.source
        }
    }
}


data class LogItem(val message: String, var time: String, var source: String)

class LogViewModel : ViewModel() {
    private val _logItems = MutableLiveData<List<LogItem>>()
    val logItems: LiveData<List<LogItem>> get() = _logItems

    fun addLog(logItem: LogItem) {
        val currentList = _logItems.value.orEmpty().toMutableList()
        currentList.add(logItem)
        _logItems.postValue(currentList)
    }

    fun clearLogs() {
        _logItems.postValue(emptyList())
    }
}
