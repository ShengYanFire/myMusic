package com.mymusic.player.network

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Executes [this] call on OkHttp's dispatcher and suspends until the response
 * arrives — and, unlike [OkHttpClient.newCall] + [Call.execute], actually
 * CANCELS the in-flight request when the awaiting coroutine is cancelled.
 * [Call.execute] would otherwise keep a cancelled search/lyrics request
 * running to completion (bounded only by the 30 s read timeout).
 */
suspend fun OkHttpClient.awaitCall(request: Request): Response =
    suspendCancellableCoroutine { cont ->
        val call = newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response)
            }
        })
    }
