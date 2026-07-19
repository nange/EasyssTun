package com.easysstun

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * Tests for LogViewModel: adding logs, clearing logs, and observing LiveData.
 */
class LogViewModelTest {

    @get:Rule
    val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Test
    fun initialLogItems_isEmpty() {
        val viewModel = LogViewModel()
        val items = viewModel.logItems.value
        // MutableLiveData starts with null before any value is posted
        assertTrue("Initial log items should be null or empty", items == null || items.isEmpty())
    }

    @Test
    fun addLog_singleItem_appendsToList() {
        val viewModel = LogViewModel()
        val logItem = LogItem("Test message", "12:00:00", "source1", "INFO")

        viewModel.addLog(logItem)

        val items = viewModel.logItems.value
        assertEquals("Should have 1 log item", 1, items?.size)
        assertEquals("Message should match", "Test message", items?.get(0)?.message)
        assertEquals("Time should match", "12:00:00", items?.get(0)?.time)
        assertEquals("Source should match", "source1", items?.get(0)?.source)
        assertEquals("Level should match", "INFO", items?.get(0)?.level)
    }

    @Test
    fun addLog_multipleItems_preservesOrder() {
        val viewModel = LogViewModel()
        val item1 = LogItem("First", "12:00:00", "src-a", "INFO")
        val item2 = LogItem("Second", "12:00:01", "src-b", "WARN")
        val item3 = LogItem("Third", "12:00:02", "src-c", "DEBUG")

        viewModel.addLog(item1)
        viewModel.addLog(item2)
        viewModel.addLog(item3)

        val items = viewModel.logItems.value
        assertEquals("Should have 3 log items", 3, items?.size)
        assertEquals("First item should be first", "First", items?.get(0)?.message)
        assertEquals("Second item should be second", "Second", items?.get(1)?.message)
        assertEquals("Third item should be third", "Third", items?.get(2)?.message)
    }

    @Test
    fun clearLogs_removesAllItems() {
        val viewModel = LogViewModel()
        viewModel.addLog(LogItem("msg1", "12:00", "src1", "INFO"))
        viewModel.addLog(LogItem("msg2", "12:01", "src2", "WARN"))

        assertEquals("Should have 2 items before clear", 2, viewModel.logItems.value?.size)

        viewModel.clearLogs()

        val items = viewModel.logItems.value
        assertNotNull("Should not be null after clear", items)
        assertTrue("Should be empty after clear", items!!.isEmpty())
    }

    @Test
    fun addLog_afterClear_startsWithFreshList() {
        val viewModel = LogViewModel()
        viewModel.addLog(LogItem("old", "12:00", "src", "INFO"))
        viewModel.clearLogs()
        viewModel.addLog(LogItem("new", "12:01", "src2", "ERROR"))

        val items = viewModel.logItems.value
        assertEquals("Should have 1 item after clear + add", 1, items?.size)
        assertEquals("Should be the new item", "new", items?.get(0)?.message)
    }

    @Test
    fun logItem_dataClass_holdsValues() {
        val item = LogItem("test msg", "13:45:00", "test-source", "INFO")

        assertEquals("test msg", item.message)
        assertEquals("13:45:00", item.time)
        assertEquals("test-source", item.source)
        assertEquals("INFO", item.level)
    }
}
