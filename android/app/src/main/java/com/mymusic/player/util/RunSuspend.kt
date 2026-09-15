package com.mymusic.player.util

import kotlinx.coroutines.CancellationException

/**
 * `runCatching` for suspend code: identical behavior, EXCEPT it rethrows
 * [CancellationException] instead of swallowing it.
 *
 * `runCatching` catches Throwable — including the cancellation a coroutine
 * scope uses to unwind — so wrapping a suspend call in it breaks structured
 * concurrency: a cancelled search reports a bogus "搜索失败", a cancelled
 * ping reports "无法连接 B 站", and a cancelled lyrics fetch happily fires
 * its LRCLIB fallback request anyway. Every suspend gateway in this app
 * that wants "failure → fallback/empty" semantics uses this helper.
 */
suspend inline fun <T> runSuspendCatching(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c
    } catch (t: Throwable) {
        Result.failure(t)
    }
