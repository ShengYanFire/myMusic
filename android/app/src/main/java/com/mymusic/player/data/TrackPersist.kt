package com.mymusic.player.data

import com.mymusic.player.domain.Track

/**
 * Shared persistence sanitizers for tracks, used by [LibraryStore] (favorites &
 * playlists) and [PlaybackProgressStore] (the remembered queue). B站 direct-stream
 * URLs expire after hours, so the URL is stripped before persisting — only the
 * identity and metadata the app needs every session are kept.
 */

/** Strip the expiring direct URLs before persisting. */
internal fun Track.sanitizeForSave(): Track =
    if (audioUrl != null || audioUrls.isNotEmpty()) {
        copy(audioUrl = null, audioUrls = emptyList())
    } else {
        this
    }

/**
 * Repair a Gson/Unsafe-deserialized track (Gson skips the constructor, so a
 * missing field reads as defaults/null through the non-null Kotlin type):
 * drop entries without a bvid, fix a missing `page` (Unsafe leaves it 0, which
 * breaks the uid identity) and a null title, and strip any legacy persisted
 * direct URLs.
 */
internal fun sanitizeLoadedTrack(track: Track?): Track? {
    val bvid = track?.bvid
    if (bvid.isNullOrBlank()) return null
    val page = if (track.page >= 1) track.page else 1
    return track.copy(
        title = track.title ?: "",
        page = page,
        audioUrl = null,
        audioUrls = emptyList(),
    )
}