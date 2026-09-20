package com.mymusic.player.data

import com.mymusic.player.domain.Track
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the cache's in-flight lifecycle: the in-flight dedup, the new
 * `cancelInFlight` (used when a play queue supersedes the previous one), and
 * the `===` guard that keeps an old future's cleanup from clobbering a newer
 * registration for the same uid.
 */
class AudioResolutionCacheTest {

    @Test
    fun `concurrent callers of the same uid share one resolution`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val cache = AudioResolutionCache(
            resolveFast = { track ->
                gate.await()
                track.copy(audioUrl = "https://cdn/${track.uid}.m4a")
            },
            scope = this,
        )
        val a = cache.resolveShared(track("A"))
        val b = cache.resolveShared(track("A"))
        assertTrue(a === b)
    }

    @Test
    fun `cancelInFlight aborts an in-flight resolution`() = runTest {
        val blocker = CompletableDeferred<Unit>()
        var cancelled = false
        val cache = AudioResolutionCache(
            resolveFast = { track ->
                try {
                    blocker.await()
                    track.copy(audioUrl = "https://cdn/${track.uid}.m4a")
                } catch (e: CancellationException) {
                    cancelled = true
                    throw e
                }
            },
            scope = this,
        )
        cache.resolveShared(track("A"))
        runCurrent() // the resolution starts and parks on blocker.await()
        cache.cancelInFlight()
        advanceUntilIdle()
        assertTrue(cancelled)
    }

    @Test
    fun `a superseded resolution does not clobber a newer one for the same uid`() = runTest {
        val gate = CompletableDeferred<Unit>()
        var resolutions = 0
        val cache = AudioResolutionCache(
            resolveFast = { track ->
                resolutions++
                gate.await()
                track.copy(audioUrl = "https://cdn/${track.uid}.m4a")
            },
            scope = this,
        )
        val first = cache.resolveShared(track("A"))
        runCurrent() // first is now parked inside gate.await()
        cache.cancelInFlight()

        // A brand-new resolution takes over the same uid while the first is
        // still unwinding its cancellation.
        val second = cache.resolveShared(track("A"))
        runCurrent() // second parks in gate.await(); first's finally runs here

        // A third caller must join the LIVE second future, not start another.
        val third = cache.resolveShared(track("A"))
        assertTrue(third === second)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(2, resolutions) // first + second, never a third
    }

    private fun track(bvid: String) = Track(bvid = bvid, title = "t-$bvid")
}