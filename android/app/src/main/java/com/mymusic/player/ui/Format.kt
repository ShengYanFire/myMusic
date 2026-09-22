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

/**
 * Collapse a preference-label list into a one-line summary: up to [max] labels
 * joined with " · ", followed by " 等 N 项" when there are more. Shared by the
 * collapsed settings preference card and the recommendation header.
 */
fun summarizeLabels(labels: List<String>, max: Int = 4): String {
    val shown = labels.take(max).joinToString(" · ")
    return if (labels.size > max) "$shown 等 ${labels.size} 项" else shown
}
