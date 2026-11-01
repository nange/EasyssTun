package com.easysstun

import java.util.Locale

internal fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> String.format(Locale.ROOT, "%.2fG", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.2fM", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.ROOT, "%.2fK", bytes / 1_000.0)
        else -> "${bytes}B"
    }
}

/** 零值分量一律省略（含秒）：`3600` 输出 `1h` 而非 `1h 0s`，不足一分钟仍显示秒。 */
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

private val SPEED_UNIT = Regex("(?<=[KMGT])B/s")
private val SIZE_UNIT = Regex("(?<=[KMGT])B$")

/** 去掉 libeasyss 回报的字节单位（`1.5 MB/s` → `1.5 M/s`），由卡片自行标注单位；K/M/G 后缀须与 [formatBytes] 一致。 */
internal fun stripByteUnit(value: String): String {
    if (value.isEmpty()) return value
    return value.replace(SPEED_UNIT, "/s").replace(SIZE_UNIT, "")
}

/** 总大小为非正数表示大小未知，返回 0 以匹配不确定进度条。 */
internal fun formatPercent(downloaded: Long, total: Long): Int =
    if (total <= 0L) 0 else ((downloaded * 100) / total).toInt().coerceIn(0, 100)

private val HEX_DIGITS = "0123456789abcdef".toCharArray()

internal fun ByteArray.toHex(): String = buildString(size * 2) {
    for (byte in this@toHex) {
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value shr 4])
        append(HEX_DIGITS[value and 0x0f])
    }
}
