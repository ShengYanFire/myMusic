package com.mymusic.player.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioUrlExpiryTest {

    @Test
    fun `parses deadline seconds into epoch ms`() {
        val url = "https://upos-sz.bilivideo.com/x.m4s?mid=1&deadline=1789890546&uipk=5&oi=1"
        assertEquals(1789890546L * 1000, AudioUrlExpiry.deadlineMs(url))
    }

    @Test
    fun `parses deadline as the leading query param`() {
        val url = "https://cdn/x.m4s?deadline=1789890546&build=0&dl=0"
        assertEquals(1789890546L * 1000, AudioUrlExpiry.deadlineMs(url))
    }

    @Test
    fun `missing or malformed deadline is null and never expired`() {
        assertNull(AudioUrlExpiry.deadlineMs("https://cdn/x.m4s?mid=1"))
        assertNull(AudioUrlExpiry.deadlineMs("https://cdn/x.m4s?deadline=abc"))
        assertNull(AudioUrlExpiry.deadlineMs(null))
        assertNull(AudioUrlExpiry.deadlineMs(""))
        // "Unknown" is deliberately never "expired": the reactive 403 fallback covers it.
        assertFalse(AudioUrlExpiry.isExpired("https://cdn/x.m4s?mid=1"))
    }

    @Test
    fun `isExpired compares deadline against now`() {
        val now = 1789890546L * 1000
        val past = "https://cdn/x.m4s?deadline=${(now - 60_000L) / 1000}"
        val boundary = "https://cdn/x.m4s?deadline=${now / 1000}"
        val future = "https://cdn/x.m4s?deadline=${(now + 60_000L) / 1000}"
        assertTrue(AudioUrlExpiry.isExpired(past, now))
        assertTrue(AudioUrlExpiry.isExpired(boundary, now)) // deadline == now counts as expired
        assertFalse(AudioUrlExpiry.isExpired(future, now))
    }

    @Test
    fun `remainingMs is negative once past the deadline`() {
        val now = 1789890546L * 1000
        val past = "https://cdn/x.m4s?deadline=${(now - 5_000) / 1000}"
        val future = "https://cdn/x.m4s?deadline=${(now + 5_000) / 1000}"
        assertEquals(-5_000L, AudioUrlExpiry.remainingMs(past, now)!!)
        assertEquals(5_000L, AudioUrlExpiry.remainingMs(future, now)!!)
    }
}