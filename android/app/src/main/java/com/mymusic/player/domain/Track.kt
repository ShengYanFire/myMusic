package com.mymusic.player.domain

/**
 * A playable song derived from a Bilibili video. [audioUrl] is resolved
 * lazily from the backend before playback.
 */
data class Track(
    val bvid: String,
    val title: String,
    val cover: String? = null,
    val author: String? = null,
    val duration: Int = 0,
    val audioUrl: String? = null,
)
