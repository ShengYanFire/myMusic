package com.mymusic.player.data

import com.mymusic.player.domain.Lyrics
import com.mymusic.player.domain.Track
import com.mymusic.player.network.BiliDirectClient
import com.mymusic.player.network.LyricsClient

/**
 * Lyric resolution for a track, with a small in-memory cache per bvid.
 *
 * Sources, best-first (both time-synced to the actual played stream):
 *  1. Bilibili subtitles of the video (CC 字幕 / AI 字幕) — perfect sync,
 *     available for a growing share of videos (AI 字幕 often needs login);
 *  2. LRCLIB free lyrics database, searched with the cleaned video title —
 *     covers popular songs whose video has no subtitles at all.
 *
 * A source that fails or returns nothing degrades to the next; when all are
 * exhausted, null means "no lyrics found".
 */
class LyricsRepository(
    private val api: BiliDirectClient,
    private val lyricsApi: LyricsClient,
) {

    private val cache = LinkedHashMap<String, Lyrics>()

    suspend fun lyrics(track: Track, force: Boolean = false): Lyrics? {
        if (!force) cache[track.bvid]?.let { return it }
        val result = runCatching { fromBilibili(track) }.getOrNull()
            ?: runCatching { fromLrcLib(track) }.getOrNull()
        if (result != null) cache[track.bvid] = result
        return result
    }

    private suspend fun fromBilibili(track: Track): Lyrics? {
        val cid = api.videoInfo(track.bvid).cid ?: return null
        val subs = api.subtitleList(track.bvid, cid)
        if (subs.isEmpty()) return null
        // Prefer human Chinese subtitles, then AI Chinese, then anything else.
        val pick = subs.firstOrNull { it.lan.startsWith("zh") && it.aiType == 0 }
            ?: subs.firstOrNull { it.lan.startsWith("zh") }
            ?: subs.first()
        val lines = lyricsApi.fetchBiliSubtitle(pick.url)
        if (lines.isEmpty()) return null
        return Lyrics(
            lines = lines,
            synced = true,
            source = if (pick.aiType != 0) "B站AI字幕" else "B站字幕",
        )
    }

    private suspend fun fromLrcLib(track: Track): Lyrics? {
        val title = LyricsClient.cleanTitle(track.title)
        if (title.isBlank()) return null
        val artist = track.author?.trim()?.takeIf { it.isNotBlank() }
        return lyricsApi.searchLrcLib(title, artist)
    }
}
