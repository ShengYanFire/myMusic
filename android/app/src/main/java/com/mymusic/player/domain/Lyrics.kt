package com.mymusic.player.domain

/**
 * One line of time-synced lyrics.
 *
 * [startMs]/[endMs] locate the line on the playback timeline. [endMs] is
 * derived (the next line's start) and only used cosmetically; highlight
 * math relies on [startMs] alone.
 */
data class LyricLine(
    val startMs: Long,
    val endMs: Long,
    val text: String,
)

/**
 * Lyrics for a track. [synced] is false for plain (untimed) lyrics, which
 * render as a static list without highlight or auto-scroll.
 * [source] is a human-readable origin, e.g. "B站AI字幕" / "网络歌词".
 */
data class Lyrics(
    val lines: List<LyricLine>,
    val synced: Boolean,
    val source: String,
)
