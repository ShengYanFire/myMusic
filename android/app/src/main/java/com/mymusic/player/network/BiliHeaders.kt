package com.mymusic.player.network

/**
 * The one place every Bilibili-required HTTP header lives. The same pair is
 * needed on four separate paths (API client, lyrics client, media data
 * source, cover-image loader) — keeping it here means Bilibili rotating its
 * requirements is a one-line change.
 */
object BiliHeaders {

    /** Referrer the Bilibili CDN / API requires (anti-hotlinking). */
    const val REFERER = "https://www.bilibili.com/"

    /**
     * Desktop Chrome UA for the API + audio CDN path. Bilibili's third-party
     * CDN nodes (upos-sz-estg*) 403 mobile/custom agents, and the playurl/WBI
     * APIs behave closer to the web when presented as a desktop browser.
     *
     * Keep the Chrome version near current stable: Bilibili's CDN/WAF
     * progressively tightens which agents read as a "real" browser, and an
     * obviously-outdated one (the previous Chrome/132 was ~2 years stale) is a
     * common root cause of HTTP 403 → ExoPlayer errorCode 2004
     * (ERROR_CODE_IO_BAD_HTTP_STATUS) on the audio stream.
     */
    const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    /** UA for cover-image fetches — the image CDN accepts ordinary agents. */
    const val COVER_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 MyMusic/1.0"
}
