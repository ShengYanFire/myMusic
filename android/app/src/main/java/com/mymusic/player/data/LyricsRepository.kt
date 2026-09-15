package com.mymusic.player.data

import com.mymusic.player.domain.Lyrics
import com.mymusic.player.domain.Track
import com.mymusic.player.network.BiliDirectClient
import com.mymusic.player.network.LyricsClient
import com.mymusic.player.util.runSuspendCatching
import java.util.LinkedHashMap

/**
 * Lyric resolution for a track, with a small in-memory cache per queue entry
 * (bvid, or bvid-P<n> for a 分P of a multi-P video — subtitles are per-page).
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

    /**
     * Small bounded LRU cache, uid → lyrics (null = "no lyrics found" negative
     * marker). LinkedHashMap(accessOrder=true) gives real least-recently-used
     * eviction — the previous ConcurrentHashMap iteration order is arbitrary,
     * so "evict the oldest first" actually evicted a random entry. A plain lock
     * restores the thread-safety this class promises without relying on every
     * caller being main-dispatcher-confined.
     */
    private val cache = LinkedHashMap<String, Lyrics?>(16, 0.75f, /* accessOrder = */ true)
    private val cacheLock = Any()

    suspend fun lyrics(track: Track, force: Boolean = false): Lyrics? {
        if (!force) {
            synchronized(cacheLock) {
                // A hit covers both a real result and a cached "no lyrics".
                if (track.uid in cache) return cache[track.uid]
            }
        }
        val result = runSuspendCatching { fromBilibili(track) }.getOrNull()
            ?: runSuspendCatching { fromLrcLib(track) }.getOrNull()
        synchronized(cacheLock) {
            // Re-insert to refresh access-order. Negative results are cached
            // too, so a no-lyrics track does not re-hit two network sources on
            // every back-and-forth; `force` is the manual refresh escape hatch.
            cache.remove(track.uid)
            cache[track.uid] = result
            // The head of an access-order map is the least-recently-used entry.
            while (cache.size > MAX_CACHE_ENTRIES) {
                val eldest = cache.keys.firstOrNull() ?: break
                cache.remove(eldest)
            }
        }
        return result
    }

    private suspend fun fromBilibili(track: Track): Lyrics? {
        // Prefer the cid resolved for this track's own 分P (multi-P videos
        // have per-page subtitles); fall back to the video's page-1 cid.
        val cid = track.cid ?: api.videoInfo(track.bvid).cid ?: return null
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

    private companion object {
        /** Lyrics are only ever viewed one track at a time; a few dozen
         *  cached entries cover back-and-forth navigation without unbounded
         *  memory growth in a long session. */
        const val MAX_CACHE_ENTRIES = 24
    }
}
