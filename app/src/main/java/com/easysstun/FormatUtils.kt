package com.easysstun

import java.util.Locale

/**
 * Formats a byte count into a human-readable string.
 * Package-internal for testability.
 */
internal fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> String.format(Locale.ROOT, "%.2fG", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.2fM", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.ROOT, "%.2fK", bytes / 1_000.0)
        else -> "${bytes}B"
    }
}

/**
 * Formats a duration in seconds into a human-readable string.
 *
 * Zero-valued components are omitted, including seconds: `3600` renders as
 * `"1h"` (not the inconsistent `"1h 0s"`), while a sub-minute duration still
 * shows its seconds (`"0s"`, `"42s"`).
 * Package-internal for testability.
 */
internal fun formatDuration(seconds: Double): String {
    val totalSecs = seconds.toLong()
    val d = totalSecs / 86400
    val h = (totalSecs % 86400) / 3600
    val m = (totalSecs % 3600) / 60
    val s = totalSecs % 60
    val parts = buildList {
        if (d > 0) add("${d}d")
        if (h > 0) add("${h}h")
        if (m > 0) add("${m}m")
        if (s > 0 || isEmpty()) add("${s}s")
    }
    return parts.joinToString(" ")
}

/** "1.5 MB/s" -> "1.5 M/s"; values without a byte unit are unchanged. */
private val SPEED_UNIT = Regex("(?<=[KMGT])B/s")
private val SIZE_UNIT = Regex("(?<=[KMGT])B$")

/**
 * Drops the `B` from a byte unit reported by libeasyss, so the stats card can
 * label the value with its own unit text. Used together with [formatBytes]:
 * both keep the same K/M/G suffix family, the size formatter just also applies
 * the SI (1000-based) scale to raw byte counts.
 */
internal fun stripByteUnit(value: String): String {
    if (value.isEmpty()) return value
    return value.replace(SPEED_UNIT, "/s").replace(SIZE_UNIT, "")
}

/**
 * Download progress as a whole percentage in `0..100`. A non-positive total
 * means "size unknown" and yields 0, matching the indeterminate progress bar.
 */
internal fun formatPercent(downloaded: Long, total: Long): Int =
    if (total <= 0L) 0 else ((downloaded * 100) / total).toInt().coerceIn(0, 100)

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

/** Lower-case hex encoding, used for SHA-256 digests. */
internal fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value shr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}
