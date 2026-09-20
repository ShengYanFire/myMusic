package com.mymusic.player.network

import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The risk-control cooldown state machine, driven through the virtual clock.
 */
class RiskControlGateTest {

    @Test
    fun `classifies risk-control codes`() {
        val gate = RiskControlGate()
        assertTrue(gate.isRiskControl(-412))
        assertTrue(gate.isRiskControl(-509))
        assertFalse(gate.isRiskControl(-352)) // signature/validation, not throttling
        assertFalse(gate.isRiskControl(-101)) // guest /nav
        assertFalse(gate.isRiskControl(0))
        assertFalse(gate.isRiskControl(200))
    }

    @Test
    fun `trigger opens a cooldown that lapses after the window`() = runTest {
        val gate = RiskControlGate(cooldownMs = 15_000L, nowMs = { testScheduler.currentTime })
        assertNull(gate.remainingCooldownMs())

        gate.trigger()
        val remaining = gate.remainingCooldownMs()
        assertTrue("cooldown must be active after trigger", remaining != null && remaining > 0L)

        advanceTimeBy(15_000L)
        assertNull(gate.remainingCooldownMs())
    }

    @Test
    fun `a second trigger extends the cooldown from now`() = runTest {
        val gate = RiskControlGate(cooldownMs = 10_000L, nowMs = { testScheduler.currentTime })
        gate.trigger()
        advanceTimeBy(8_000L)
        gate.trigger() // re-hit while ~2s remain -> push the deadline out again

        advanceTimeBy(2_000L) // t=10s: the ORIGINAL window would have lapsed here
        assertEquals(8_000L, gate.remainingCooldownMs()) // but the re-trigger kept 8s

        advanceTimeBy(8_000L) // t=18s: the extended window ends
        assertNull(gate.remainingCooldownMs())
    }
}