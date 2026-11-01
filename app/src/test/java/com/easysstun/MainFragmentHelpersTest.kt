package com.easysstun

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

/** 覆盖从 MainFragment 抽出的 formatBytes() 与 formatDuration()。 */
class MainFragmentHelpersTest {

    // ── formatBytes ──────────────────────────────────────────────

    @Test
    fun formatBytes_zero() {
        assertEquals("0B", formatBytes(0))
    }

    @Test
    fun formatBytes_bytes() {
        assertEquals("0B", formatBytes(0))
        assertEquals("1B", formatBytes(1))
        assertEquals("999B", formatBytes(999))
    }

    @Test
    fun formatBytes_kilobytes() {
        val result1KB = formatBytes(1_000)
        assertTrue("1K should contain 'K'", result1KB.contains("K"))

        val result1500 = formatBytes(1_500)
        assertTrue("1.5K should contain 'K'", result1500.contains("K"))
    }

    @Test
    fun formatBytes_megabytes() {
        val result1MB = formatBytes(1_000_000)
        assertTrue("1M should contain 'M'", result1MB.contains("M"))

        val result = formatBytes(2_500_000)
        assertTrue("2.5M should contain 'M'", result.contains("M"))
    }

    @Test
    fun formatBytes_gigabytes() {
        val result1GB = formatBytes(1_000_000_000)
        assertTrue("1G should contain 'G'", result1GB.contains("G"))

        val result = formatBytes(1_500_000_000L)
        assertTrue("1.5G should contain 'G'", result.contains("G"))
    }

    @Test
    fun formatBytes_largeGigabytes() {
        val result = formatBytes(50_000_000_000L)
        assertTrue("50G should contain 'G'", result.contains("G"))
        assertFalse("50G should not contain 'M'", result.contains("M"))
    }

    @Test
    fun formatBytes_boundaryValues() {
        assertTrue("999 should be B", formatBytes(999).contains("B") && !formatBytes(999).contains("K"))
        assertTrue("1000 should be K", formatBytes(1_000).contains("K"))
        assertTrue("999_999 should be K", formatBytes(999_999).contains("K"))
        assertTrue("1_000_000 should be M", formatBytes(1_000_000).contains("M"))
        assertTrue("999_999_999 should be M", formatBytes(999_999_999).contains("M"))
        assertTrue("1_000_000_000 should be G", formatBytes(1_000_000_000).contains("G"))
    }

    // ── formatDuration ───────────────────────────────────────────

    @Test
    fun formatDuration_zero() {
        assertEquals("0s", formatDuration(0.0))
    }

    @Test
    fun formatDuration_seconds() {
        assertEquals("0s", formatDuration(0.0))
        assertEquals("1s", formatDuration(1.0))
        assertEquals("59s", formatDuration(59.0))
    }

    @Test
    fun formatDuration_minutes() {
        assertEquals("1m", formatDuration(60.0))
        assertEquals("2m 30s", formatDuration(150.0))
        assertEquals("59m 59s", formatDuration(3599.0))
    }

    @Test
    fun formatDuration_hours() {
        // 为零的分量（含秒）不输出。
        assertEquals("1h", formatDuration(3600.0))
        assertEquals("2h 30m 15s", formatDuration(9015.0))
    }

    @Test
    fun formatDuration_days() {
        assertEquals("1d", formatDuration(86400.0))
        assertEquals("2d 6h 30m 45s", formatDuration(196245.0))
    }

    @Test
    fun formatDuration_fractionalSeconds_truncates() {
        // 小数秒直接截断（toLong），不四舍五入。
        val result = formatDuration(65.9)
        assertTrue("65.9s should show 1m 5s", result.contains("1m"))
        assertTrue("65.9s should show 1m 5s", result.contains("5s"))
    }

    @Test
    fun formatDuration_onlyDays() {
        assertEquals("3d", formatDuration(259200.0))
    }

    @Test
    fun formatDuration_largeValue() {
        val seconds = 10 * 86400.0 + 5 * 3600.0 + 30 * 60.0 + 15.0
        val result = formatDuration(seconds)
        assertTrue("Should contain days", result.contains("10d"))
        assertTrue("Should contain hours", result.contains("5h"))
        assertTrue("Should contain minutes", result.contains("30m"))
        assertTrue("Should contain seconds", result.contains("15s"))
    }
}
