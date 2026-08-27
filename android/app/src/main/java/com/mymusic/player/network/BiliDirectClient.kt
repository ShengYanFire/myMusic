package com.mymusic.player.network

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.mymusic.player.data.AppSettings
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
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
class BiliDirectClient(private val settings: AppSettings) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var wbi: WbiKeys? = null
    private var wbiFetchedAt = 0L

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
        val result = body.obj("data").getAsJsonArray("result")
            ?: return emptyList()
        return result.mapNotNull { el ->
            val o = el.asJsonObject
            if (o.get("type")?.asString != "video") return@mapNotNull null
            SearchItem(
                bvid = o.str("bvid"),
                title = stripHtml(o.str("title")),
                pic = normalizePic(o.str("pic")),
                duration = o.get("duration")?.asInt ?: 0,
                author = o.str("author"),
                play = o.get("play")?.asLong,
            )
        }
    }

    /** Video detail (needed to get the cid for page 1). */
    suspend fun videoInfo(bvid: String): VideoInfo {
        val d = get("/x/web-interface/view", mapOf("bvid" to bvid), signed = false)
            .obj("data")
        return VideoInfo(bvid = d.str("bvid"), cid = d.get("cid")?.asLong)
    }

    /** Best audio-only DASH stream; quality "low" = least data. */
    suspend fun audioStream(bvid: String, cid: Long, quality: String): AudioInfo {
        val body = get(
            "/x/player/wbi/playurl",
            mapOf("bvid" to bvid, "cid" to cid, "fnval" to 16, "fourk" to 1),
            signed = true,
        )
        val dash = body.obj("data").getAsJsonObject("dash")
            ?: throw RuntimeException("该视频没有可用的纯音频流（可能未登录或需大会员）")
        val list = dash.getAsJsonArray("audio")?.map { it.asJsonObject }.orEmpty()
        if (list.isEmpty()) {
            throw RuntimeException("该视频没有可用的纯音频流（可能未登录或需大会员）")
        }
        val sorted = list.sortedBy { it.get("bandwidth")?.asLong ?: 0L }
        val pick = if (quality == "low") sorted.first() else sorted.last()
        return AudioInfo(
            url = pick.str("baseUrl"),
            duration = pick.get("duration")?.asLong
                ?: dash.get("duration")?.asLong ?: 0L,
        )
    }

    /** Connectivity check: fetch WBI keys from /nav. */
    suspend fun ping(): Boolean =
        runCatching { get("/x/web-interface/nav", emptyMap(), signed = false) }.isSuccess

    // ------------------------------------------------------------------
    //  HTTP core + WBI signing
    // ------------------------------------------------------------------

    private suspend fun get(path: String, params: Map<String, Any>, signed: Boolean): JsonObject =
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
                .header("User-Agent", UA)
                .header("Referer", "https://www.bilibili.com/")
            val cookie = settings.cookie.first()
            if (cookie.isNotBlank()) {
                requestBuilder.header("Cookie", cookie)
            }

            client.newCall(requestBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) throw RuntimeException("B 站返回 HTTP ${resp.code}")
                val text = resp.body?.string() ?: throw RuntimeException("B 站返回空响应")
                val obj = JsonParser.parseString(text).asJsonObject
                val code = obj.get("code")?.asInt ?: -1
                if (code != 0) {
                    val msg = obj.get("message")?.asString ?: "未知错误"
                    throw RuntimeException("B 站接口错误（$code）$msg")
                }
                obj
            }
        }

    private suspend fun wbiKeys(): WbiKeys {
        val now = System.currentTimeMillis()
        if (wbi != null && now - wbiFetchedAt < WBI_TTL) return wbi!!
        val img = get("/x/web-interface/nav", emptyMap(), signed = false)
            .obj("data")
            .obj("wbi_img")
        val imgUrl = img.str("img_url")
        val subUrl = img.str("sub_url")
        val imgKey = imgUrl.substring(imgUrl.lastIndexOf('/') + 1, imgUrl.lastIndexOf('.'))
        val subKey = subUrl.substring(subUrl.lastIndexOf('/') + 1, subUrl.lastIndexOf('.'))
        wbi = WbiKeys(imgKey, subKey)
        wbiFetchedAt = now
        return wbi!!
    }

    /**
     * WBI request signing:
     * add wts, drop !'()* chars, sort keys, JS-style percent-encode, md5(query+mixinKey).
     */
    private fun encWbi(params: Map<String, Any>, imgKey: String, subKey: String): String {
        val mixinKey = getMixinKey(imgKey + subKey)
        val query = HashMap<String, String>()
        params.forEach { (k, v) -> query[k] = filterReservedChars(v.toString()) }
        query["wts"] = (System.currentTimeMillis() / 1000).toString()
        val enc = query.keys.sorted().joinToString("&") { k ->
            "${encodeURIComponent(k)}=${encodeURIComponent(query[k]!!)}"
        }
        return "$enc&w_rid=${md5(enc + mixinKey)}"
    }

    private fun getMixinKey(orig: String): String {
        val sb = StringBuilder()
        for (i in MIXIN_TAB) sb.append(orig[i])
        return sb.toString().substring(0, 32)
    }

    private fun filterReservedChars(value: String): String =
        value.replace("[!'()*]".toRegex(), "")

    /** JS-compatible encodeURIComponent (space -> %20, ~ unencoded, hex uppercase). */
    private fun encodeURIComponent(value: String): String {
        val enc = java.net.URLEncoder.encode(value, "UTF-8")
        return enc.replace("+", "%20").replace("%7E", "~")
    }

    private fun md5(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private fun JsonObject.obj(name: String): JsonObject =
        getAsJsonObject(name) ?: throw RuntimeException("B 站响应缺少 $name")

    private fun JsonObject.str(name: String): String = get(name)?.asString ?: ""

    private fun stripHtml(text: String): String =
        text.replace(Regex("<[^>]+>"), "").trim()

    private fun normalizePic(pic: String): String =
        if (pic.startsWith("//")) "https:$pic" else pic

    companion object {
        private const val WBI_TTL = 10 * 60 * 1000L
        const val UA = "Mozilla/5.0 (Linux; Android 13) MyMusic/1.0"

        private val MIXIN_TAB = intArrayOf(
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52,
        )
    }
}
