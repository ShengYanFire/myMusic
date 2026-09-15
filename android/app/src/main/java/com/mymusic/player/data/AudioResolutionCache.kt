package com.mymusic.player.data

import com.mymusic.player.domain.Track
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
     */
    fun resolveShared(track: Track): Deferred<Result<Track>> {
        val uid = track.uid
        cache[uid]?.let { (cached, at) ->
            if (System.currentTimeMillis() - at < TTL_MS && cached.hasAudio) {
                return CompletableDeferred(Result.success(cached))
            }
            cache.remove(uid)
        }
        inFlight[uid]?.let { return it }
        val deferred = scope.async(start = CoroutineStart.LAZY) {
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
                // Even a failure or a cancelled await must unregister, or a
                // later request would forever join a dead deferred.
                inFlight.remove(uid)
            }
        }
        inFlight[uid] = deferred
        deferred.start() // begin the round trip NOW, not at the first await
        return deferred
    }

    /** Await the shared resolution future; null when it failed. */
    suspend fun resolve(track: Track): Track? = resolveShared(track).await().getOrNull()

    /**
     * The freshly-resolved (TTL-valid) copy of [track] from this cache, or
     * null. Used when rebuilding a play queue: entries resolved moments ago
     * keep their fresh URLs, everything else gets its long-expired stored URLs
     * stripped.
     */
    fun cached(track: Track): Track? {
        cache[track.uid]?.let { (cached, at) ->
            if (System.currentTimeMillis() - at < TTL_MS && cached.hasAudio) {
                return cached
            }
        }
        return null
    }

    private companion object {
        private const val TTL_MS = 30 * 60_000L
        private const val MAX_ENTRIES = 500
    }
}