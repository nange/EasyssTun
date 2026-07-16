package com.easysstun

import java.util.Locale

/**
 * Formats a byte count into a human-readable string.
 * Package-internal for testability.
 */
internal fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> String.format(Locale.ROOT, "%.2f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.2f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.ROOT, "%.2f KB", bytes / 1_000.0)
        else -> "$bytes B"
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
