package com.mymusic.player.network

/**
 * DTOs parsed directly from Bilibili API responses.
 */
data class SearchItem(
    val bvid: String,
    val title: String,
    val pic: String,
    val duration: Int,
    val author: String,
    val play: Long? = null,
)

/** Only the fields the player actually needs (the cid for page 1). */
data class VideoInfo(
    val bvid: String,
    val cid: Long?,
)

data class AudioInfo(
    val url: String,
    val duration: Long? = null,
)
