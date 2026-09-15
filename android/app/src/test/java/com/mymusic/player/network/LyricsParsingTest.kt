package com.mymusic.player.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function coverage for the lyrics pipeline: LRC parsing and the messy
 * Bilibili-title → song-name reduction that feeds LRCLIB queries.
 */
class LyricsParsingTest {

    // ---- parseLrc ----

    @Test
    fun `parses simple timed lines in order`() {
        val lrc = """
            [00:01.00]第一行
            [00:05.50]第二行
            [00:10]第三行
        """.trimIndent()
        val lines = LyricsClient.parseLrc(lrc)
        assertEquals(3, lines.size)
        assertEquals(1_000L, lines[0].startMs)
        assertEquals(5_500L, lines[1].startMs)
        assertEquals(10_000L, lines[2].startMs)
        assertEquals("第一行", lines[0].text)
        // Each line ends where the next starts.
        assertEquals(5_500L, lines[0].endMs)
        assertEquals(10_000L, lines[1].endMs)
    }

    @Test
    fun `several timestamps on one line each become their own entry`() {
        val lines = LyricsClient.parseLrc("[00:01.00][00:09.00]副歌")
        assertEquals(2, lines.size)
        assertEquals("副歌", lines[0].text)
        assertEquals("副歌", lines[1].text)
        assertEquals(1_000L, lines[0].startMs)
        assertEquals(9_000L, lines[1].startMs)
    }

    @Test
    fun `out-of-order lines are sorted by start time`() {
        val lines = LyricsClient.parseLrc("[00:09.00]late\n[00:02.00]early")
        assertEquals(2, lines.size)
        assertEquals("early", lines[0].text)
        assertEquals(2_000L, lines[0].startMs)
        assertEquals(9_000L, lines[0].endMs)
    }

    @Test
    fun `last line gets a generous end so it stays highlighted`() {
        val lines = LyricsClient.parseLrc("[00:30.00]最后一行")
        assertEquals(1, lines.size)
        assertTrue(lines[0].endMs > 30_000L)
    }

    @Test
    fun `metadata tags and empty text lines are ignored`() {
        val lines = LyricsClient.parseLrc("[ti:标题]\n[by:作者]\n[00:01.00]\n[00:02.00]正文")
        assertEquals(1, lines.size)
        assertEquals("正文", lines[0].text)
    }

    // ---- cleanTitle ----

    @Test
    fun `strips bracket noise and filler words`() {
        // Brackets carry channel/series noise and are removed wholesale.
        assertEquals(
            "千本桜",
            LyricsClient.cleanTitle("【初音未来】千本桜 MV 中文字幕"),
        )
    }

    @Test
    fun `strips separators between song name and artist`() {
        assertEquals(
            "歌曲名 歌手",
            LyricsClient.cleanTitle("歌曲名/歌手"),
        )
    }

    @Test
    fun `collapses whitespace and trims`() {
        val cleaned = LyricsClient.cleanTitle("  a   b  ")
        assertEquals("a b", cleaned)
    }

    @Test
    fun `plain titles pass through unchanged`() {
        assertEquals("Lemon", LyricsClient.cleanTitle("Lemon"))
    }
}
