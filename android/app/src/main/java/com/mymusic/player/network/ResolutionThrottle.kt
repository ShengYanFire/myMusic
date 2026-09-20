package com.mymusic.player.network

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Global rate limiter for B站 audio-source resolution (the
 * `/x/player/wbi/playurl` call behind `BiliDirectClient.audioStream`).
 *
 * Every audio resolution funnels through that one call — the tapped song,
 * the background queue fill, a 下一首 on-demand skip, and the player's
 * error-recovery re-resolve — so gating here (rather than in each caller)
 * is the single choke point that cannot be bypassed or double-counted.
 *
 * Two guards, applied in order:
 *
 *  1. **Concurrency** — at most [maxConcurrent] resolutions run at once
 *     ([Semaphore]). This caps fan-out: the background fill fans 4 tracks at
 *     once, but only this many actually hit B站 simultaneously.
 *  2. **Spacing** — consecutive sends are at least [minIntervalMs] apart
 *     ([Mutex] + [delay]), turning a rapid string of switches — one playurl
 *     call per switch — into a steady trickle instead of a burst that trips
 *     B站's risk control (风控, `-412 请求被拦截`) and then cascades into every
 *     subsequent song failing.
 *
 * Rigour notes:
 *  - The permit is acquired BEFORE the spacing delay, and a start slot is
 *    stamped only once a coroutine is about to actually send (never before it
 *    has a permit) — otherwise two waiters could both "claim" spaced slots and
 *    then fire back-to-back the moment permits free up.
 *  - Cancellation is leak-free: `Semaphore.withPermit` releases in its
 *    `finally`, and `Mutex.withLock` unlocks in its own, so a caller cancelled
 *    while queued, while waiting out the spacing delay, or mid-block can never
 *    strand a slot for the rest of the process.
 *  - [nowMs] is monotonic ms and injectable for tests. The production default
 *    is `SystemClock.elapsedRealtime()` — the same uptime clock `delay()` is
 *    effectively measured against on Android, so the spacing stays correct
 *    across brief device deep-sleep (unlike `System.nanoTime()`).
 *
 * @param maxConcurrent  maximum simultaneous resolutions in flight (>= 1)
 * @param minIntervalMs  minimum gap between consecutive resolution sends (>= 0)
 * @param nowMs          monotonic millisecond clock (injectable for tests)
 */
class ResolutionThrottle(
    val maxConcurrent: Int = DEFAULT_MAX_CONCURRENT,
    val minIntervalMs: Long = DEFAULT_MIN_INTERVAL_MS,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    init {
        require(maxConcurrent >= 1) { "maxConcurrent must be >= 1, was $maxConcurrent" }
        require(minIntervalMs >= 0) { "minIntervalMs must be >= 0, was $minIntervalMs" }
    }

    private val permits = Semaphore(maxConcurrent)
    private val spacingGate = Mutex()

    /** Timestamp of the most recent resolution send; null until the first. */
    private var lastStartAtMs: Long? = null

    /**
     * Run [block] only once a concurrency slot is free AND at least
     * [minIntervalMs] have passed since the previous send.
     */
    suspend fun <T> gate(block: suspend () -> T): T = permits.withPermit {
        spacingGate.withLock {
            val last = lastStartAtMs
            if (last != null) {
                val wait = minIntervalMs - (nowMs() - last)
                if (wait > 0) delay(wait)
            }
            lastStartAtMs = nowMs()
        }
        block()
    }

    companion object {
        /** At most this many playurl resolutions in flight at once. */
        const val DEFAULT_MAX_CONCURRENT = 2

        /** Minimum gap between consecutive playurl resolution sends. */
        const val DEFAULT_MIN_INTERVAL_MS = 400L
    }
}