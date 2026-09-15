package com.mymusic.player.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WBI signing against the OFFICIAL key vector documented by the
 * bilibili-API-collect community docs:
 *
 *   img_key  = 7cd084941338484aae1ad9425b84077c
 *   sub_key  = 4932caff0ff746eab6f01bf08b70ac45
 *   mixin_key = ea1db124af3c7062474693fa704f4ff8
 *
 * The w_rid expectation was additionally cross-checked against an INDEPENDENT
 * MD5 implementation (System.Security.Cryptography) over the hand-sorted
 * query, so the whole pipeline (mixin key → filter → sort → percent-encode →
 * w_rid) is verified, not just self-consistency.
 */
class EncWbiTest {

    private val imgKey = "7cd084941338484aae1ad9425b84077c"
    private val subKey = "4932caff0ff746eab6f01bf08b70ac45"

    @Test
    fun `mixin key matches the official reference vector`() {
        assertEquals(
            "ea1db124af3c7062474693fa704f4ff8",
            BiliDirectClient.getMixinKey(imgKey + subKey),
        )
    }

    @Test
    fun `w_rid matches the cross-checked full signature vector`() {
        val signed = BiliDirectClient.encWbi(
            params = mapOf("foo" to "114", "bar" to "514", "baz" to "1919810"),
            imgKey = imgKey,
            subKey = subKey,
            wts = 1_702_204_800L,
        )
        // Keys are sorted, values JS-percent-encoded (none need it here).
        assertEquals(
            "bar=514&baz=1919810&foo=114&wts=1702204800&" +
                "w_rid=f1534ef4266290fe9661773c80e69b34",
            signed,
        )
    }

    @Test
    fun `params are sorted and w_rid is a 32-char hex digest`() {
        val signed = BiliDirectClient.encWbi(
            // Deliberately unsorted input map.
            params = mapOf("zeta" to 1, "alpha" to 2, "mid" to 3),
            imgKey = imgKey,
            subKey = subKey,
            wts = 1L,
        )
        // Keys are sorted, including the injected `wts` ('w' < 'z', so wts
        // sorts BEFORE zeta); values JS-percent-encoded (none need it here).
        assertTrue(signed.startsWith("alpha=2&mid=3&wts=1&zeta=1&w_rid="))
        assertTrue(
            "w_rid must be 32 lowercase hex chars: $signed",
            Regex("w_rid=[0-9a-f]{32}$").containsMatchIn(signed),
        )
    }

    @Test
    fun `reserved chars are stripped from values`() {
        assertEquals("abc", BiliDirectClient.filterReservedChars("a!b'c()*"))
    }

    @Test
    fun `encodeURIComponent is JS-compatible`() {
        assertEquals("a%20b", BiliDirectClient.encodeURIComponent("a b"))
        assertEquals("~x", BiliDirectClient.encodeURIComponent("~x"))
        assertEquals("%E5%88%9D%E9%9F%B3", BiliDirectClient.encodeURIComponent("初音"))
    }

    @Test
    fun `md5 matches the well-known digest of hello`() {
        assertEquals("5d41402abc4b2a76b9719d911017c592", BiliDirectClient.md5("hello"))
    }
}
