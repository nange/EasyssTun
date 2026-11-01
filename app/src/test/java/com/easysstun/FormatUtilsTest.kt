package com.easysstun

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatUtilsTest {

    @Test
    fun toHex_encodesEmptyAndSingleBytes() {
        assertEquals("", byteArrayOf().toHex())
        assertEquals("00", byteArrayOf(0x00).toHex())
        assertEquals("0f", byteArrayOf(0x0f).toHex())
        assertEquals("ff", byteArrayOf(0xff.toByte()).toHex())
        assertEquals("000f10ff", byteArrayOf(0x00, 0x0f, 0x10, 0xff.toByte()).toHex())
    }

    @Test
    fun formatPercent_handlesUnknownTotalAndClamps() {
        assertEquals(0, formatPercent(0, 0))
        assertEquals(0, formatPercent(10, -1))
        assertEquals(25, formatPercent(50, 200))
        assertEquals(100, formatPercent(200, 200))
        assertEquals(100, formatPercent(300, 200))
        assertEquals(33, formatPercent(1, 3))
    }

    @Test
    fun stripByteUnit_matchesTheStatsUnitStyle() {
        assertEquals("", stripByteUnit(""))
        assertEquals("1.5 M/s", stripByteUnit("1.5 MB/s"))
        assertEquals("12 K/s", stripByteUnit("12 KB/s"))
        assertEquals("1.5 M", stripByteUnit("1.5 MB"))
        assertEquals("1.5 M/s", stripByteUnit("1.5 M/s"))
        assertEquals("unknown", stripByteUnit("unknown"))
    }
}
