package com.mymusic.player.domain

/**
 * A playable song derived from a Bilibili video. [audioUrl] is resolved
 * lazily from the backend before playback. [audioUrls] carries every usable
 * direct URL (primary + mirror/backup + lower tiers, best-first) so the
 * player can fall back when a CDN node refuses a specific URL.
 */
data class Track(
    val bvid: String,
    val title: String,
    val cover: String? = null,
    val author: String? = null,
    val duration: Int = 0,
    val audioUrl: String? = null,
    val audioUrls: List<String> = emptyList(),
) {
    /**
     * True when this track carries at least one usable direct audio URL.
     * Matches the player's own playability check (audioUrls first, then the
     * single audioUrl), so UI gating and playback agree on what is playable.
     */
    val hasAudio: Boolean
        get() = audioUrls.any { it.isNotBlank() } || !audioUrl.isNullOrBlank()
}
