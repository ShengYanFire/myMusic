package com.mymusic.player.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The whole point of [runSuspendCatching] is that it behaves exactly like
 * runCatching EXCEPT for cancellation — these tests pin both sides of that
 * contract.
 */
class RunSuspendCatchingTest {

    @Test
    fun `success propagates as Result success`() = runTest {
        val result = runSuspendCatching { 42 }
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `ordinary failures are captured as Result failure`() = runTest {
        val result = runSuspendCatching<String> { throw IllegalStateException("boom") }
        assertTrue(result.isFailure)
        assertEquals("boom", result.exceptionOrNull()?.message)
    }

    @Test
    fun `cancellation is rethrown, not captured`() = runTest {
        var sawCancellation = false
        // UNDISPATCHED so the body actually starts (reaches awaitCancellation)
        // before we cancel: under runTest's StandardTestDispatcher a plain
        // launch { } would never run before job.cancel(), so the throw would
        // never be observed.
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                // A cancelled runCatching would turn this into a FAILURE
                // result and complete "normally" — the helper must rethrow.
                runSuspendCatching { awaitCancellation() }
            } catch (e: CancellationException) {
                sawCancellation = true
                throw e
            }
        }
        job.cancel()
        job.join()
        assertTrue(sawCancellation)
    }
}
