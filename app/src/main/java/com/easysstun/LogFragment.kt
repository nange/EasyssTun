package com.easysstun

import android.annotation.SuppressLint
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
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch


class LogFragment : Fragment() {
    private lateinit var recyclerView: RecyclerView
    private lateinit var logViewModel: LogViewModel
    private lateinit var logAdapter: LogAdapter

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
        LogStore.ensureStarted(requireContext())

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
            // 首屏不足一屏时自动补页（如大字体/行数少）
            recyclerView.post { fillViewportIfNeeded() }
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
                    // 滑到顶部时加载更早的一页日志
                    if (newState == RecyclerView.SCROLL_STATE_IDLE &&
                        !recyclerView.canScrollVertically(-1)
                    ) {
                        loadOlderLogs()
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


        logViewModel.open()
    }

    /**
     * 在列表顶部向前加载更早的一页日志，并保持视口锚定在加载前
     * 的第一条可见项上（loadMore 通过 setValue 同步触发列表更新）。
     */
    private fun loadOlderLogs() {
        if (!logViewModel.canLoadMore()) return
        val layoutManager = recyclerView.layoutManager as LinearLayoutManager
        val firstPos = layoutManager.findFirstVisibleItemPosition()
        val firstView = layoutManager.findViewByPosition(firstPos)
        val offset = firstView?.top ?: 0
        val prepended = logViewModel.loadMore()
        if (prepended > 0 && !isAtBottom) {
            val target = firstPos + prepended
            if (target in 0 until logAdapter.itemCount) {
                layoutManager.scrollToPositionWithOffset(target, offset)
            }
        }
    }

    /** 窗口内容不足一屏且仍有更早日志时，继续向前补页直到填满或到顶。 */
    private fun fillViewportIfNeeded() {
        if (logViewModel.canLoadMore() && !recyclerView.canScrollVertically(1)) {
            loadOlderLogs()
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


/**
 * Holds the visible window into [LogStore]: the window always spans
 * [windowStart, store.endIndex), starting with the latest page on open.
 * Scrolling up prepends older pages; new logs always append at the tail.
 */
class LogViewModel : ViewModel() {
    private val window = mutableListOf<LogItem>()
    private val _logItems = MutableLiveData<List<LogItem>>(emptyList())
    val logItems: LiveData<List<LogItem>> get() = _logItems

    /** Capture-stream index of window[0]. */
    private var windowStart = 0
    /** Capture-stream index one past the last synced item (== store endIndex). */
    private var windowEndIndex = 0
    private var opened = false

    init {
        // Every store append (or eviction) triggers a resync; intermediate
        // emissions may be skipped, but each resync reads the current store
        // state so the window always converges.
        viewModelScope.launch {
            LogStore.updates.collect { resync() }
        }
    }

    /** Show the latest page; called when the log screen opens. Idempotent. */
    fun open() {
        if (opened) return
        opened = true
        val end = LogStore.endIndex
        windowStart = maxOf(LogStore.frontIndex, end - LogStore.PAGE_SIZE)
        windowEndIndex = end
        window.clear()
        window.addAll(LogStore.sliceRange(windowStart, end))
        _logItems.value = window.toList()
    }

    /**
     * Prepend the next older page of logs. Returns how many items were
     * actually prepended (0 when nothing older is available).
     */
    fun loadMore(): Int {
        if (!opened) return 0
        val front = LogStore.frontIndex
        if (windowStart < front) {
            // Eviction passed the front of our window: rebuild from the retained range
            rebuildWindow()
            return 0
        }
        if (windowStart <= front) return 0
        val newStart = maxOf(front, windowStart - LogStore.PAGE_SIZE)
        val older = LogStore.sliceRange(newStart, windowStart)
        if (older.isEmpty()) return 0
        window.addAll(0, older)
        windowStart = newStart
        _logItems.value = window.toList()
        return older.size
    }

    /** True when an older page is still available. */
    fun canLoadMore(): Boolean = opened && windowStart > LogStore.frontIndex

    private fun resync() {
        if (!opened) return
        val front = LogStore.frontIndex
        val end = LogStore.endIndex
        if (windowStart < front) {
            rebuildWindow()
        } else if (end > windowEndIndex) {
            window.addAll(LogStore.sliceRange(windowEndIndex, end))
            windowEndIndex = end
            _logItems.value = window.toList()
        }
    }

    private fun rebuildWindow() {
        val front = LogStore.frontIndex
        val end = LogStore.endIndex
        windowStart = maxOf(windowStart, front)
        windowEndIndex = end
        window.clear()
        window.addAll(LogStore.sliceRange(windowStart, end))
        _logItems.value = window.toList()
    }
}
