package com.mymusic.player.network

import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

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
            .header("User-Agent", UA)
            .header("Referer", "https://passport.bilibili.com/login")
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
            .header("User-Agent", UA)
            .header("Referer", "https://passport.bilibili.com/login")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("登录轮询返回 HTTP ${resp.code}")
            val obj = JsonParser.parseString(resp.body?.string() ?: "").asJsonObject
            when (val code = obj.get("code")?.asInt ?: -1) {
                0 -> {
                    val cookie = obj.getAsJsonObject("data")
                        ?.get("url")?.asString
                        ?.let { extractCookies(it) }
                        .orEmpty()
                    if (cookie.isBlank()) throw RuntimeException("登录成功但未解析到 Cookie")
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
                val value = pair.substringAfter('=', "").trim()
                if (key in wanted && value.isNotEmpty()) "$key=$value" else null
            }
        return found.joinToString("; ")
    }

    companion object {
        private const val UA = "Mozilla/5.0 (Linux; Android 13) MyMusic/1.0"
    }
}
