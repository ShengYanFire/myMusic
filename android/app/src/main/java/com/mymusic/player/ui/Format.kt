package com.mymusic.player.ui

import java.util.Locale

fun formatDuration(seconds: Int): String {
    val s = seconds.coerceAtLeast(0)
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) {
        String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
    } else {
        String.format(Locale.US, "%d:%02d", m, sec)
    }
}

fun formatMs(ms: Long): String = formatDuration((ms / 1000).toInt())

fun formatPlayCount(play: Long?): String {
    val p = play ?: return ""
    return if (p >= 10000) {
        String.format(Locale.US, "%.1f万", p / 10000.0)
    } else {
        p.toString()
    }
}
