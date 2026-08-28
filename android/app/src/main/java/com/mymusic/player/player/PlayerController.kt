package com.mymusic.player.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.util.Log
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.MoreExecutors
import com.mymusic.player.MyMusicApp
import com.mymusic.player.domain.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class PlayerUiState(
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

/**
 * Singleton bridge between the Compose UI and the MediaController bound to
 * [PlaybackService]. Exposes playback state as a [StateFlow].
 */
object PlayerController {

    private const val TAG = "MyMusicPlayer"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var appContext: Context? = null
    private var controller: MediaController? = null
    private var pendingQueue: Pair<List<Track>, Int>? = null

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _sleepRemainingMs = MutableStateFlow<Long?>(null)
    val sleepRemainingMs: StateFlow<Long?> = _sleepRemainingMs.asStateFlow()

    private var sleepDeadlineMs: Long? = null

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying)
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(
                TAG,
                "播放错误 errorCode=${error.errorCode} " +
                    "message=${error.message} cause=${error.cause}",
            )
            val causeMsg = error.cause?.message ?: error.message ?: "未知错误"
            val failedUrl = (error.cause as? HttpDataSource.InvalidResponseCodeException)
                ?.dataSpec?.uri?.toString()
            _state.value = _state.value.copy(
                error = "播放失败（错误码 ${error.errorCode}）：$causeMsg" +
                    (failedUrl?.let { "\n$it" } ?: ""),
                isPlaying = false,
            )
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _state.value = _state.value.copy(error = null)
            syncFromPlayer()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            _state.value = _state.value.copy(
                durationMs = controller?.duration?.coerceAtLeast(0) ?: 0,
            )
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            syncFromPlayer()
        }
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        val appCtx = appContext!!
        val future = MediaController.Builder(appCtx, PlaybackService.sessionToken(appCtx))
            .buildAsync()
        future.addListener({
            val c = runCatching { future.get() }.getOrNull()
            if (c == null) {
                // Session service unavailable; playback controls become no-ops.
                return@addListener
            }
            controller = c
            c.addListener(listener)
            syncFromPlayer()
            // Restore the persisted repeat mode on the (re)created player.
            scope.launch {
                val saved = MyMusicApp.instance.settings.repeatMode.first()
                c.repeatMode = saved
                _repeatMode.value = saved
            }
            pendingQueue?.let { (tracks, index) ->
                pendingQueue = null
                playQueue(tracks, index)
            }
        }, MoreExecutors.directExecutor())

        // Position ticker for the seek bar (only while something is loaded).
        scope.launch {
            while (true) {
                delay(500)
                val c = controller ?: continue
                // Sleep timer countdown / expiry.
                val deadline = sleepDeadlineMs
                if (deadline != null) {
                    val remaining = deadline - System.currentTimeMillis()
                    _sleepRemainingMs.value = remaining.coerceAtLeast(0)
                    if (remaining <= 0) {
                        sleepDeadlineMs = null
                        _sleepRemainingMs.value = null
                        stopAndExit()
                    }
                }
                if (_state.value.current != null) {
                    _state.value = _state.value.copy(
                        positionMs = c.currentPosition.coerceAtLeast(0),
                    )
                }
            }
        }
    }

    fun playTrack(track: Track) {
        playQueue(listOf(track))
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        val c = controller
        if (c == null) {
            pendingQueue = tracks to startIndex
            return
        }
        val items = tracks.map { toMediaItem(it) }
        val start = tracks.getOrNull(startIndex)
        Log.d(TAG, "playQueue bvid=${start?.bvid} url=${start?.audioUrl}")
        c.setMediaItems(items, startIndex.coerceIn(0, items.lastIndex.coerceAtLeast(0)), 0L)
        c.prepare()
        c.play()
        _state.value = PlayerUiState(
            current = tracks.getOrNull(startIndex),
            isPlaying = true,
            positionMs = 0,
            durationMs = 0,
            error = null,
        )
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun playNext() {
        controller?.seekToNextMediaItem()
    }

    fun playPrevious() {
        controller?.seekToPreviousMediaItem()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs.coerceAtLeast(0))
    }

    /** Cycle repeat mode: off -> one -> all -> off, and persist it. */
    fun cycleRepeatMode() {
        val c = controller ?: return
        val next = (c.repeatMode + 1) % 3
        c.repeatMode = next
        _repeatMode.value = next
        scope.launch { MyMusicApp.instance.settings.setRepeatMode(next) }
    }

    /** Set a sleep timer in minutes (<=0 cancels). When it fires, playback stops and the app exits. */
    fun setSleepTimer(minutes: Int) {
        if (minutes <= 0) {
            sleepDeadlineMs = null
            _sleepRemainingMs.value = null
        } else {
            sleepDeadlineMs = System.currentTimeMillis() + minutes * 60_000L
        }
    }

    /** Stop playback, stop the background service, then leave the app. */
    private fun stopAndExit() {
        val c = controller
        c?.pause()
        c?.stop()
        appContext?.let { ctx ->
            runCatching {
                ctx.stopService(Intent(ctx, PlaybackService::class.java))
            }
        }
        _state.value = _state.value.copy(isPlaying = false, error = null)
        scope.launch {
            delay(400)
            Process.killProcess(Process.myPid())
        }
    }

    private fun toMediaItem(track: Track): MediaItem =
        MediaItem.Builder()
            .setUri(track.audioUrl ?: "")
            .setMediaId(track.bvid)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.author)
                    .setArtworkUri(track.cover?.let { Uri.parse(it) })
                    .build(),
            )
            .build()

    private fun syncFromPlayer() {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        val meta = item.mediaMetadata
        val track = Track(
            bvid = item.mediaId,
            title = meta.title?.toString() ?: "",
            author = meta.artist?.toString(),
            cover = meta.artworkUri?.toString(),
        )
        _state.value = _state.value.copy(
            current = track,
            isPlaying = c.isPlaying,
            positionMs = c.currentPosition.coerceAtLeast(0),
            durationMs = c.duration.coerceAtLeast(0),
        )
    }
}
