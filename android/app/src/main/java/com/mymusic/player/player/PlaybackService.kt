package com.mymusic.player.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.mymusic.player.MyMusicApp
import com.mymusic.player.network.BiliDirectClient
import com.mymusic.player.ui.MainActivity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

/**
 * Media3 session service: keeps playing in the background, shows a media
 * notification, and enables lock-screen / Bluetooth controls.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // Bilibili audio CDN requires Referer / User-Agent headers, may need the
        // login cookie for higher-quality streams, and often redirects to mirror
        // nodes across protocols — so allow those.
        val cookie = runCatching {
            runBlocking { MyMusicApp.instance.settings.cookie.first() }
        }.getOrDefault("")

        val defaultHeaders = buildMap {
            put("Referer", "https://www.bilibili.com/")
            // Bilibili's third-party CDN nodes (upos-sz-estg*) reject mobile /
            // custom User-Agents with HTTP 403 and only serve desktop browser
            // agents, so send a desktop Chrome UA for audio requests
            // (same constant the API client signs with).
            put("User-Agent", BiliDirectClient.UA)
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(BiliDirectClient.UA)
            .setDefaultRequestProperties(defaultHeaders)
            .setAllowCrossProtocolRedirects(true)

        // Snappy seeking: resume playback after only 0.5 s / 1 s of buffered
        // audio (defaults are 2.5 s / 5 s — a seek audibly "pauses" while
        // that much media is fetched), and keep 30 s of back buffer so short
        // backward seeks are served instantly without hitting the network.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 15_000,
                /* maxBufferMs = */ 50_000,
                /* bufferForPlaybackMs = */ 500,
                /* bufferForPlaybackAfterRebufferMs = */ 1_000,
            )
            .setBackBuffer(
                /* backBufferDurationMs = */ 30_000,
                /* retainBackBufferFromKeyframe = */ true,
            )
            .build()

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .setLoadControl(loadControl)
            .setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true)
            .build()

        val sessionIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionIntent)
            .build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null ||
            !player.playWhenReady ||
            player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    companion object {
        fun sessionToken(context: Context): SessionToken =
            SessionToken(context, ComponentName(context, PlaybackService::class.java))
    }
}
