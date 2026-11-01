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

    /** 补页时视口锚定的项；锚点用 [LogItem] 实例本身，日志追加不会换实例，diff 提交前仍能定位。 */
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

        logViewModel.logItems.observe(viewLifecycleOwner) { logItems ->
            submitList(logItems)
        }

        binding.logRecyclerView.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                // submitList 里的 stopScroll 也会触发回调，提交期间用 changingState 屏蔽，避免误判 isAtBottom 与重复补页。
                if (changingState) return
                if (!recyclerView.canScrollVertically(1)) {
                    isAtBottom = true
                    binding.fabToBotton.hide()
                } else {
                    isAtBottom = false
                    binding.fabToBotton.show()
                }
                // 到顶就补一页；列表不足一屏时 canScrollVertically(-1) 同样为 false。
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

    /** 滚动修正放在提交回调里：[ListAdapter] 异步应用列表，提前动 LayoutManager 拿到的还是旧条目数。 */
    private fun submitList(items: List<LogItem>) {
        val recyclerView = _binding?.logRecyclerView ?: return
        val anchor = anchorItem
        val offset = anchorOffset
        changingState = true
        recyclerView.stopScroll()
        logAdapter.submitList(items) {
            val layoutManager = _binding?.logRecyclerView?.layoutManager as? LinearLayoutManager
            if (anchor != null) {
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

    /** 向前补一页并锚定加载前的首条可见项：loadMore 经 setValue 同步更新列表，锚点必须提前取。 */
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

    /** 不足一屏时补一页；补页后经 submitList 的 post 回调再次进入，直到填满或没有更早日志。 */
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
        /** 窗口复用同一批 [LogItem] 实例（补页不复制），故用引用相等判同一项，内容按值比较。 */
        val DIFF_CALLBACK = object : DiffUtil.ItemCallback<LogItem>() {
            override fun areItemsTheSame(oldItem: LogItem, newItem: LogItem): Boolean =
                oldItem === newItem

            override fun areContentsTheSame(oldItem: LogItem, newItem: LogItem): Boolean =
                oldItem == newItem
        }
    }
}


/** 日志页窗口始终覆盖 `[windowStart, capture.endIndex)`：打开时取最新一页，回看旧日志时前插，新日志只追加在尾部。 */
internal class LogViewModel(
    private val capture: LogCapture = LogStore.capture,
) : ViewModel() {
    private val window = mutableListOf<LogItem>()
    private val _logItems = MutableLiveData<List<LogItem>>(emptyList())
    val logItems: LiveData<List<LogItem>> get() = _logItems

    private var windowStart = 0
    // 已同步进窗口的采集流下标（不含）；与 capture.endIndex 的差值就是待追加的新日志。
    private var windowEndIndex = 0
    private var opened = false

    init {
        // 每次追加或淘汰都触发重同步；中间通知可能被跳过，但每次重同步都读当前仓库状态，
        // 窗口最终收敛。
        viewModelScope.launch {
            capture.updates.collect { resync() }
        }
    }

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

    fun loadMore(): Int {
        if (!opened) return 0
        val front = capture.frontIndex
        if (windowStart < front) {
            // 淘汰已越过窗口起点：按保留区间重建，返回 0 让调用方丢弃锚点。
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
