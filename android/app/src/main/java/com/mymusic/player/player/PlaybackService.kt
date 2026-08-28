package com.mymusic.player.player

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionToken
import com.mymusic.player.MyMusicApp
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
            put("User-Agent", "Mozilla/5.0 (Linux; Android 13) MyMusic/1.0")
            if (cookie.isNotBlank()) put("Cookie", cookie)
        }
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("MyMusic/1.0 (Android)")
            .setDefaultRequestProperties(defaultHeaders)
            .setAllowCrossProtocolRedirects(true)

        val player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
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
