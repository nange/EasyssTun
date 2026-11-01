package com.easysstun

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.easysstun.databinding.FragmentLogBinding
import com.easysstun.databinding.ItemLogBinding
import kotlinx.coroutines.launch


class LogFragment : Fragment() {

    private var _binding: FragmentLogBinding? = null
    private val binding get() = checkNotNull(_binding)

    private lateinit var logViewModel: LogViewModel
    private lateinit var logAdapter: LogAdapter

    private var isAtBottom = true
    private var changingState = false

    /**
     * Item the viewport is anchored to while an older page is prepended. The
     * anchor is the [LogItem] instance itself (the store reuses instances), so
     * it survives new logs arriving before the diff is committed.
     */
    private var anchorItem: LogItem? = null
    private var anchorOffset = 0

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLogBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        LogStore.start(requireContext())

        logAdapter = LogAdapter()
        binding.logRecyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.logRecyclerView.recycledViewPool.setMaxRecycledViews(0, 50)
        binding.logRecyclerView.adapter = logAdapter
        logViewModel = ViewModelProvider(this)[LogViewModel::class.java]

        // 观察LiveData以更新RecyclerView
        logViewModel.logItems.observe(viewLifecycleOwner) { logItems ->
            submitList(logItems)
        }

        binding.logRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                if (changingState) return
                if (!recyclerView.canScrollVertically(1)) {
                    isAtBottom = true
                    binding.fabToBotton.hide()
                } else {
                    isAtBottom = false
                    binding.fabToBotton.show()
                }
                // 滑到顶部时加载更早的一页日志
                if (newState == RecyclerView.SCROLL_STATE_IDLE &&
                    !recyclerView.canScrollVertically(-1)
                ) {
                    loadOlderLogs()
                }
            }
        })

        binding.fabToBotton.setOnClickListener {
            isAtBottom = !isAtBottom
            if (isAtBottom && logAdapter.itemCount > 1) {
                binding.logRecyclerView.stopScroll()
                binding.logRecyclerView.scrollToPosition(logAdapter.itemCount - 1)
                binding.fabToBotton.hide()
            }
        }

        logViewModel.open()
    }

    override fun onDestroyView() {
        binding.logRecyclerView.adapter = null
        super.onDestroyView()
        _binding = null
    }

    /**
     * Hands a new window to the diffing adapter. The scroll adjustments run in
     * the commit callback because [ListAdapter] applies the list asynchronously;
     * touching the layout manager before that would use the old item count.
     */
    private fun submitList(items: List<LogItem>) {
        val recyclerView = _binding?.logRecyclerView ?: return
        val anchor = anchorItem
        val offset = anchorOffset
        changingState = true
        recyclerView.stopScroll()
        logAdapter.submitList(items) {
            val layoutManager = _binding?.logRecyclerView?.layoutManager as? LinearLayoutManager
            if (anchor != null) {
                // Keep the previously visible item at the same offset after the
                // older page is prepended.
                val target = items.indexOfFirst { it === anchor }
                if (target >= 0) layoutManager?.scrollToPositionWithOffset(target, offset)
                anchorItem = null
            } else if (isAtBottom && items.size > 1) {
                recyclerView.scrollToPosition(items.size - 1)
            }
            changingState = false
            _binding?.logRecyclerView?.post { fillViewportIfNeeded() }
        }
    }

    /**
     * 在列表顶部向前加载更早的一页日志，并保持视口锚定在加载前
     * 的第一条可见项上（loadMore 通过 setValue 同步触发列表更新）。
     */
    private fun loadOlderLogs() {
        if (!logViewModel.canLoadMore()) return
        val layoutManager = _binding?.logRecyclerView?.layoutManager as? LinearLayoutManager
            ?: return
        val firstPos = layoutManager.findFirstVisibleItemPosition()
        if (firstPos == RecyclerView.NO_POSITION) return
        anchorItem = logAdapter.currentList.getOrNull(firstPos)
        anchorOffset = layoutManager.findViewByPosition(firstPos)?.top ?: 0
        if (logViewModel.loadMore() <= 0) {
            anchorItem = null
        }
    }

    /** 窗口内容不足一屏且仍有更早日志时，继续向前补页直到填满或到顶。 */
    private fun fillViewportIfNeeded() {
        val recyclerView = _binding?.logRecyclerView ?: return
        if (logViewModel.canLoadMore() && !recyclerView.canScrollVertically(1)) {
            loadOlderLogs()
        }
    }
}


