package com.mymusic.player.network

import java.util.Locale
import java.util.UUID

/**
 * Pure helpers for B站 identity cookies: the anonymous device fingerprint
 * (buvid3) and the whitelist of stable login cookies carried on api requests.
 * Every function here is pure, so it unit-tests without Android.
 *
 * Bilibili's risk control tracks *who* (login identity) is calling from *what
 * device* (buvid3). An app that presents no stable fingerprint looks like a
 * fresh, untrusted client on every launch — which is exactly what trips the
 * `-412` mass failures. Keeping one per-install [generateBuvid3] across
 * launches, logins and logouts, plus the complete [loginIdentityCookies], is
 * what makes the client look like a normal browser and lifts the threshold.
 */
object BiliIdentity {

    /** The buvid3 cookie name (B站's device identifier). */
    const val BUVID3 = "buvid3"

    /**
     * Stable login cookies kept from the WebView jar. Volatile session /
     * device cookies (buvid4, b_nut, b_lsid, bili_ticket, _uuid, sid_*, …)
     * are deliberately dropped — they expire or rotate, and send a stale id
     * that no longer matches the session. The app owns its fingerprint via
     * [BUVID3], so the WebView's own buvid3 is not carried forward either.
     */
    private val LOGIN_COOKIES = setOf(
        "sessdata",
        "bili_jct",
        "dedeuserid",
        "dedeuserid__ckmd5",
    )

    /** 32 uppercase hex + "infoc" — the widely accepted client-generated form. */
    fun generateBuvid3(): String =
        UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT) + "infoc"

    /**
     * Reduce a raw `CookieManager.getCookie` jar to the stable login identity,
     * as a `"k=v; k=v"` string (empty fragments and unknown keys are dropped).
     */
    fun loginIdentityCookies(rawJar: String): String = rawJar
        .split(";")
        .map { it.trim() }
        .filter { part ->
            val key = part.substringBefore('=', "").trim().lowercase(Locale.ROOT)
            key in LOGIN_COOKIES
        }
        .joinToString("; ")

    /**
     * The final Cookie header for api.bilibili.com: our own [buvid3] first,
     * then the login cookies — dropping any buvid3 the login string may still
     * carry, so the per-install fingerprint is the sole identifier regardless
     * of what a pasted/legacy cookie contained.
     */
    fun buildApiCookie(buvid3: String, loginCookies: String): String = buildList {
        if (buvid3.isNotBlank()) add("$BUVID3=$buvid3")
        loginCookies.split(";").forEach { raw ->
            val part = raw.trim()
            if (part.isEmpty()) return@forEach
            val key = part.substringBefore('=', "").trim()
            if (key.equals(BUVID3, ignoreCase = true)) return@forEach
            // Stay in lock-step with loginIdentityCookies: only known login keys
            // reach the wire, so a legacy/pasted cookie carrying extra keys can't
            // leak them onto api.bilibili.com.
            if (key.lowercase(Locale.ROOT) !in LOGIN_COOKIES) return@forEach
            add(part)
        }
    }.joinToString("; ")
}