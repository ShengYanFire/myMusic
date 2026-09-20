package com.mymusic.player.network

import android.os.SystemClock

/**
 * Global cooldown for B站 risk control (风控).
 *
 * Bilibili rate-limits aggressive clients: `/x/player/wbi/playurl` and its
 * siblings answer a burst with a risk-control business code — `-412 请求被拦
 * 截`, or `-509` throttling. Retrying immediately only strengthens and
 * lengthens the block. So once any API response carries such a code,
 * [trigger] opens a short window; [remainingCooldownMs] reports how much of it
 * is left, and audio-source resolution short-circuits during that window
 * instead of hammering B站 — turning a cascade of "解析失败" into one clear
 * "请稍后再试" plus automatic recovery when the window lapses.
 *
 * @param cooldownMs length of the cooldown after a risk-control rejection
 * @param nowMs      monotonic millisecond clock (injectable for tests)
 */
class RiskControlGate(
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {
    init {
        require(cooldownMs >= 0) { "cooldownMs must be >= 0, was $cooldownMs" }
    }

    /**
     * Monotonic timestamp until which risk control is assumed active. Marked
     * [Volatile] because [trigger] runs on the IO dispatcher (from inside the
     * response-parsing `get`) while [remainingCooldownMs] runs on the caller's
     * (Main) dispatcher.
     */
    @Volatile
    private var coolDownUntilMs = 0L

    /** Whether [code] is a risk-control rejection the cooldown should react to. */
    fun isRiskControl(code: Int): Boolean = code in RISK_CODES

    /** Record a risk-control hit and (re)start the cooldown window. */
    fun trigger() {
        coolDownUntilMs = nowMs() + cooldownMs
    }

    /** Milliseconds of cooldown remaining, or null when not cooling down. */
    fun remainingCooldownMs(): Long? = (coolDownUntilMs - nowMs()).takeIf { it > 0 }

    companion object {
        /** Default cooldown after a risk-control rejection. */
        const val DEFAULT_COOLDOWN_MS = 15_000L

        /**
         * Business codes treated as risk control: `-412 请求被拦截` (the classic
         * rate-limit/风控 block) and `-509` request throttling. `-352 风控校验
         * 失败` is deliberately excluded — it usually indicates an invalid WBI
         * signature, which waiting does not repair.
         */
        private val RISK_CODES = setOf(-412, -509)
    }
}