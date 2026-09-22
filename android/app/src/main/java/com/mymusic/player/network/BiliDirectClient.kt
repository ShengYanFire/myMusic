package com.mymusic.player.network

import android.text.Html
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.mymusic.player.data.AppSettings
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Direct Bilibili API client — the app needs no backend server.
 *
 * Native Android has no CORS restriction, so we call api.bilibili.com straight
 * from the app, implementing the same WBI request signing the web does.
 *
 * Reference vector for the WBI mixin-key algorithm:
 *   img_key = 7cd084941338484aae1ad9425b84077c
 *   sub_key = 4932caff0ff746eab6f01bf08b70ac45
 *   mixin_key = ea1db124af3c7062474693fa704f4ff8
 */
class BiliDirectClient(
    private val settings: AppSettings,
    private val client: OkHttpClient,
    /**
     * Rate-limits `/x/player/wbi/playurl` resolution ([audioStream]) so rapid
     * track switching cannot fire a playurl burst that trips B站 risk control
     * (风控). A single instance app-wide — this client is built once in
     * MyMusicApp — so the throttle is naturally shared by every resolution
     * path (tapped song, background fill, 下一首 on-demand, error recovery).
     */
    private val throttle: ResolutionThrottle = ResolutionThrottle(),
    /**
     * Risk-control cooldown: one shared window app-wide, so a `-412/-509` seen
     * on ANY endpoint cools down subsequent resolution requests for a while.
     */
    private val riskGate: RiskControlGate = RiskControlGate(),
) {

    @Volatile
    private var wbi: WbiKeys? = null
    @Volatile
    private var wbiFetchedAt = 0L

    /** Serializes the WBI key fetch: concurrent signed requests share one /nav call. */
    private val wbiMutex = Mutex()

    private data class WbiKeys(val imgKey: String, val subKey: String)

    /** Search videos (same fields the old backend returned). */
    suspend fun search(keyword: String, page: Int = 1): List<SearchItem> {
        val body = get(
            "/x/web-interface/wbi/search/type",
            mapOf(
                "search_type" to "video",
                "keyword" to keyword,
                "page" to page,
                "page_size" to 20,
            ),
            signed = true,
        )
        val result = body.obj("data").get("result")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return result.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            if (o.str("type") != "video") return@mapNotNull null
            toSearchItem(o)
        }
    }

    /**
     * Music-trending videos (B站音乐分区排行榜, public unsigned endpoint).
     * One of three sources mixed into the "推荐" feed.
     */
    suspend fun musicRanking(limit: Int = 100): List<SearchItem> {
        val body = get(
            "/x/web-interface/ranking/v2",
            mapOf("rid" to 3),
            signed = false,
        )
        val list = body.obj("data").get("list")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return list.mapNotNull { (it as? JsonObject)?.let(::toSearchItem) }.take(limit)
    }

    /**
     * Music sub-area 3-day ranking (音乐综合 rid=30, public unsigned endpoint).
     * Second source of the "推荐" mix.
     */
    suspend fun musicSubRanking(limit: Int = 50): List<SearchItem> {
        val body = get(
            "/x/web-interface/ranking/region",
            mapOf("rid" to 30, "day" to 3),
            signed = false,
        )
        // This endpoint returns the ranked list as a top-level array under data.
        val arr = body.get("data")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return arr.mapNotNull { (it as? JsonObject)?.let(::toSearchItem) }.take(limit)
    }

    /**
     * Video detail (needed to get the cid for page 1). The response also
     * carries the video's 分P list (视频选集) and — when the video is part of
     * an uploader 合集 (ugc_season) — every episode of that collection, both
     * returned alongside the cid ([VideoInfo.pages] / [VideoInfo.seasonEpisodes]).
     */
    suspend fun videoInfo(bvid: String): VideoInfo = resolveGate {
        val d = get("/x/web-interface/view", mapOf("bvid" to bvid), signed = false)
            .obj("data")
        VideoInfo(
            cid = d.get("cid").longOrNull(),
            pages = videoPages(d),
            seasonEpisodes = seasonEpisodes(d),
        )
    }

    /** 分P list (视频选集) of a /view response; empty when the field is absent. */
    private fun videoPages(d: JsonObject): List<BiliPage> {
        val arr = d.get("pages")?.takeIf { it.isJsonArray }?.asJsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val cid = o.get("cid")?.longOrNull() ?: return@mapNotNull null
            BiliPage(
                cid = cid,
                page = o.get("page")?.longOrNull()?.toInt() ?: 1,
                part = o.str("part"),
                duration = o.get("duration")?.longOrNull()?.toInt() ?: 0,
            )
        }
    }

    /**
     * Videos of the 合集 a /view response carries, in order:
     * ugc_season.sections[].episodes[] (or a flat ugc_season.episodes[] on
     * the older shape), each mapped to a [SearchItem] from its arc (cover /
     * duration / uploader / play count). Empty when the video does not belong
     * to a collection. Parsed defensively — any unexpected shape simply
     * means "no collection".
     */
    private fun seasonEpisodes(d: JsonObject): List<SearchItem> {
        val season = d.objOrNull("ugc_season") ?: return emptyList()
        val sections = season.get("sections")?.takeIf { it.isJsonArray }?.asJsonArray
        // Newer responses group episodes under sections[]; older ones put
        // episodes directly on ugc_season — read whichever is present.
        val containers: List<JsonObject> = sections
            ?.mapNotNull { it as? JsonObject }
            ?: listOf(season)
        return containers.flatMap { sec ->
            val episodes = sec.get("episodes")?.takeIf { it.isJsonArray }?.asJsonArray
                ?: return@flatMap emptyList()
            episodes.mapNotNull { epEl ->
                val ep = epEl as? JsonObject ?: return@mapNotNull null
                val epBvid = ep.str("bvid")
                if (epBvid.isBlank()) return@mapNotNull null
                val arc = ep.objOrNull("arc")
                SearchItem(
                    bvid = epBvid,
                    title = stripHtml(ep.str("title").ifBlank { arc?.str("title").orEmpty() }),
                    pic = normalizePic(arc?.str("pic").orEmpty()),
                    duration = (arc?.get("duration")?.longOrNull() ?: ep.get("duration").longOrNull())
                        ?.toInt() ?: 0,
                    author = arc?.objOrNull("owner")?.str("name").orEmpty(),
                    play = arc?.objOrNull("stat")?.get("view")?.longOrNull(),
                )
            }
        }
    }

    /** Best audio-only DASH stream; quality "low" = least data. Returns the
     *  picked URL plus every available mirror/backup and lower tier so the
     *  player can degrade gracefully when a CDN node 403s. */
    suspend fun audioStream(bvid: String, cid: Long, quality: String): AudioInfo = resolveGate {
        val body = get(
            "/x/player/wbi/playurl",
            mapOf("bvid" to bvid, "cid" to cid, "fnval" to 16, "fourk" to 1),
            signed = true,
        )
        val dash = body.obj("data").get("dash")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: throw RuntimeException("该视频没有可用的纯音频流（可能未登录或需大会员）")
        // Defensive casts throughout: a malformed element means "skip the
        // tier", never a parse crash (consistent with the rest of this file).
        val list = dash.get("audio")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it as? JsonObject }.orEmpty()
        if (list.isEmpty()) {
            throw RuntimeException("该视频没有可用的纯音频流（可能未登录或需大会员）")
        }
        val sorted = list.sortedBy { it.get("bandwidth").longOr() }
        // Build the candidate list best-first: for "high" the highest bandwidth
        // tier first, for "low" the lowest first; each tier contributes its
        // baseUrl + backupUrl mirrors (different CDN nodes) before the next tier.
        val ordered = if (quality == "low") sorted else sorted.reversed()
        val urls = LinkedHashSet<String>()
        for (t in ordered) {
            t.str("baseUrl").takeIf { it.isNotBlank() }?.let { urls.add(it) }
            t.get("backupUrl")?.takeIf { it.isJsonArray }?.asJsonArray?.forEach { el ->
                (el as? JsonPrimitive)?.takeIf { it.isString }?.asString
                    ?.takeIf { it.isNotBlank() }?.let { urls.add(it) }
            }
        }
        if (urls.isEmpty()) {
            throw RuntimeException("该视频没有可用的纯音频流（可能未登录或需大会员）")
        }
        // Duration of the tier we actually picked (raw list order may differ
        // from the quality-ordered one).
        val first = ordered.first()
        AudioInfo(
            url = urls.first(),
            urls = urls.toList(),
            duration = first.get("duration").longOrNull()
                ?: dash.get("duration").longOrNull(),
        )
    }

    /** Connectivity check: fetch WBI keys from /nav (works even when logged out). */
    suspend fun ping(): Boolean = try {
        get("/x/web-interface/nav", emptyMap(), signed = false, allowLoggedOut = true)
        true
    } catch (e: CancellationException) {
        // A cancelled ping must report neither "ok" nor "broken" — propagate.
        throw e
    } catch (e: Exception) {
        false
    }

    /**
     * Subtitle tracks of a video (CC + AI 字幕) from /x/player/wbi/v2 —
     * the lyric source for the lyrics page. Empty when the video has none
     * (very common); AI entries often additionally require the login cookie.
     */
    suspend fun subtitleList(bvid: String, cid: Long): List<BiliSubtitle> {
        val body = get(
            "/x/player/wbi/v2",
            mapOf("bvid" to bvid, "cid" to cid),
            signed = true,
        )
        val subtitle = body.objOrNull("data")?.get("subtitle")?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return emptyList()
        val arr = subtitle.get("subtitles")?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return arr.mapNotNull { el ->
            if (!el.isJsonObject) return@mapNotNull null
            val o = el.asJsonObject
            val url = normalizePic(o.str("subtitle_url"))
            if (url.isBlank()) return@mapNotNull null
            BiliSubtitle(
                lan = o.str("lan"),
                lanDoc = o.str("lan_doc"),
                url = url,
                aiType = o.get("ai_type").intOr(),
            )
        }
    }

    // ------------------------------------------------------------------
    //  HTTP core + WBI signing
    // ------------------------------------------------------------------

    private suspend fun get(
        path: String,
        params: Map<String, Any>,
        signed: Boolean,
        allowLoggedOut: Boolean = false,
    ): JsonObject =
        withContext(Dispatchers.IO) {
            // Build the query string ALREADY percent-encoded, then pass the whole
            // URL to OkHttp which parses it as-is (no double-encoding).
            val query = if (signed) {
                val keys = wbiKeys()
                encWbi(params, keys.imgKey, keys.subKey)
            } else {
                params.entries.joinToString("&") { (k, v) ->
                    "${encodeURIComponent(k)}=${encodeURIComponent(v.toString())}"
                }
            }
            val url = buildString {
                append("https://api.bilibili.com")
                append(path)
                if (query.isNotEmpty()) {
                    append('?')
                    append(query)
                }
            }

            val requestBuilder = Request.Builder()
                .url(url)
                .header("User-Agent", BiliHeaders.DESKTOP_UA)
                .header("Referer", BiliHeaders.REFERER)
            val cookie = biliCookie()
            if (cookie.isNotBlank()) {
                requestBuilder.header("Cookie", cookie)
            }

            client.awaitCall(requestBuilder.build()).use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("B 站返回 HTTP ${resp.code}")
                val text = resp.body?.string() ?: throw RuntimeException("B 站返回空响应")
                val obj = JsonParser.parseString(text).asJsonObject
                val code = obj.get("code").intOr(-1)
                // /nav returns -101 "账号未登录" for guests, but still carries the
                // wbi_img keys in data — so tolerate it where login isn't required.
                if (code != 0 && !(allowLoggedOut && code == -101)) {
                    val msg = obj.get("message")?.asString ?: "未知错误"
                    if (riskGate.isRiskControl(code)) {
                        // A risk-control code opens the cooldown right at the
                        // source, so -412/-509 on ANY endpoint arms it (not just
                        // playurl). Runs on the IO dispatcher; the gate's
                        // @Volatile field makes the Main-side check see it now.
                        riskGate.trigger()
                        throw BiliRiskControlException("B 站接口错误（$code）$msg", code)
                    }
                    throw RuntimeException("B 站接口错误（$code）$msg")
                }
                obj
            }
        }

    /**
     * Gate for the two resolution endpoints (/view + playurl): short-circuits
     * with a readable hint while a risk-control cooldown is active, and
     * otherwise runs through the global [throttle] (concurrency + spacing). A
     * risk-control rejection returned inside [block] opens the cooldown for
     * the NEXT call (done centrally by [get]).
     */
    private suspend fun <T> resolveGate(block: suspend () -> T): T {
        riskGate.remainingCooldownMs()?.let { remaining ->
            // ceil(remaining / 1000), min 1 — a 15s window reads "约 15 秒",
            // not "约 16 秒" (the old `remaining/1000 + 1` over-rounded).
            val secs = (remaining + 999) / 1000
            throw BiliRiskControlException("请求过于频繁，请稍后再试（约 $secs 秒）")
        }
        return throttle.gate { block() }
    }

    /**
     * The Cookie header for api.bilibili.com: the stable device fingerprint
     * (buvid3) first, then the complete login identity. A consistent
     * device+login pair reads as a normal browser to B站 risk control — the
     * thing that lifts the 风控 threshold behind mass parse failures. Never
     * used for media/CDN requests (those stay cookie-free).
     */
    private suspend fun biliCookie(): String =
        BiliIdentity.buildApiCookie(
            buvid3 = settings.ensureBuvid3(),
            loginCookies = settings.cookie.first(),
        )

    /**
     * WBI keys with a single-flight fetch: the mutex guarantees that the
     * parallel searches of a recommendation reload all SHARE one /nav call
     * instead of each firing a duplicate (10+ requests on a cold cache, which
     * reads as a request burst to Bilibili's risk control).
     */
    private suspend fun wbiKeys(): WbiKeys = wbiMutex.withLock {
        val now = System.currentTimeMillis()
        val cached = wbi?.takeIf { now - wbiFetchedAt < WBI_TTL }
        if (cached != null) return@withLock cached
        val img = get(
            "/x/web-interface/nav", emptyMap(), signed = false, allowLoggedOut = true,
        )
            .obj("data")
            .obj("wbi_img")
        val imgUrl = img.str("img_url")
        val subUrl = img.str("sub_url")
        if (imgUrl.isBlank() || subUrl.isBlank()) {
            throw RuntimeException("B 站响应缺少 WBI 密钥")
        }
        // Key = the file name sans extension. substringAfter/-BeforeLast never
        // throw: a malformed /nav response missing '/' or '.' falls back to the
        // whole input instead of crashing with a StringIndexOutOfBoundsException.
        val imgKey = imgUrl.substringAfterLast('/').substringBeforeLast('.')
        val subKey = subUrl.substringAfterLast('/').substringBeforeLast('.')
        val keys = WbiKeys(imgKey, subKey)
        wbi = keys
        wbiFetchedAt = now
        keys
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private fun JsonObject.obj(name: String): JsonObject =
        getAsJsonObject(name) ?: throw RuntimeException("B 站响应缺少 $name")

    private fun JsonObject.objOrNull(name: String): JsonObject? =
        if (has(name) && get(name).isJsonObject) getAsJsonObject(name) else null

    /**
     * Map any video object (search / ranking v2 / ranking region / popular) to
     * a [SearchItem], tolerating the two author/play shapes Bilibili uses:
     * direct `author`/`play` fields (old ranking, region) vs `owner`/`stat`
     * nested objects (ranking v2, popular, search).
     */
    private fun toSearchItem(o: JsonObject): SearchItem? {
        val bvid = o.str("bvid")
        if (bvid.isBlank()) return null
        return SearchItem(
            bvid = bvid,
            title = stripHtml(o.str("title")),
            pic = normalizePic(o.str("pic")),
            duration = o.get("duration").intOr(),
            author = o.str("author").ifBlank { o.objOrNull("owner")?.str("name").orEmpty() },
            play = o.get("play").longOrNull()
                ?: o.objOrNull("stat")?.get("view")?.longOrNull(),
        )
    }

    /** String field reading that tolerates absent values AND JSON nulls
     *  (JsonNull is not a JsonPrimitive, so a null field reads back as ""). */
    private fun JsonObject.str(name: String): String =
        (get(name) as? JsonPrimitive)?.asString ?: ""

    /**
     * Robust number reading. Bilibili sometimes returns numbers as decimal strings
     * (e.g. "50.14" for a DASH duration), which strict asInt/asLong would crash on.
     * Numbers are truncated to whole; strings have any decimal part stripped.
     */
    private fun JsonElement?.longOrNull(): Long? {
        if (this == null) return null
        if (this is JsonPrimitive) {
            if (this.isNumber) return this.asLong
            val s = this.asString.trim()
            if (s.isEmpty()) return null
            return s.substringBefore('.').toLongOrNull()
        }
        return null
    }

    private fun JsonElement?.longOr(default: Long = 0L): Long = longOrNull() ?: default

    private fun JsonElement?.intOr(default: Int = 0): Int = longOrNull()?.toInt() ?: default

    /**
     * Strip HTML tags AND decode entities: Bilibili wraps the search keyword
     * in `<em class="keyword">…</em>` and may escape the rest, so a naive tag
     * regex would leave `&amp;` / `&#39;` literals in the title.
     */
    private fun stripHtml(text: String): String =
        Html.fromHtml(text, Html.FROM_HTML_MODE_COMPACT).toString().trim()

    /** Scheme-agnostic CDN links are upgraded to https (the CDN serves it);
     *  already-https and non-http (data:/relative) links pass through. */
    private fun normalizePic(pic: String): String = when {
        pic.startsWith("//") -> "https:$pic"
        pic.startsWith("http://") -> "https://" + pic.removePrefix("http://")
        else -> pic
    }

    companion object {
        private const val WBI_TTL = 10 * 60 * 1000L

        /** JS-compatible encodeURIComponent (space -> %20, ~ unencoded, hex uppercase). */
        internal fun encodeURIComponent(value: String): String {
            val enc = java.net.URLEncoder.encode(value, "UTF-8")
            return enc.replace("+", "%20").replace("%7E", "~")
        }

        /**
         * WBI request signing:
         * add wts, drop !'()* chars, sort keys, JS-style percent-encode, md5(query+mixinKey).
         *
         * [wts] is injectable so tests can pin the timestamp against a known
         * vector (default: now).
         */
        internal fun encWbi(
            params: Map<String, Any>,
            imgKey: String,
            subKey: String,
            wts: Long = System.currentTimeMillis() / 1000,
        ): String {
            val mixinKey = getMixinKey(imgKey + subKey)
            val query = HashMap<String, String>()
            params.forEach { (k, v) -> query[k] = filterReservedChars(v.toString()) }
            query["wts"] = wts.toString()
            val enc = query.keys.sorted().joinToString("&") { k ->
                "${encodeURIComponent(k)}=${encodeURIComponent(query[k]!!)}"
            }
            return "$enc&w_rid=${md5(enc + mixinKey)}"
        }

        /**
         * Extracts the 32-char WBI mixin key by shuffling the concatenated
         * img+sub keys through the fixed [MIXIN_TAB] index table. Reference
         * vector: 7cd084941338484aae1ad9425b84077c + 4932caff0ff746eab6f01bf08b70ac45
         * → ea1db124af3c7062474693fa704f4ff8.
         */
        internal fun getMixinKey(orig: String): String {
            val sb = StringBuilder()
            for (i in MIXIN_TAB) sb.append(orig[i])
            return sb.toString().substring(0, 32)
        }

        internal fun filterReservedChars(value: String): String =
            value.replace("[!'()*]".toRegex(), "")

        internal fun md5(input: String): String {
            val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
        }

        private val MIXIN_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52,
        )
    }
}
