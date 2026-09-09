package com.easysstun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the bounded log buffer ([LogBuffer]): append, eviction and
 * capture-stream index clamping.
 */
class LogBufferTest {

    private fun item(i: Int) = LogItem("msg$i", "12:00", "src", "INFO")

    @Test
    fun append_withinCapacity_keepsAllInOrder() {
        val buffer = LogBuffer(100)
        repeat(10) { buffer.append(item(it)) }
        assertEquals(10, buffer.size)
        assertEquals(0, buffer.frontIndex)
        assertEquals((0 until 10).map { "msg$it" }, buffer.sliceRange(0, 10).map { it.message })
    }

    @Test
    fun append_overCapacity_dropsOldest() {
        val buffer = LogBuffer(5)
        repeat(7) { buffer.append(item(it)) }
        assertEquals(5, buffer.size)
        assertEquals(2, buffer.frontIndex)
        assertEquals(listOf("msg2", "msg3", "msg4", "msg5", "msg6"),
            buffer.sliceRange(0, 7).map { it.message })
    }

    @Test
    fun sliceRange_clampsBelowFrontIndex() {
        val buffer = LogBuffer(5)
        repeat(7) { buffer.append(item(it)) }
        // frontIndex = 2; requesting below the front clamps to the front.
        assertEquals(listOf("msg2", "msg3"), buffer.sliceRange(0, 4).map { it.message })
    }

    @Test
    fun sliceRange_clampsBeyondEnd() {
        val buffer = LogBuffer(5)
        repeat(3) { buffer.append(item(it)) }
        assertEquals(listOf("msg1", "msg2"), buffer.sliceRange(1, 100).map { it.message })
        assertTrue(buffer.sliceRange(5, 10).isEmpty())
    }

    @Test
    fun sliceRange_emptyWhenOutOfRange() {
        val buffer = LogBuffer(5)
        repeat(3) { buffer.append(item(it)) }
        assertTrue(buffer.sliceRange(3, 3).isEmpty())
        assertTrue(buffer.sliceRange(10, 20).isEmpty())
    }

    @Test
    fun clear_resetsState() {
        val buffer = LogBuffer(5)
        repeat(7) { buffer.append(item(it)) }
        buffer.clear()
        assertEquals(0, buffer.size)
        assertEquals(0, buffer.frontIndex)
        assertTrue(buffer.sliceRange(0, 10).isEmpty())
    }
}
