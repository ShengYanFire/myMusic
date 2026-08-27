package com.mymusic.player.data

import com.mymusic.player.domain.Track
import com.mymusic.player.network.BiliDirectClient
import com.mymusic.player.network.SearchItem
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
     * Resolve the direct audio-stream URL for a track.
     * Two direct Bilibili calls: video info (for cid) + playurl (DASH audio).
     */
    suspend fun resolveAudio(track: Track): Track {
        val quality = settings.quality.first()
        val info = api.videoInfo(track.bvid)
        val cid = info.cid ?: throw RuntimeException("无法获取视频分 P 信息")
        val audio = api.audioStream(track.bvid, cid, quality)
        return track.copy(
            audioUrl = audio.url,
            duration = (audio.duration ?: 0L).toInt().takeIf { it > 0 } ?: track.duration,
        )
    }

    suspend fun testConnection(): Boolean = api.ping()
}
