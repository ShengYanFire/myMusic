package com.mymusic.player.network

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.mymusic.player.data.AppSettings
import com.mymusic.player.domain.LyricLine
import com.mymusic.player.domain.Lyrics
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Lyrics networking that does NOT go through api.bilibili.com:
 *  - downloads Bilibili subtitle JSON files from the hdslb CDN (needs the
 *    same Referer/UA headers as cover images, plus the cookie when present);
 *  - searches LRCLIB (https://lrclib.net, free, no key) as a fallback when a
 *    video has no subtitles at all, which is the common case.
 */
class LyricsClient(private val settings: AppSettings) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    /**
     * Download a Bilibili subtitle JSON ([url] from [BiliSubtitle]) and parse
     * its "body" array of {from, to, content} entries into timed lines.
     */
    suspend fun fetchBiliSubtitle(url: String): List<LyricLine> =
        withContext(Dispatchers.IO) {
            val builder = Request.Builder()
                .url(url)
                .header("User-Agent", BiliDirectClient.UA)
                .header("Referer", "https://www.bilibili.com/")
            val cookie = settings.cookie.first()
            if (cookie.isNotBlank()) {
                builder.header("Cookie", cookie)
            }
            client.newCall(builder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("字幕下载失败（HTTP ${resp.code}）")
                val text = resp.body?.string() ?: throw RuntimeException("字幕下载失败：空响应")
                val obj = JsonParser.parseString(text).asJsonObject
                val body = obj.get("body")?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: return@withContext emptyList()
                body.mapNotNull { el ->
                    if (!el.isJsonObject) return@mapNotNull null
                    val o = el.asJsonObject
                    val content = o.get("content")?.asString?.trim().orEmpty()
                    if (content.isEmpty()) return@mapNotNull null
                    val from = o.get("from").doubleOrNull() ?: 0.0
                    val to = o.get("to").doubleOrNull() ?: from
                    LyricLine(
                        startMs = (from * 1000).toLong(),
                        endMs = (to * 1000).toLong(),
                        text = content,
                    )
                }
            }
        }

    /**
     * Search LRCLIB for lyrics matching [title] (already cleaned) and an
     * optional [artist]. Tries the precise track/artist query first, then a
     * free-text query, then track-only. Prefers synced lyrics and entries
     * whose artist/title actually match. Returns null when nothing is found.
     */
    suspend fun searchLrcLib(title: String, artist: String?): Lyrics? {
        val attempts = buildList {
            if (!artist.isNullOrBlank()) {
                add(mapOf("track_name" to title, "artist_name" to artist))
            }
            add(mapOf("q" to title))
            add(mapOf("track_name" to title))
        }
        for (params in attempts) {
            val results = runCatching { lrcLibSearch(params) }.getOrDefault(emptyList())
            val best = pickBest(results, title, artist)
            if (best != null) return best
        }
        return null
    }

    private suspend fun lrcLibSearch(params: Map<String, String>): List<JsonObject> =
        withContext(Dispatchers.IO) {
            val query = params.entries.joinToString("&") { (k, v) ->
                "${encodeURIComponent(k)}=${encodeURIComponent(v)}"
            }
            val request = Request.Builder()
                .url("$LRCLIB_BASE/api/search?$query")
                .header("User-Agent", LRCLIB_UA)
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val text = resp.body?.string() ?: return@withContext emptyList()
                val parsed = runCatching { JsonParser.parseString(text) }.getOrNull()
                val arr = parsed?.takeIf { it.isJsonArray }?.asJsonArray
                    ?: return@withContext emptyList()
                arr.mapNotNull { el -> el.takeIf { it.isJsonObject }?.asJsonObject }
            }
        }

    /** Score candidates: synced lyrics + artist/title match win; never instrumental. */
    private fun pickBest(
        results: List<JsonObject>,
        title: String,
        artist: String?,
    ): Lyrics? {
        data class Scored(val score: Int, val lyrics: Lyrics)

        val candidates = results.mapNotNull { o ->
            if (o.get("instrumental")?.asBooleanOrNull() == true) return@mapNotNull null
            val synced = o.get("syncedLyrics")?.asStringOrNull()
            val plain = o.get("plainLyrics")?.asStringOrNull()
            val trackName = o.get("trackName")?.asStringOrNull().orEmpty()
            val artistName = o.get("artistName")?.asStringOrNull().orEmpty()
            val hasSynced = !synced.isNullOrBlank()
            val hasPlain = !plain.isNullOrBlank()
            if (!hasSynced && !hasPlain) return@mapNotNull null

            val normTitle = normalizeForMatch(title)
            val normArtist = artist?.takeIf { it.isNotBlank() }?.let { normalizeForMatch(it) }
            val nameScore =
                (if (normTitle.isNotEmpty() &&
                    normalizeForMatch(trackName).contains(normTitle)) 2 else 0) +
                    (if (normArtist != null &&
                        normalizeForMatch(artistName).contains(normArtist)) 1 else 0)
            val score = (if (hasSynced) 4 else 0) + nameScore
            val lyrics = if (hasSynced) {
                val lines = parseLrc(synced!!)
                if (lines.isEmpty()) {
                    // Timestamps existed but nothing parsed: fall back to plain.
                    plainLines(plain)
                } else {
                    Lyrics(lines = lines, synced = true, source = "网络歌词")
                }
            } else {
                plainLines(plain)
            }
            if (lyrics.lines.isEmpty()) return@mapNotNull null
            Scored(score, lyrics)
        }
        return candidates.maxByOrNull { it.score }?.lyrics
    }

    private fun plainLines(plain: String?): Lyrics =
        Lyrics(
            lines = plain.orEmpty()
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { LyricLine(startMs = 0, endMs = 0, text = it) },
            synced = false,
            source = "网络歌词（未同步）",
        )

    /**
     * Parse an LRC string ("[mm:ss.xx]line", possibly several timestamps per
     * line, metadata tags like [ti:] ignored) into timed, sorted lines. The
     * last line gets a generous 10 s end so it stays highlighted.
     */
    fun parseLrc(lrc: String): List<LyricLine> {
        val out = mutableListOf<Pair<Long, String>>()
        lrc.lines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            val stamps = LRC_STAMP.findAll(line).toList()
            if (stamps.isEmpty()) return@forEach
            val text = line.substring(stamps.last().range.last + 1).trim()
            if (text.isEmpty()) return@forEach
            stamps.forEach { m ->
                val min = m.groupValues[1].toLongOrNull() ?: return@forEach
                val sec = m.groupValues[2].toLongOrNull() ?: return@forEach
                val frac = m.groupValues[3]
                val fracMs = when (frac.length) {
                    0 -> 0L
                    1 -> frac.toLongOrNull()?.times(100)
                    2 -> frac.toLongOrNull()?.times(10)
                    else -> frac.take(3).toLongOrNull()
                } ?: 0L
                val t = (min * 60 + sec) * 1000 + fracMs
                out.add(t to text)
            }
        }
        val sorted = out.sortedBy { it.first }
        return sorted.mapIndexed { i, (t, text) ->
            val end = sorted.getOrNull(i + 1)?.first ?: (t + 10_000)
            LyricLine(startMs = t, endMs = end, text = text)
        }
    }

    companion object {
        private const val LRCLIB_BASE = "https://lrclib.net"

        // LRCLIB asks for a descriptive User-Agent; no bili cookie ever goes here.
        private const val LRCLIB_UA = "MyMusic-Android/1.0 (bilibili music player; lyrics fallback)"

        private val LRC_STAMP =
            Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

        /** Bracketed noise that Bilibili video titles carry around the song name. */
        private val BRACKET_NOISE = listOf(
            Regex("""【[^】]*】"""),
            Regex("""（[^）]*）"""),
            Regex("""\([^)]*\)"""),
            Regex("""\[[^\]]*\]"""),
            Regex("""「[^」]*」"""),
            Regex("""『[^』]*』"""),
        )

        /** MV-ish filler words (ASCII, case-insensitive). */
        private val ASCII_FILLER =
            Regex("""(?i)\b(MV|PV|MAD|OP|ED|FULL|LIVE|HD|4K|1080P|60FPS|COVER(ed)?|VER)\b""")

        /** CJK filler words commonly appended to music video titles. */
        private val CJK_FILLER = listOf(
            "中文字幕", "字幕", "高清", "完整版", "完整", "现场版", "音源", "无损",
            "自制", "搬运", "修复", "重置版", "燃向", "高音质",
        )

        /** Separators between song name and artist in a title. */
        private val SEPARATORS = Regex("""[|/／\\|丨·~～—\-–_]+""")

        /**
         * Reduce a messy Bilibili title ("【初音未来】千本桜 MV 中文字幕") to a
         * plain song-name query ("初音未来 千本桜") for LRCLIB.
         */
        fun cleanTitle(title: String): String {
            var t = title
            BRACKET_NOISE.forEach { t = t.replace(it, " ") }
            t = t.replace(ASCII_FILLER, " ")
            CJK_FILLER.forEach { t = t.replace(it, " ") }
            t = t.replace(SEPARATORS, " ")
            return t.trim().replace(Regex("""\s+"""), " ")
        }

        private fun normalizeForMatch(s: String): String =
            s.lowercase().replace(Regex("""[\s\-–—·'’`]"""), "")

        private fun encodeURIComponent(value: String): String {
            val enc = java.net.URLEncoder.encode(value, "UTF-8")
            return enc.replace("+", "%20").replace("%7E", "~")
        }
    }

    // ---- small JSON helpers (independent of BiliDirectClient's privates) ----

    private fun JsonElement?.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.asString

    private fun JsonElement?.asBooleanOrNull(): Boolean? =
        (this as? JsonPrimitive)?.takeIf { it.isBoolean }?.asBoolean

    private fun JsonElement?.doubleOrNull(): Double? {
        if (this == null || this !is JsonPrimitive) return null
        if (isNumber) return asDouble
        return asString.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
    }
}
