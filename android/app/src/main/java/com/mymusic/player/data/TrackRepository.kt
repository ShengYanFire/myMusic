package com.mymusic.player.data

import com.mymusic.player.domain.MusicGenres
import com.mymusic.player.domain.MusicMoods
import com.mymusic.player.domain.Track
import com.mymusic.player.network.BiliDirectClient
import com.mymusic.player.network.SearchItem
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first

/**
 * Business logic on top of the direct Bilibili client.
 */
class TrackRepository(
    private val api: BiliDirectClient,
    private val settings: AppSettings,
) {

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
                async { runCatching { api.search(keyword) }.getOrDefault(emptyList()) }
            }
        } else {
            val genrePicks = genres.shuffled().map { it.keywords }
            val moodPicks = moods.shuffled().map { it.keywords }
            val pools = buildList {
                var g = 0
                var m = 0
                while (size < MAX_PREFERENCE_SEARCHES && (g < genrePicks.size || m < moodPicks.size)) {
                    if (g < genrePicks.size) add(genrePicks[g++])
                    if (size < MAX_PREFERENCE_SEARCHES && m < moodPicks.size) add(moodPicks[m++])
                }
            }
            pools.map { keywords ->
                async { runCatching { api.search(keywords.random()) }.getOrDefault(emptyList()) }
            }
        }
        val rankingSources = listOf(
            async { runCatching { api.musicRanking() }.getOrDefault(emptyList()) },
            async { runCatching { api.musicSubRanking() }.getOrDefault(emptyList()) },
        )

        val genreLists = keywordSources.awaitAll()
        val rankingLists = rankingSources.awaitAll()

        if (!personalized) {
            // Generic feed: merge everything by bvid (later duplicates
            // overwrite) then shuffle.
            val merged = LinkedHashMap<String, SearchItem>()
            (genreLists + rankingLists).flatten().forEach { merged[it.bvid] = it }
            merged.values.shuffled()
        } else {
            // Personalized feed: preferred songs first (shuffled), ranking
            // songs appended as tail filler (deduped, shuffled) so scrolling
            // past the preferences still surfaces popular music.
            val preferred = LinkedHashMap<String, SearchItem>()
            genreLists.flatten().forEach { preferred[it.bvid] = it }
            val tail = LinkedHashMap<String, SearchItem>()
            rankingLists.flatten().forEach { if (it.bvid !in preferred) tail[it.bvid] = it }
            preferred.values.shuffled() + tail.values.shuffled()
        }
    }

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
     * Resolve the direct audio-stream URL for a track.
     * Two direct Bilibili calls: video info (for cid) + playurl (DASH audio).
     */
    suspend fun resolveAudio(track: Track): Track {
        val quality = settings.quality.first()
        val info = api.videoInfo(track.bvid)
        val cid = info.cid ?: throw RuntimeException("无法获取视频分 P 信息")
        val audio = api.audioStream(track.bvid, cid, quality)
        val candidates = audio.urls.ifEmpty { listOfNotNull(audio.url) }
        return track.copy(
            audioUrl = candidates.firstOrNull(),
            audioUrls = candidates,
            duration = (audio.duration ?: 0L).toInt().takeIf { it > 0 } ?: track.duration,
        )
    }

    suspend fun testConnection(): Boolean = api.ping()
}
