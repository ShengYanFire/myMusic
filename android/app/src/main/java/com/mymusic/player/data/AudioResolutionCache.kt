package com.mymusic.player.data

import com.mymusic.player.domain.Track
import com.mymusic.player.network.AudioUrlExpiry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import java.util.LinkedHashMap

/**
 * App-scoped cache for resolved audio URLs, with in-flight dedup.
 *
 * B站 direct URLs stay valid for hours, so a short TTL (30 min) lets replays
 * and queue re-fills serve instantly instead of paying another B站 round trip,
 * and the LRU (access-order) eviction drops the least-recently-used entry once
 * bounded — never the whole cache. The in-flight map makes the queue fill, a
 * 下一首 on-demand tap, a replay, and the player's error-recovery path SHARE
 * one round trip for the same track instead of duplicating it.
 *
 * This class exists so the resolution cache is no longer owned by a single
 * production caller (the play-queue coordinator) while the player's
 * error-recovery path bypassed it through [TrackRepository] directly — two
 * caches that disagreed. Both now go through the one injected instance.
 *
 * Thread-safety: [resolveShared]/[cached] and every map write run on the
 * Main dispatcher (the coordinator's viewModelScope and the player controller
 * both resolve on Main), and [resolveFast] does its network work off-thread via
 * its own `withContext(Dispatchers.IO)` — so the maps are effectively
 * single-threaded, matching the previous implementation's assumption.
 */
class AudioResolutionCache(
    private val resolveFast: suspend (Track) -> Track,
    private val scope: CoroutineScope,
) {

    /** uid → (resolved track, resolvedAtMs), access-ordered for LRU eviction. */
    private val cache = LinkedHashMap<String, Pair<Track, Long>>(16, 0.75f, /* accessOrder = */ true)

    /** In-flight resolutions, uid → deferred, so concurrent callers share one call. */
    private val inFlight = mutableMapOf<String, Deferred<Result<Track>>>()

    /**
     * The shared resolution future for [track]: a fresh cache hit completes
     * immediately, an in-flight request is returned as-is, and otherwise a new
     * resolution STARTS NOW (a caller asking early gives later callers a head
     * start). Failures resolve to [Result.failure]; cancellation propagates.
     *
     * [force] bypasses the TTL cache hit (and evicts any cached copy first):
     * the player's error-recovery uses it so a "re-resolve" can never hand back
     * the very URL that just 403'd. A remaining in-flight future is still
     * shared because an in-flight future is, by construction, a fresh round
     * trip started on a cache miss.
     */
    fun resolveShared(track: Track, force: Boolean = false): Deferred<Result<Track>> {
        val uid = track.uid
        if (force) {
            cache.remove(uid)
        } else {
            cache[uid]?.let { (cached, at) ->
                if (isFresh(cached, at)) {
                    return CompletableDeferred(Result.success(cached))
                }
                cache.remove(uid)
            }
        }
        inFlight[uid]?.let { return it }
        lateinit var deferred: Deferred<Result<Track>>
        deferred = scope.async(start = CoroutineStart.LAZY) {
            try {
                val result = try {
                    Result.success(resolveFast(track))
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Result.failure<Track>(e)
                }
                result.getOrNull()?.takeIf { it.hasAudio }?.let { fresh ->
                    if (cache.size >= MAX_ENTRIES) {
                        cache.remove(cache.keys.first())
                    }
                    cache[uid] = fresh to System.currentTimeMillis()
                }
                result
            } finally {
                // Unregister ONLY if we are still the registered future: a
                // superseding play queue calls [cancelInFlight] and may then
                // start a brand-new resolution for the same uid, and this old
                // future's finally must not clobber that newer registration.
                // (A failure/cancelled await still unregisters itself.)
                if (inFlight[uid] === deferred) inFlight.remove(uid)
            }
        }
        inFlight[uid] = deferred
        deferred.start() // begin the round trip NOW, not at the first await
        return deferred
    }

    /** Await the shared resolution future; null when it failed. */
    suspend fun resolve(track: Track): Track? = resolveShared(track).await().getOrNull()

    /**
     * Cancel every in-flight resolution and drop the registration map.
     *
     * Called by the queue coordinator exactly once per play-queue supersession:
     * the moment a new song is tapped, the previous episode's background fill
     * and any on-demand/预热 resolutions are no longer wanted. Cancelling them
     * here — instead of letting their app-scope coroutines run orphaned to
     * completion — makes `Network.awaitCall` actually cancel the underlying
     * B站 request, so rapid switching stops emitting orphaned requests the
     * instant the old queue is abandoned.
     *
     * The per-uid resolved-URL TTL cache is left intact (only in-flight work
     * is dropped), so a later replay/skip still reuses fresh URLs. Snapshot +
     * clear-before-cancel plus the [resolveShared] `===` guard keep this race
     * free: an old future's `finally` can never unregister a newer one that
     * already took over the same uid.
     */
    fun cancelInFlight() {
        val inFlightNow = inFlight.values.toList()
        inFlight.clear()
        inFlightNow.forEach { it.cancel() }
    }

    /**
     * The freshly-resolved (TTL-valid) copy of [track] from this cache, or
     * null. Used when rebuilding a play queue: entries resolved moments ago
     * keep their fresh URLs, everything else gets its long-expired stored URLs
     * stripped.
     */
    fun cached(track: Track): Track? {
        cache[track.uid]?.let { (cached, at) ->
            if (isFresh(cached, at)) {
                return cached
            }
        }
        return null
    }

    /**
     * A cached entry is usable when it has audio, is inside [TTL_MS], and its
     * primary URL's own `deadline` has not lapsed. The last condition matters:
     * a URL that expires (or the CDN refuses early, e.g. after a network/IP
     * switch) within the TTL window is just as dead as a long-expired one, and
     * must not round-trip as a "success".
     */
    private fun isFresh(entry: Track, resolvedAtMs: Long): Boolean {
        if (!entry.hasAudio) return false
        val now = System.currentTimeMillis()
        if (now - resolvedAtMs >= TTL_MS) return false
        val primary = entry.usableAudioUrls.firstOrNull()
        return !AudioUrlExpiry.isExpired(primary, now)
    }

    private companion object {
        private const val TTL_MS = 30 * 60_000L
        private const val MAX_ENTRIES = 500
    }
}