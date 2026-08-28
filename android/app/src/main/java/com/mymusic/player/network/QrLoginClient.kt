package com.mymusic.player.network

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Bilibili web QR login, authorized by scanning with the Bilibili app.
 *
 * Flow: generate a QR -> render it -> poll until the user confirms in the
 * Bilibili app -> the final redirect URL carries SESSDATA/bili_jct/DedeUserID,
 * which we turn into the app's cookie setting. No third-party OAuth is needed:
 * this is the same official web "扫码登录" flow, so it works for guests.
 */
class QrLoginClient {

    // Persist cookies (e.g. buvid3) set by the generate response so the poll
    // request isn't flagged as a bot by passport.bilibili.com's risk control.
    private val cookieStore = mutableMapOf<String, List<Cookie>>()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .cookieJar(object : CookieJar {
            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> =
                cookieStore[url.host] ?: emptyList()
        })
        .build()

    /** Browser-ish headers so passport.bilibili.com doesn't treat us as a bot. */
    private fun Request.Builder.browserHeaders(): Request.Builder =
        this
            .header("User-Agent", UA)
            .header("Referer", "https://passport.bilibili.com/login")
            .header("Accept", "application/json, text/plain, */*")
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header("Origin", "https://passport.bilibili.com")

    /** A generated login session: QR content to render + polling key. */
    data class QrSession(val url: String, val key: String)

    /** Poll outcome. */
    sealed class Result {
        /** Not scanned yet — keep polling. */
        object Pending : Result()

        /** Scanned on the Bilibili app, awaiting confirmation. */
        object Scanned : Result()

        /** QR expired — regenerate. */
        object Expired : Result()

        /** Login OK; [cookie] is the SESSDATA string to save. */
        data class Success(val cookie: String) : Result()
    }

    /** Create a new QR login session. GET is the current Bilibili contract; POST falls back if Bilibili switches back. */
    suspend fun generate(): QrSession = withContext(Dispatchers.IO) {
        try {
            generateOnce("GET")
        } catch (e: RuntimeException) {
            if (e.message?.contains("405") == true) generateOnce("POST") else throw e
        }
    }

    private fun generateOnce(method: String): QrSession {
        val builder = Request.Builder()
            .url("https://passport.bilibili.com/x/passport-login/web/qrcode/generate")
            .browserHeaders()
        val request = if (method == "POST") {
            builder.post(ByteArray(0).toRequestBody()).build()
        } else {
            builder.build()
        }
        return client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("登录接口返回 HTTP ${resp.code}")
            val obj = JsonParser.parseString(resp.body?.string() ?: "").asJsonObject
            val data = obj.getAsJsonObject("data")
                ?: throw RuntimeException("登录接口响应缺少 data")
            QrSession(
                url = data.get("url")?.asString
                    ?: throw RuntimeException("登录接口响应缺少 url"),
                key = data.get("qrcode_key")?.asString
                    ?: throw RuntimeException("登录接口响应缺少 qrcode_key"),
            )
        }
    }

    /** Poll the login state for [key]. Call roughly every 2 seconds. */
    suspend fun poll(key: String): Result = withContext(Dispatchers.IO) {
        val url = "https://passport.bilibili.com/x/passport-login/web/qrcode/poll" +
            "?qrcode_key=" + URLEncoder.encode(key, "UTF-8")
        val req = Request.Builder()
            .url(url)
            .browserHeaders()
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("登录轮询返回 HTTP ${resp.code}")
            val setCookies = resp.headers("Set-Cookie")
            val obj = JsonParser.parseString(resp.body?.string() ?: "").asJsonObject
            when (val code = obj.get("code")?.asInt ?: -1) {
                0 -> {
                    val data = obj.getAsJsonObject("data")
                    val url = data?.get("url")?.asString
                    val urlCookies = url?.let { extractCookies(it) }.orEmpty()
                    val headerCookies = extractCookiesFromHeaders(setCookies)
                    val jsonCookies = extractCookiesFromJson(obj)
                    val cookie = urlCookies.ifBlank { headerCookies }.ifBlank { jsonCookies }
                    if (cookie.isBlank()) {
                        val reason = buildString {
                            append("url=${if (url == null) "缺失" else if (urlCookies.isBlank()) "无Cookie" else "OK"}; ")
                            append("setCookie=${if (setCookies.isEmpty()) "无" else if (headerCookies.isBlank()) "无匹配" else "OK"}; ")
                            append("body=${if (jsonCookies.isBlank()) "无Cookie" else "OK"}")
                        }
                        throw RuntimeException("登录成功但未解析到 Cookie：$reason")
                    }
                    Result.Success(cookie)
                }
                86101 -> Result.Pending
                86090 -> Result.Scanned
                86038 -> Result.Expired
                else -> throw RuntimeException("登录轮询错误（$code）")
            }
        }
    }

    /** Pull SESSDATA/bili_jct/DedeUserID out of the passport redirect URL. */
    private fun extractCookies(redirectUrl: String): String {
        val wanted = setOf("SESSDATA", "bili_jct", "DedeUserID")
        val found = redirectUrl
            .substringAfter('?', "")
            .split('&')
            .mapNotNull { pair ->
                val key = pair.substringBefore('=').trim()
                val raw = pair.substringAfter('=', "").trim()
                if (key in wanted && raw.isNotEmpty()) {
                    val value = runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }
                        .getOrElse { raw }
                    "$key=$value"
                } else null
            }
        return found.joinToString("; ")
    }

    /** Pull the same three cookies out of Set-Cookie response headers. */
    private fun extractCookiesFromHeaders(setCookieHeaders: List<String>): String {
        val wanted = listOf("SESSDATA", "bili_jct", "DedeUserID")
        val found = mutableMapOf<String, String>()
        for (header in setCookieHeaders) {
            val first = header.substringBefore(';').trim()
            val key = first.substringBefore('=').trim()
            val value = first.substringAfter('=', "").trim()
            if (key in wanted && value.isNotEmpty()) found[key] = value
        }
        return wanted.mapNotNull { key -> found[key]?.let { "$key=$it" } }
            .joinToString("; ")
    }

    /** Scan the whole response JSON for the three cookie keys wherever they appear. */
    private fun extractCookiesFromJson(root: JsonElement): String {
        val wanted = listOf("SESSDATA", "bili_jct", "DedeUserID")
        val found = mutableMapOf<String, String>()
        fun walk(element: JsonElement) {
            when (element) {
                is JsonObject -> element.entrySet().forEach { (k, v) ->
                    if (k in wanted && v.isJsonPrimitive && v.asString.isNotEmpty()) {
                        found[k] = v.asString
                    } else {
                        walk(v)
                    }
                }
                is JsonArray -> element.forEach { walk(it) }
                else -> {}
            }
        }
        walk(root)
        return wanted.mapNotNull { key -> found[key]?.let { "$key=$it" } }
            .joinToString("; ")
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36"
    }
}
