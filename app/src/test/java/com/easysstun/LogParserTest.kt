package com.easysstun

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the pure logcat line parsing in [LogParser].
 */
class LogParserTest {

    @Test
    fun parseLine_slogFormat() {
        val item = LogParser.parseLine(
            "time=2026-07-19T19:08:48.135+08:00 level=INFO source=vpn msg=connected to server"
        )
        assertEquals("connected to server", item?.message)
        assertEquals("07-19 19:08:48.135", item?.time)
        assertEquals("vpn", item?.source)
        assertEquals("INFO", item?.level)
    }

    @Test
    fun parseLine_slogWrappedInLogcat() {
        // Go slog lines arrive wrapped by logcat; the slog pattern must win
        // over the standard android.util.Log pattern.
        val item = LogParser.parseLine(
            "07-25 10:30:45.123  1234  5678 I GoLog   : time=2026-07-19T19:08:48.135+08:00 level=WARN source=core msg=slow dial"
        )
        assertEquals("slow dial", item?.message)
        assertEquals("07-19 19:08:48.135", item?.time)
        assertEquals("core", item?.source)
        assertEquals("WARN", item?.level)
    }

    @Test
    fun parseLine_tproxyFallbackFormat() {
        val item = LogParser.parseLine(
            "07-25 10:30:45.123  1234  5678 I easyss   : msg=native hello"
        )
        assertEquals("native hello", item?.message)
        assertEquals("07-25 10:30:45.123", item?.time)
        assertEquals("", item?.source)
        assertEquals("INFO", item?.level)
    }

    @Test
    fun parseLine_standardLogFormat() {
        val item = LogParser.parseLine(
            "07-25 10:30:45.123  1234  5678 I TProxyServiceDiag: onStartCommand: Received ACTION_DISCONNECT."
        )
        assertEquals("onStartCommand: Received ACTION_DISCONNECT.", item?.message)
        assertEquals("07-25 10:30:45.123", item?.time)
        assertEquals("TProxyServiceDiag", item?.source)
        assertEquals("INFO", item?.level)
    }

    @Test
    fun parseLine_unknownFormat_returnsNull() {
        assertNull(LogParser.parseLine("some random garbage"))
        assertNull(LogParser.parseLine("--------- beginning of main"))
    }

    @Test
    fun formatTime_isoWithTimezone() {
        assertEquals("07-19 19:08:48.135", LogParser.formatTime("2026-07-19T19:08:48.135+08:00"))
    }

    @Test
    fun formatTime_isoUtc() {
        assertEquals("07-19 19:08:48.135", LogParser.formatTime("2026-07-19T19:08:48.135Z"))
    }

    @Test
    fun formatTime_noIsoTime_returnsInput() {
        assertEquals("not-a-time", LogParser.formatTime("not-a-time"))
    }

    @Test
    fun mapLevelChar_allChars() {
        assertEquals("VERBOSE", LogParser.mapLevelChar("V"))
        assertEquals("DEBUG", LogParser.mapLevelChar("D"))
        assertEquals("INFO", LogParser.mapLevelChar("I"))
        assertEquals("WARN", LogParser.mapLevelChar("W"))
        assertEquals("ERROR", LogParser.mapLevelChar("E"))
        assertEquals("FATAL", LogParser.mapLevelChar("F"))
        assertEquals("X", LogParser.mapLevelChar("X"))
    }

    @Test
    fun levelSeverity_knownAndUnknown() {
        assertEquals(2, LogParser.levelSeverity("VERBOSE"))
        assertEquals(3, LogParser.levelSeverity("DEBUG"))
        assertEquals(4, LogParser.levelSeverity("INFO"))
        assertEquals(5, LogParser.levelSeverity("WARN"))
        assertEquals(5, LogParser.levelSeverity("WARNING"))
        assertEquals(6, LogParser.levelSeverity("ERROR"))
        assertEquals(7, LogParser.levelSeverity("FATAL"))
        assertEquals(4, LogParser.levelSeverity("unknown"))
    }

    @Test
    fun isStartMarker_matchesMarkerLine() {
        assertTrue(LogParser.isStartMarker(LogParser.MARKER))
        assertTrue(
            LogParser.isStartMarker(
                "07-25 10:30:45.123  1234  5678 I LogStore : ${LogParser.MARKER}"
            )
        )
    }
}
