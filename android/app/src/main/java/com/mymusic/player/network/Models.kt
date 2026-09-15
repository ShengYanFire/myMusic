package com.mymusic.player.network

import com.mymusic.player.domain.Track

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

/** Convert a search result into the playable [Track] domain model. */
fun SearchItem.toTrack(): Track = Track(
    bvid = bvid,
    title = title,
    cover = pic,
    author = author,
    duration = duration,
)

/**
 * One 分P of a multi-P video (视频选集), from /x/web-interface/view's pages[]:
 * each page has its own cid (audio stream + subtitles are per-page).
 */
data class BiliPage(
    val cid: Long,
    val page: Int,
    /** Page title as shown in B站's 选集 list ("01 歌名"); may be blank. */
    val part: String,
    val duration: Int,
)

/**
 * Fields of /x/web-interface/view the player needs: the cid for page 1, the
 * video's 分P list (视频选集), plus — when the video belongs to an uploader
 * 合集 (ugc_season) — every video of that collection in order. All ride
 * along on the same /view request, so none of it costs an extra API call.
 */
data class VideoInfo(
    val cid: Long?,
    val pages: List<BiliPage> = emptyList(),
    val seasonEpisodes: List<SearchItem> = emptyList(),
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
