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
 * Package-internal for testability.
 */
internal fun formatDuration(seconds: Double): String {
    val totalSecs = seconds.toLong()
    val d = totalSecs / 86400
    val h = (totalSecs % 86400) / 3600
    val m = (totalSecs % 3600) / 60
    val s = totalSecs % 60
    return buildString {
        if (d > 0) append("${d}d ")
        if (h > 0) append("${h}h ")
        if (m > 0) append("${m}m ")
        append("${s}s")
    }
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
