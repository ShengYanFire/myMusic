package com.mymusic.player.data

import com.mymusic.player.domain.MusicGenres
import com.mymusic.player.domain.MusicMoods
import com.mymusic.player.domain.Track
import com.mymusic.player.network.BiliDirectClient
import com.mymusic.player.network.BiliPage
import com.mymusic.player.network.SearchItem
import com.mymusic.player.util.runSuspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * A resolved [Track] together with the 分P list (视频选集) and the 合集
 * (uploader collection) the video belongs to — everything arrives from the
 * same Bilibili calls, so a collection/multi-page-aware play queue costs no
 * extra request.
 */
data class ResolvedTrack(
    val track: Track,
    /** Every video of the B站 合集 the track belongs to (empty = none). */
    val seasonEpisodes: List<SearchItem> = emptyList(),
    /** 分P list of the track's video (one entry for single-P videos). */
    val pages: List<BiliPage> = emptyList(),
)

/**
 * Business logic on top of the direct Bilibili client.
 *
 * Implements [TrackResolver] (the seam the playback-queue pipeline and its
 * unit tests use) for the two audio-resolution entry points.
 */
class TrackRepository(
    private val api: BiliDirectClient,
    private val settings: AppSettings,
) : com.mymusic.player.ui.TrackResolver {

    suspend fun search(keyword: String, page: Int = 1): List<SearchItem> =
        api.search(keyword, page)

    /**
     * Mixed recommendation pool for the search page "推荐" feed:
     * music ranking + music sub-area ranking + music-keyword searches,
     * fetched in parallel, deduped by bvid and shuffled (打散随机). A failing
     * source degrades to the others so the feed still returns something.
     *
     * With favorite genres and/or moods set (音乐/心情偏好) the feed is
     * personalized: each selected genre/mood contributes one random keyword
     * search, and those preferred songs are shuffled to the FRONT of the pool
     * (ranking songs only back-fill the tail), so the first batches the user
     * sees match what they like.
     */
    suspend fun recommendPool(): List<SearchItem> = coroutineScope {
        val genres = settings.favoriteGenres.first()
            .mapNotNull { MusicGenres.byId(it) }
        val moods = settings.favoriteMoods.first()
            .mapNotNull { MusicMoods.byId(it) }
        val personalized = genres.isNotEmpty() || moods.isNotEmpty()

        // Keyword searches: one random keyword per selected genre/mood. The
        // picks are interleaved round-robin so neither dimension crowds out
        // the other, then capped to keep the request count bounded. Without
        // any preference, fall back to two random music keywords so the feed
        // stays generic but fresh.
        val keywordSources = if (!personalized) {
            MUSIC_KEYWORDS.shuffled().take(2).map { keyword ->
                async { runSuspendCatching { api.search(keyword) }.getOrDefault(emptyList()) }
            }
        } else {
            val genrePicks = genres.shuffled().map { it.keywords }
            val moodPicks = moods.shuffled().map { it.keywords }
            interleave(genrePicks, moodPicks).take(MAX_PREFERENCE_SEARCHES).map { keywords ->
                async { runSuspendCatching { api.search(keywords.random()) }.getOrDefault(emptyList()) }
            }
        }
        val rankingSources = listOf(
            async { runSuspendCatching { api.musicRanking() }.getOrDefault(emptyList()) },
            async { runSuspendCatching { api.musicSubRanking() }.getOrDefault(emptyList()) },
        )

        val genreLists = keywordSources.awaitAll()
        val rankingLists = rankingSources.awaitAll()

        if (!personalized) {
            // Generic feed: merge everything by bvid then shuffle.
            mergeByBvid(genreLists + rankingLists).shuffled()
        } else {
            // Personalized feed: preferred songs first (shuffled), ranking
            // songs appended as tail filler (deduped, shuffled) so scrolling
            // past the preferences still surfaces popular music.
            val preferred = mergeByBvid(genreLists)
            val preferredIds = preferred.mapTo(HashSet()) { it.bvid }
            preferred.shuffled() +
                mergeByBvid(rankingLists).filterNot { it.bvid in preferredIds }.shuffled()
        }
    }

    /** Round-robin interleave of two lists, preserving each list's own order. */
    private fun <T> interleave(a: List<T>, b: List<T>): List<T> = buildList {
        for (i in 0 until maxOf(a.size, b.size)) {
            a.getOrNull(i)?.let(::add)
            b.getOrNull(i)?.let(::add)
        }
    }

    /** Merge result lists into a bvid-deduped list (later entries overwrite). */
    private fun mergeByBvid(lists: List<List<SearchItem>>): List<SearchItem> =
        lists.flatten().associateByTo(LinkedHashMap()) { it.bvid }.values.toList()

    companion object {
        private val MUSIC_KEYWORDS = listOf(
            "热门歌曲", "流行音乐", "钢琴曲", "纯音乐", "翻唱",
            "民谣", "说唱", "电子音乐", "古风音乐", "轻音乐",
            "吉他弹唱", "治愈音乐", "经典老歌", "R&B", "摇滚现场",
            "音乐现场", "器乐演奏", "华语经典",
        )

        /** Max genre/mood keyword searches per reload (request cap). */
        private const val MAX_PREFERENCE_SEARCHES = 8
    }

    /**
     * Resolve the direct audio-stream URL for a track, together with its
     * video's 分P list and 合集. Two direct Bilibili calls: video info
     * (cid + pages + ugc_season episodes) + playurl (DASH audio). Multi-P
     * tracks ([Track.page] > 1) resolve the cid of their OWN 分P so each
     * page plays (and subtitles/lyrics follow) the right content.
     */
    override suspend fun resolveAudioWithSeason(track: Track): ResolvedTrack {
        val quality = settings.quality.first()
        val info = api.videoInfo(track.bvid)
        val cid = if (track.page > 1) {
            info.pages.firstOrNull { it.page == track.page }?.cid
                ?: throw RuntimeException("无法获取分P P${track.page}（视频分P可能已调整）")
        } else {
            info.cid ?: info.pages.firstOrNull()?.cid
                ?: throw RuntimeException("无法获取视频分 P 信息")
        }
        val audio = api.audioStream(track.bvid, cid, quality)
        val candidates = audio.urls.ifEmpty { listOfNotNull(audio.url) }
        return ResolvedTrack(
            track = track.copy(
                audioUrl = candidates.firstOrNull(),
                audioUrls = candidates,
                cid = cid,
                duration = (audio.duration ?: 0L).toInt().takeIf { it > 0 } ?: track.duration,
            ),
            seasonEpisodes = info.seasonEpisodes,
            pages = info.pages,
        )
    }

    /** Resolve the direct audio-stream URL for a track (no collection info). */
    suspend fun resolveAudio(track: Track): Track = resolveAudioWithSeason(track).track

    /**
     * Resolve the direct audio-stream URL with the FEWEST B站 calls: when the
     * track already knows its own cid (favorites / recently played entries
     * persist it via Gson), go straight to playurl — ONE call instead of the
     * /view + playurl pair — halving the resolution latency for the queue
     * fill and 下一首. Falls back to the full round trip whenever the fast
     * call fails (stale cid after an uploader edit, transient B站 error).
     *
     * Callers that need the 分P / 合集 info (the queue context of a freshly
     * tapped song) must use [resolveAudioWithSeason] instead.
     */
    override suspend fun resolveAudioFast(track: Track): Track {
        val cid = track.cid
        if (cid != null && cid > 0) {
            try {
                val quality = settings.quality.first()
                val audio = api.audioStream(track.bvid, cid, quality)
                val candidates = audio.urls.ifEmpty { listOfNotNull(audio.url) }
                return track.copy(
                    audioUrl = candidates.firstOrNull(),
                    audioUrls = candidates,
                    duration = (audio.duration ?: 0L).toInt().takeIf { it > 0 } ?: track.duration,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Fall through: stale cid / edited video / transient B站
                // error — the full path re-derives everything from /view.
            }
        }
        return resolveAudio(track)
    }

    suspend fun testConnection(): Boolean = api.ping()
}
