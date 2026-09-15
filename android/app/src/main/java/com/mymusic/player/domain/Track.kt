package com.mymusic.player.domain

import androidx.compose.runtime.Immutable

/**
 * A playable song derived from a Bilibili video. [audioUrl] is resolved
 * lazily from the backend before playback. [audioUrls] carries every usable
 * direct URL (primary + mirror/backup + lower tiers, best-first) so the
 * player can fall back when a CDN node refuses a specific URL.
 *
 * Multi-P videos (视频选集): each 分P becomes its own queue entry with its own
 * [page] (1-based) and resolved [cid], so the pages of one video can coexist
 * in a play queue — [uid] is the stable identity for queue/player keys.
 *
 * [Immutable]: every field is a val and the lists are never mutated after
 * construction — Compose can then skip composables that hold a Track.
 */
@Immutable
data class Track(
    val bvid: String,
    val title: String,
    val cover: String? = null,
    val author: String? = null,
    val duration: Int = 0,
    val audioUrl: String? = null,
    val audioUrls: List<String> = emptyList(),
    /** 1-based 分P number of a multi-P video; 1 for normal single-P videos. */
    val page: Int = 1,
    /** Resolved cid of this track's own 分P (subtitle/lyrics lookups); transient. */
    val cid: Long? = null,
) {
    /**
     * Stable unique id for queue / player entries. Equal to the bvid for
     * page 1; pages > 1 get a "-P<n>" suffix so several pages of the same
     * video never collide in LazyColumn keys or player media ids.
     */
    val uid: String
        get() = if (page > 1) "$bvid-P$page" else bvid

    /**
     * All usable direct audio URLs, best-first: the candidate list with blank
     * entries dropped, falling back to the single [audioUrl]. The player
     * builds its queue and fallback list from this, so UI gating and playback
     * always agree on what is playable.
     */
    val usableAudioUrls: List<String>
        get() = audioUrls.filter { it.isNotBlank() }.ifEmpty { listOfNotNull(audioUrl) }

    /** True when this track carries at least one usable direct audio URL. */
    val hasAudio: Boolean
        get() = usableAudioUrls.isNotEmpty()
}
