package com.easysstun

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** 覆盖日志分页窗口：打开显示最新一页、上滑加载更早、新日志追加到尾部且不打断已加载窗口。 */
class LogViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var capture: LogCapture

    @Before
    fun setUp() {
        // 每个用例各自持有 capture，无需在 LogStore 上做进程级重置。
        capture = LogCapture()
    }

    private fun item(i: Int) = LogItem("msg$i", "12:00", "src", "INFO")

    /** 测试调度器内联执行收集器，因此这里用 runBlocking 同步追加。 */
    private fun append(item: LogItem) {
        runBlocking { capture.append(item) }
    }

    @Test
    fun open_withEmptyStore_showsEmptyWindow() {
        val viewModel = LogViewModel(capture)
        viewModel.open()

        assertTrue(viewModel.logItems.value.orEmpty().isEmpty())
        assertFalse(viewModel.canLoadMore())
    }

    @Test
    fun open_showsOnlyTheLatestPage() {
        repeat(120) { append(item(it)) }

        val viewModel = LogViewModel(capture)
        viewModel.open()

        val items = viewModel.logItems.value!!
        assertEquals(50, items.size)
        assertEquals("msg70", items.first().message)
        assertEquals("msg119", items.last().message)
        assertTrue(viewModel.canLoadMore())
    }

    @Test
    fun open_fewerThanPageSize_showsAll() {
        repeat(30) { append(item(it)) }

        val viewModel = LogViewModel(capture)
        viewModel.open()

        val items = viewModel.logItems.value!!
        assertEquals(30, items.size)
        assertEquals("msg0", items.first().message)
        assertEquals("msg29", items.last().message)
        assertFalse(viewModel.canLoadMore())
    }

    @Test
    fun loadMore_prependsOlderPagesUntilExhausted() {
        repeat(120) { append(item(it)) }
        val viewModel = LogViewModel(capture)
        viewModel.open()

        assertEquals(50, viewModel.loadMore())
        var items = viewModel.logItems.value!!
        assertEquals(100, items.size)
        assertEquals("msg20", items.first().message)

        assertEquals(20, viewModel.loadMore())
        items = viewModel.logItems.value!!
        assertEquals(120, items.size)
        assertEquals("msg0", items.first().message)
        assertFalse(viewModel.canLoadMore())

        assertEquals(0, viewModel.loadMore())
        assertEquals(120, viewModel.logItems.value!!.size)
    }

    @Test
    fun loadMore_partialPageNearFront() {
        repeat(70) { append(item(it)) }
        val viewModel = LogViewModel(capture)
        viewModel.open()

        assertEquals(50, viewModel.logItems.value!!.size)
        assertEquals(20, viewModel.loadMore())
        val items = viewModel.logItems.value!!
        assertEquals(70, items.size)
        assertEquals("msg0", items.first().message)
        assertFalse(viewModel.canLoadMore())
    }

    @Test
    fun newLogsAppendToWindowTail() {
        repeat(60) { append(item(it)) }
        val viewModel = LogViewModel(capture)
        viewModel.open()
        assertEquals(50, viewModel.logItems.value!!.size)

        repeat(10) { append(item(60 + it)) }

        val items = viewModel.logItems.value!!
        assertEquals(60, items.size)
        assertEquals("msg10", items.first().message)
        assertEquals("msg69", items.last().message)
    }

    @Test
    fun newLogsAppendEvenAfterLoadingEverything() {
        repeat(70) { append(item(it)) }
        val viewModel = LogViewModel(capture)
        viewModel.open()
        viewModel.loadMore()
        assertEquals(70, viewModel.logItems.value!!.size)

        repeat(5) { append(item(70 + it)) }

        val items = viewModel.logItems.value!!
        assertEquals(75, items.size)
        assertEquals("msg0", items.first().message)
        assertEquals("msg74", items.last().message)
    }

    @Test
    fun open_keepsWindowWhenCalledAgain() {
        repeat(120) { append(item(it)) }
        val viewModel = LogViewModel(capture)
        viewModel.open()
        viewModel.loadMore()
        val before = viewModel.logItems.value!!

        // 视图重建会再次调用 open()，此时不得重置已加载窗口。
        viewModel.open()

        assertEquals(before, viewModel.logItems.value)
    }

    @Test
    fun freshViewModel_showsLatestPageAgain() {
        repeat(120) { append(item(it)) }
        LogViewModel(capture).open()
        repeat(30) { append(item(120 + it)) }

        // 重新进入日志页会新建 ViewModel，应重新显示最新一页。
        val viewModel = LogViewModel(capture)
        viewModel.open()

        val items = viewModel.logItems.value!!
        assertEquals(50, items.size)
        assertEquals("msg100", items.first().message)
        assertEquals("msg149", items.last().message)
    }

    @Test
    fun loadMore_afterEviction_rebuildsFromRetainedRange() {
        repeat(LogStore.MAX_BUFFER_SIZE) { append(item(it)) }
        val viewModel = LogViewModel(capture)
        viewModel.open()
        while (viewModel.loadMore() > 0) { }
        val sizeBeforeEviction = viewModel.logItems.value!!.size

        // 写满后再溢出：窗口正在展示的最旧条目被淘汰，迫使窗口重建。
        repeat(LogStore.MAX_BUFFER_SIZE + 10) { append(item(10_000 + it)) }

        val items = viewModel.logItems.value!!
        assertTrue(items.size <= sizeBeforeEviction + 10)
        assertTrue(items.size > 0)
        // 保留区间为全局下标 [2010, 4010)，对应最新 10010..12009。
        assertEquals("msg10010", items.first().message)
        assertEquals("msg12009", items.last().message)
    }
}
