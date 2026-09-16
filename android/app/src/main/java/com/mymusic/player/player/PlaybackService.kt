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
import com.mymusic.player.network.BiliHeaders
import com.mymusic.player.ui.MainActivity

/**
 * Media3 session service: keeps playing in the background, shows a media
 * notification, and enables lock-screen / Bluetooth controls.
 */
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()

        // Bilibili audio CDN requires Referer / User-Agent headers and often
        // redirects to mirror nodes across protocols — so allow those.
        //
        // No Cookie here, deliberately:
        //  - the audio QUALITY is decided by the playurl API call (which does
        //    carry the login cookie); the CDN itself only needs Referer+UA;
        //  - keeping the session token off the media path means it can never
        //    leak onto a cleartext CDN URL or cross-protocol redirect, and a
        //    re-login needs no service restart for playback headers to match.
        val defaultHeaders = buildMap {
            put("Referer", BiliHeaders.REFERER)
            put("User-Agent", BiliHeaders.DESKTOP_UA)
        }
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
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

        // Route notification / lock-screen / Bluetooth skip actions through the
        // app's own queue logic (shuffle + on-demand resolution) instead of the
        // player's native linear timeline navigation.
        val sessionPlayer = SessionNavigationPlayer(player)

        val sessionIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        mediaSession = MediaSession.Builder(this, sessionPlayer)
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
