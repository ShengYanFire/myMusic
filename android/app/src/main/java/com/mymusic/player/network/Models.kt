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
    /** All usable audio URLs for this stream, ordered best-first. Includes
     *  backupUrl mirrors (often different CDN nodes) and lower tiers when the
     *  chosen quality has none, so the player can fall back on 403/404. */
    val urls: List<String> = listOf(url),
)

/**
 * A subtitle track listed by /x/player/wbi/v2 (CC subtitles and B站 AI 字幕).
 * [url] points to a JSON file on the hdslb CDN whose "body" array carries
 * {from, to, content} entries — the closest thing Bilibili has to lyrics.
 */
data class BiliSubtitle(
    val lan: String,
    val lanDoc: String,
    val url: String,
    /** 0 = human subtitles, non-zero = AI-generated. */
    val aiType: Int,
)
