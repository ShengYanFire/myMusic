package com.mymusic.player.network

/**
 * Deadline parsing for the direct-stream URLs B站's playurl endpoint returns.
 *
 * Every `upos-*.m4s` DASH URL carries a `deadline` query parameter (unix
 * seconds, e.g. `...&deadline=1789890546&...`) past which the CDN rejects the
 * URL with HTTP 403. Two invariants follow from it:
 *
 *  - a URL past its deadline is dead even while a cache entry is still inside
 *    its own TTL, so caches must actually expire on `min(TTL, deadline)`;
 *  - mirror rotation can never rescue a deadline expiry — every mirror in one
 *    playurl response shares the same `deadline` — so the player should go
 *    straight to re-resolution once the deadline has passed.
 *
 * A missing/unparsable deadline means "unknown expiry", which is deliberately
 * never treated as "expired": the reactive 403 fallback still covers those.
 */
object AudioUrlExpiry {

    private val DEADLINE = Regex("[?&]deadline=(\\d+)")

    /** The deadline as epoch milliseconds, or null when absent/unparsable. */
    fun deadlineMs(url: String?): Long? {
        if (url.isNullOrBlank()) return null
        val match = DEADLINE.find(url) ?: return null
        return match.groupValues[1].toLongOrNull()?.let { it * 1000L }
    }

    /** Milliseconds until [url] expires; negative once past the deadline; null
     *  when the URL carries no deadline. */
    fun remainingMs(url: String?, nowMs: Long = System.currentTimeMillis()): Long? {
        val deadline = deadlineMs(url) ?: return null
        return deadline - nowMs
    }

    /** True only when [url] is provably past its deadline. */
    fun isExpired(url: String?, nowMs: Long = System.currentTimeMillis()): Boolean {
        val remaining = remainingMs(url, nowMs) ?: return false
        return remaining <= 0L
    }
}