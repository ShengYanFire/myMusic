package com.mymusic.player.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure identity-cookie helpers — no Android needed.
 */
class BiliIdentityTest {

    @Test
    fun `buvid3 matches the accepted format`() {
        val pattern = Regex("[0-9A-F]{32}infoc")
        repeat(500) {
            assertTrue(BiliIdentity.generateBuvid3().matches(pattern))
        }
    }

    @Test
    fun `login identity keeps only stable cookies and drops volatile ones`() {
        val jar =
            "SESSDATA=abc; bili_jct=xyz; DedeUserID=123; DedeUserID__ckMd5=md5;" +
                " buvid3=dead; buvid4=gone; b_nut=123; b_lsid=lsid; _uuid=u; sid_8=1"
        assertEquals(
            "SESSDATA=abc; bili_jct=xyz; DedeUserID=123; DedeUserID__ckMd5=md5",
            BiliIdentity.loginIdentityCookies(jar),
        )
    }

    @Test
    fun `login identity is blank when nothing stable is present`() {
        assertEquals("", BiliIdentity.loginIdentityCookies("buvid3=dead; buvid4=gone"))
        assertEquals("", BiliIdentity.loginIdentityCookies(""))
    }

    @Test
    fun `api cookie puts buvid3 first and strips any jar buvid3`() {
        assertEquals(
            "buvid3=mine; SESSDATA=s; bili_jct=cs",
            BiliIdentity.buildApiCookie("mine", "buvid3=jarvalue; SESSDATA=s; bili_jct=cs"),
        )
    }

    @Test
    fun `api cookie is buvid3-only when not logged in`() {
        assertEquals("buvid3=mine", BiliIdentity.buildApiCookie("mine", ""))
    }
}