package com.oshri.multihotspot.ui

import java.util.Locale

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
    return String.format(Locale.US, if (v < 10) "%.1f %s" else "%.0f %s", v, units[i])
}

fun formatSince(epochSeconds: Long, nowMillis: Long = System.currentTimeMillis()): String {
    if (epochSeconds <= 0) return ""
    val s = (nowMillis / 1000 - epochSeconds).coerceAtLeast(0)
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min"
        s < 86400 -> "${s / 3600} h ${(s % 3600) / 60} min"
        else -> "${s / 86400} d"
    }
}

fun channelFrequency(channel: Int): Int = if (channel == 14) 2484 else 2407 + 5 * channel
