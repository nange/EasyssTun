package com.easysstun

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Tests for LogViewModel's paged window into [LogStore]: opening shows the
 * latest page, scrolling up loads older pages, and new logs append at the
 * tail without disturbing the loaded window.
 */
class LogViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Before
    fun setUp() {
        LogStore.resetForTest()
    }

    @After
    fun tearDown() {
        LogStore.resetForTest()
    }

    private fun item(i: Int) = LogItem("msg$i", "12:00", "src", "INFO")

    @Test
    fun open_withEmptyStore_showsEmptyWindow() {
        val viewModel = LogViewModel()
        viewModel.open()

        assertTrue(viewModel.logItems.value.orEmpty().isEmpty())
        assertFalse(viewModel.canLoadMore())
    }

    @Test
    fun open_showsOnlyTheLatestPage() {
        repeat(120) { LogStore.appendForTest(item(it)) }

        val viewModel = LogViewModel()
        viewModel.open()

        val items = viewModel.logItems.value!!
        assertEquals(50, items.size)
        assertEquals("msg70", items.first().message)
        assertEquals("msg119", items.last().message)
        assertTrue(viewModel.canLoadMore())
    }

    @Test
    fun open_fewerThanPageSize_showsAll() {
        repeat(30) { LogStore.appendForTest(item(it)) }

        val viewModel = LogViewModel()
        viewModel.open()

        val items = viewModel.logItems.value!!
        assertEquals(30, items.size)
        assertEquals("msg0", items.first().message)
        assertEquals("msg29", items.last().message)
        assertFalse(viewModel.canLoadMore())
    }

    @Test
    fun loadMore_prependsOlderPagesUntilExhausted() {
        repeat(120) { LogStore.appendForTest(item(it)) }
        val viewModel = LogViewModel()
        viewModel.open()

        // First page: [70, 120)
        assertEquals(50, viewModel.loadMore())
        var items = viewModel.logItems.value!!
        assertEquals(100, items.size)
        assertEquals("msg20", items.first().message)

        // Second page: only [0, 20) remains
        assertEquals(20, viewModel.loadMore())
        items = viewModel.logItems.value!!
        assertEquals(120, items.size)
        assertEquals("msg0", items.first().message)
        assertFalse(viewModel.canLoadMore())

        // Nothing older left: no-op
        assertEquals(0, viewModel.loadMore())
        assertEquals(120, viewModel.logItems.value!!.size)
    }

    @Test
    fun loadMore_partialPageNearFront() {
        repeat(70) { LogStore.appendForTest(item(it)) }
        val viewModel = LogViewModel()
        viewModel.open()

        // Open: [20, 70)
        assertEquals(50, viewModel.logItems.value!!.size)
        // Load more: only 20 older items exist
        assertEquals(20, viewModel.loadMore())
        val items = viewModel.logItems.value!!
        assertEquals(70, items.size)
        assertEquals("msg0", items.first().message)
        assertFalse(viewModel.canLoadMore())
    }

    @Test
    fun newLogsAppendToWindowTail() {
        repeat(60) { LogStore.appendForTest(item(it)) }
        val viewModel = LogViewModel()
        viewModel.open()
        assertEquals(50, viewModel.logItems.value!!.size)

        repeat(10) { LogStore.appendForTest(item(60 + it)) }

        val items = viewModel.logItems.value!!
        assertEquals(60, items.size)
        assertEquals("msg10", items.first().message)
        assertEquals("msg69", items.last().message)
    }

    @Test
    fun newLogsAppendEvenAfterLoadingEverything() {
        repeat(70) { LogStore.appendForTest(item(it)) }
        val viewModel = LogViewModel()
        viewModel.open()
        viewModel.loadMore()
        assertEquals(70, viewModel.logItems.value!!.size)

        repeat(5) { LogStore.appendForTest(item(70 + it)) }

        val items = viewModel.logItems.value!!
        assertEquals(75, items.size)
        assertEquals("msg0", items.first().message)
        assertEquals("msg74", items.last().message)
    }

    @Test
    fun open_keepsWindowWhenCalledAgain() {
        repeat(120) { LogStore.appendForTest(item(it)) }
        val viewModel = LogViewModel()
        viewModel.open()
        viewModel.loadMore()
        val before = viewModel.logItems.value!!

        // A second open() (e.g. view recreated) must not reset the window.
        viewModel.open()

        assertEquals(before, viewModel.logItems.value)
    }

    @Test
    fun freshViewModel_showsLatestPageAgain() {
        repeat(120) { LogStore.appendForTest(item(it)) }
        LogViewModel().open()
        repeat(30) { LogStore.appendForTest(item(120 + it)) }

        // Re-entering the log screen creates a new ViewModel.
        val viewModel = LogViewModel()
        viewModel.open()

        val items = viewModel.logItems.value!!
        assertEquals(50, items.size)
        assertEquals("msg100", items.first().message)
        assertEquals("msg149", items.last().message)
    }

    @Test
    fun loadMore_afterEviction_rebuildsFromRetainedRange() {
        repeat(LogStore.MAX_BUFFER_SIZE) { LogStore.appendForTest(item(it)) }
        val viewModel = LogViewModel()
        viewModel.open()
        // Load the oldest retained page.
        while (viewModel.loadMore() > 0) { /* keep going until exhausted */ }
        val sizeBeforeEviction = viewModel.logItems.value!!.size

        // Overfill the buffer: the oldest items (which the window shows) get evicted.
        repeat(LogStore.MAX_BUFFER_SIZE + 10) { LogStore.appendForTest(item(10_000 + it)) }

        val items = viewModel.logItems.value!!
        // Window must be rebuilt from the retained range, not crash.
        assertTrue(items.size <= sizeBeforeEviction + 10)
        assertTrue(items.size > 0)
        // The retained range is [2010, 4010): the newest items (10010..12009).
        assertEquals("msg10010", items.first().message)
        assertEquals("msg12009", items.last().message)
    }
}