internal class LogAdapter : ListAdapter<LogItem, LogAdapter.LogViewHolder>(DIFF_CALLBACK) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogViewHolder {
        val binding = ItemLogBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return LogViewHolder(binding)
    }

    override fun onBindViewHolder(holder: LogViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    class LogViewHolder(
        private val binding: ItemLogBinding,
    ) : RecyclerView.ViewHolder(binding.root) {
        fun bind(logItem: LogItem) {
            binding.logTimestampTextView.text = logItem.time
            binding.logLevelTextView.text = logItem.level
            binding.logSourceTextView.text = logItem.source
            binding.logMessageTextView.text = logItem.message
        }
    }

    private companion object {
        /**
         * The window is rebuilt from the same [LogItem] instances (appends add
         * new ones, paging reuses the stored ones), so identity is the stable
         * key and the mutable fields are compared by value.
         */
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LogItem>() {
            override fun areItemsTheSame(oldItem: LogItem, newItem: LogItem): Boolean =
                oldItem === newItem

            override fun areContentsTheSame(oldItem: LogItem, newItem: LogItem): Boolean =
                oldItem == newItem
        }
    }
}


/**
 * Holds the visible window into a [LogCapture]: the window always spans
 * `[windowStart, capture.endIndex)`, starting with the latest page on open.
 * Scrolling up prepends older pages; new logs always append at the tail.
 */
internal class LogViewModel(
    private val capture: LogCapture = LogStore.capture,
) : ViewModel() {
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
            capture.updates.collect { resync() }
        }
    }

    /** Show the latest page; called when the log screen opens. Idempotent. */
    fun open() {
        if (opened) return
        opened = true
        val end = capture.endIndex
        windowStart = maxOf(capture.frontIndex, end - LogStore.PAGE_SIZE)
        windowEndIndex = end
        window.clear()
        window.addAll(capture.sliceRange(windowStart, end))
        _logItems.value = window.toList()
    }

    /**
     * Prepend the next older page of logs. Returns how many items were
     * actually prepended (0 when nothing older is available).
     */
    fun loadMore(): Int {
        if (!opened) return 0
        val front = capture.frontIndex
        if (windowStart < front) {
            // Eviction passed the front of our window: rebuild from the retained range
            rebuildWindow()
            return 0
        }
        if (windowStart <= front) return 0
        val newStart = maxOf(front, windowStart - LogStore.PAGE_SIZE)
        val older = capture.sliceRange(newStart, windowStart)
        if (older.isEmpty()) return 0
        window.addAll(0, older)
        windowStart = newStart
        _logItems.value = window.toList()
        return older.size
    }

    /** True when an older page is still available. */
    fun canLoadMore(): Boolean = opened && windowStart > capture.frontIndex

    private fun resync() {
        if (!opened) return
        val front = capture.frontIndex
        val end = capture.endIndex
        if (windowStart < front) {
            rebuildWindow()
        } else if (end > windowEndIndex) {
            window.addAll(capture.sliceRange(windowEndIndex, end))
            windowEndIndex = end
            _logItems.value = window.toList()
        }
    }

    private fun rebuildWindow() {
        val front = capture.frontIndex
        val end = capture.endIndex
        windowStart = maxOf(windowStart, front)
        windowEndIndex = end
        window.clear()
        window.addAll(capture.sliceRange(windowStart, end))
        _logItems.value = window.toList()
    }
}
