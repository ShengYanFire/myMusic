package com.mymusic.player.network

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Properties of the playurl rate limiter, verified against the virtual clock
 * (`testScheduler.currentTime`) so the tests are deterministic and never sleep
 * for real. Each test pins the limit being exercised and leaves the others at
 * their permissive defaults (interval 0 / concurrency 2), keeping every
 * assertion to a single invariant.
 */
class ResolutionThrottleTest {

    @Test
    fun `first call runs immediately with no spacing wait`() = runTest {
        val throttle = ResolutionThrottle(nowMs = { testScheduler.currentTime })
        var ran = false
        throttle.gate { ran = true }
        assertTrue(ran)
    }

    @Test
    fun `consecutive sends are spaced by minIntervalMs`() = runTest {
        val throttle = ResolutionThrottle(
            minIntervalMs = 400L,
            nowMs = { testScheduler.currentTime },
        )
        val starts = mutableListOf<Long>()
        val jobs = List(3) {
            launch { throttle.gate { starts += testScheduler.currentTime } }
        }
        advanceUntilIdle()
        jobs.forEach { it.join() }
        assertEquals(listOf(0L, 400L, 800L), starts)
    }

    @Test
    fun `at most maxConcurrent blocks run at once`() = runTest {
        val throttle = ResolutionThrottle(
            maxConcurrent = 2,
            minIntervalMs = 0L,
            nowMs = { testScheduler.currentTime },
        )
        var inFlight = 0
        var maxInFlight = 0
        val release = CompletableDeferred<Unit>()
        val jobs = List(5) {
            launch {
                throttle.gate<Unit> {
                    inFlight++
                    if (inFlight > maxInFlight) maxInFlight = inFlight
                    try {
                        release.await()
                    } finally {
                        inFlight--
                    }
                }
            }
        }
        advanceUntilIdle()
        assertEquals(2, maxInFlight)

        release.complete(Unit)
        advanceUntilIdle()
        jobs.forEach { it.join() }
        assertEquals(0, inFlight)
    }

    @Test
    fun `cancelling a waiter does not leak a permit`() = runTest {
        val throttle = ResolutionThrottle(
            maxConcurrent = 1,
            minIntervalMs = 0L,
            nowMs = { testScheduler.currentTime },
        )
        val blocker = CompletableDeferred<Unit>()
        // Holder takes the single permit and parks inside the block.
        val holder = launch { throttle.gate { blocker.await() } }
        runCurrent()

        var waiterRan = false
        val waiter = launch { throttle.gate { waiterRan = true } }
        runCurrent()
        // Waiter is suspended on acquire() with no permit; cancelling there
        // must not have consumed one.
        waiter.cancelAndJoin()
        assertFalse(waiterRan)

        blocker.complete(Unit)
        advanceUntilIdle()
        holder.join()

        // The release above freed the only permit; a fresh call succeeds.
        var ran = false
        throttle.gate { ran = true }
        assertTrue(ran)
    }

    @Test
    fun `a throwing block releases its permit`() = runTest {
        val throttle = ResolutionThrottle(
            maxConcurrent = 1,
            minIntervalMs = 0L,
            nowMs = { testScheduler.currentTime },
        )
        try {
            throttle.gate<Unit> { error("boom") }
        } catch (e: IllegalStateException) {
            // Expected — the gate passes the failure through.
        }
        // The failed call must not have stranded the single permit.
        var ran = false
        throttle.gate { ran = true }
        assertTrue(ran)
    }
}