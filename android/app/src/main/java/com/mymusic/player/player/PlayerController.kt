package com.mymusic.player.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
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
import kotlin.math.abs

data class PlayerUiState(
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
    /** True while auto-falling back to an alternate audio URL after an error. */
    val retrying: Boolean = false,
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

    // Candidate audio URLs per mediaId (bvid), best-first, for automatic
    // fallback when a CDN node refuses the primary URL (e.g. HTTP 403).
    private val pendingUrls = mutableMapOf<String, List<String>>()
    private val pendingIndex = mutableMapOf<String, Int>()
    private val pendingTrack = mutableMapOf<String, Track>()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _sleepRemainingMs = MutableStateFlow<Long?>(null)
    val sleepRemainingMs: StateFlow<Long?> = _sleepRemainingMs.asStateFlow()

    private var sleepDeadlineMs: Long? = null

    /**
     * A seek committed through [seekTo] that the player has not confirmed yet.
     *
     * [MediaController] dispatches seekTo() to the session asynchronously, and
     * its currentPosition keeps returning the OLD position until the session
     * pushes the seek discontinuity back (a binder round trip). Holding the
     * target here lets the position ticker keep the UI at the seek position
     * during that window — otherwise the seek bar visibly rubber-bands back to
     * the pre-seek position before jumping ahead.
     */
    private var pendingSeekMs: Long? = null
    private var pendingSeekSetAtMs = 0L

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.value = _state.value.copy(isPlaying = isPlaying, retrying = false)
        }

        override fun onPlayerError(error: PlaybackException) {
            // The player is in an unknown position now; any optimistic seek
            // preview is meaningless.
            pendingSeekMs = null
            Log.e(
                TAG,
                "播放错误 errorCode=${error.errorCode} " +
                    "message=${error.message} cause=${error.cause}",
            )
            // Retryable = transport-level failure (HTTP 403/404, connection
            // errors). Not retryable = decoding/format problems.
            val retryable = error.errorCode in 2000..2009 ||
                error.cause is HttpDataSource.InvalidResponseCodeException
            val c = controller
            val mediaId = c?.currentMediaItem?.mediaId
            val candidateUrls = mediaId?.let { pendingUrls[it] }.orEmpty()
            val idx = mediaId?.let { pendingIndex[it] } ?: 0

            if (retryable && c != null && mediaId != null &&
                candidateUrls.size > 1 && idx < candidateUrls.lastIndex
            ) {
                val next = idx + 1
                pendingIndex[mediaId] = next
                Log.w(TAG, "音源降级 bvid=$mediaId ${idx + 1}/${candidateUrls.size} -> ${candidateUrls[next]}")
                _state.value = _state.value.copy(error = null, retrying = true)
                val track = pendingTrack[mediaId]
                val newItem = toMediaItem(track, candidateUrls[next])
                val pos = c.currentMediaItemIndex
                c.replaceMediaItem(pos, newItem)
                c.seekTo(pos, 0L)
                c.prepare()
                c.play()
                // The retrying flag is cleared in onMediaItemTransition /
                // onIsPlayingChanged once the fallback actually takes effect.
                return
            }

            // Exhausted all candidates: show the error.
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
            _state.value = _state.value.copy(error = null, retrying = false)
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

        override fun onRepeatModeChanged(repeatMode: Int) {
            // Reflect repeat-mode changes made outside the app UI (notification /
            // Bluetooth / car controllers) so the Now Playing screen stays in sync.
            _repeatMode.value = repeatMode
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
                        positionMs = resolvePositionMs(c),
                    )
                }
            }
        }
    }

    fun playQueue(tracks: List<Track>, startIndex: Int = 0) {
        val c = controller
        if (c == null) {
            pendingQueue = tracks to startIndex
            return
        }
        // Drop tracks without a usable URL (e.g. library entries whose audio
        // stream could not be refreshed) so ExoPlayer never gets an empty URI.
        val playable = tracks.mapIndexedNotNull { i, t ->
            val urls = if (t.audioUrls.isNotEmpty()) t.audioUrls
            else listOfNotNull(t.audioUrl)
            if (urls.isEmpty()) null else i to (t to urls)
        }
        if (playable.isEmpty()) {
            _state.value = _state.value.copy(
                current = null,
                isPlaying = false,
                positionMs = 0,
                durationMs = 0,
                error = "所选内容没有可播放的音源",
            )
            return
        }
        // Register per-track candidate URL lists (best-first) for fallback.
        pendingUrls.clear()
        pendingIndex.clear()
        pendingTrack.clear()
        playable.forEach { (_, pair) ->
            val t = pair.first
            pendingUrls[t.bvid] = pair.second
            pendingIndex[t.bvid] = 0
            pendingTrack[t.bvid] = t
        }
        val items = playable.map { (_, pair) -> toMediaItem(pair.first, pair.second.first()) }
        // Map the requested start index onto the filtered list: prefer the first
        // playable track at or after startIndex, falling back to the last one.
        val clampedStart = startIndex.coerceIn(0, tracks.lastIndex.coerceAtLeast(0))
        val startItem = playable.firstOrNull { it.first >= clampedStart } ?: playable.last()
        val startPos = playable.indexOf(startItem).coerceAtLeast(0)
        val startTrack = playable[startPos].second.first
        Log.d(TAG, "playQueue bvid=${startTrack.bvid} url=${startTrack.audioUrl}")
        c.setMediaItems(items, startPos, 0L)
        c.prepare()
        c.play()
        _state.value = PlayerUiState(
            current = startTrack,
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

    /**
     * 下一首：explicitly seek to the adjacent media item.
     *
     * Never `seekToNextMediaItem()`: under REPEAT_MODE_ALL that call maps the
     * "next" of the last (and, with a one-song queue, only) item back onto the
     * SAME item, so ExoPlayer restarts the current song instead of skipping —
     * exactly the bug where 下一首 replayed the song from its beginning.
     */
    fun playNext() {
        val c = controller ?: return
        val count = c.mediaItemCount
        if (count == 0) return
        val cur = c.currentMediaItemIndex
        val next = cur + 1
        when {
            // A real next song exists: skip to it.
            next < count -> c.seekTo(next, C.TIME_UNSET)
            // 列表循环: wrap around to the first song — never onto the current one.
            c.repeatMode == Player.REPEAT_MODE_ALL && count > 1 -> c.seekTo(0, C.TIME_UNSET)
            // Repeat off / 单曲循环 at the end of the queue: nothing to skip to.
            // (Do NOT restart the current song — that is the reported bug.)
        }
    }

    /**
     * 上一首: restart the current song once it is a few seconds in (standard
     * player behavior; media3 itself uses a 3 s threshold), otherwise jump to
     * the previous item — wrapping to the last song under 列表循环.
     */
    fun playPrevious() {
        val c = controller ?: return
        val count = c.mediaItemCount
        if (count == 0) return
        val cur = c.currentMediaItemIndex
        // Half the (known) duration for very short tracks so 上一首 still works.
        val restartThresholdMs = if (c.duration > 0) minOf(3_000L, c.duration / 2) else 3_000L
        when {
            c.currentPosition > restartThresholdMs -> c.seekTo(cur, 0L)
            cur > 0 -> c.seekTo(cur - 1, C.TIME_UNSET)
            c.repeatMode == Player.REPEAT_MODE_ALL && count > 1 -> c.seekTo(count - 1, C.TIME_UNSET)
            // First song of the queue with repeat off / one: restart it.
            else -> c.seekTo(cur, 0L)
        }
    }

    /**
     * Extend the queue around the currently playing track ([pivotBvid]):
     * [before] is inserted ahead of the pivot, [after] at the queue end, both
     * keeping the caller's list order. Tracks already queued (by bvid) or
     * without a usable URL are skipped. No-op when the pivot is no longer in
     * the queue — a newer [playQueue] replaced the list and these results are
     * stale.
     */
    fun insertQueue(before: List<Track>, after: List<Track>, pivotBvid: String) {
        val c = controller ?: return
        if (before.isEmpty() && after.isEmpty()) return
        // media3 1.4 has no getMediaItems(): walk the timeline by index.
        var pivot = -1
        val existing = HashSet<String>(c.mediaItemCount)
        for (j in 0 until c.mediaItemCount) {
            val id = c.getMediaItemAt(j).mediaId
            if (id == pivotBvid) pivot = j
            existing.add(id)
        }
        if (pivot < 0) return

        // Register candidate URL lists (best-first) for the error fallback,
        // mirroring playQueue, and build the media items (skipping dupes).
        fun itemFor(t: Track): MediaItem? {
            val urls = if (t.audioUrls.isNotEmpty()) t.audioUrls else listOfNotNull(t.audioUrl)
            if (urls.isEmpty() || !existing.add(t.bvid)) return null
            pendingUrls[t.bvid] = urls
            pendingIndex[t.bvid] = 0
            pendingTrack[t.bvid] = t
            return toMediaItem(t, urls.first())
        }

        val beforeItems = before.mapNotNull(::itemFor)
        if (beforeItems.isNotEmpty()) c.addMediaItems(pivot, beforeItems)
        val afterItems = after.mapNotNull(::itemFor)
        if (afterItems.isNotEmpty()) c.addMediaItems(afterItems)
    }

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        val pos = positionMs.coerceAtLeast(0)
        c.seekTo(pos)
        // Publish the seek target immediately (the UI must not wait for the
        // async round trip) and remember it so the 500 ms ticker suppresses
        // the stale pre-seek position until the player confirms the seek
        // (via onPositionDiscontinuity → syncFromPlayer).
        pendingSeekMs = pos
        pendingSeekSetAtMs = SystemClock.elapsedRealtime()
        _state.value = _state.value.copy(positionMs = pos)
    }

    /**
     * Position to publish on a ticker tick while a committed seek may still
     * be in flight: hold the seek target until the player's position reaches
     * it (normally [syncFromPlayer] confirms it first on the discontinuity),
     * with a safety timeout so a seek that never lands cannot pin the bar.
     */
    private fun resolvePositionMs(c: MediaController): Long {
        val pos = c.currentPosition.coerceAtLeast(0)
        val pending = pendingSeekMs ?: return pos
        val age = SystemClock.elapsedRealtime() - pendingSeekSetAtMs
        return when {
            // The player reached the seek target → confirmed; resume tracking.
            // (Skip the first tick: right after a seek the controller still
            // reports the old position, which can be arbitrarily close to the
            // target for short seeks and would falsely clear the preview.)
            age > 600 && abs(pos - pending) < 750 -> {
                pendingSeekMs = null
                pos
            }
            // Safety net: drop a preview that never got confirmed.
            age > 5_000 -> {
                pendingSeekMs = null
                pos
            }
            // Stale pre-seek position → keep showing the committed target.
            else -> pending
        }
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

    private fun toMediaItem(track: Track?, url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaId(track?.bvid ?: url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track?.title ?: "")
                    .setArtist(track?.author)
                    .setArtworkUri(track?.cover?.let { Uri.parse(it) })
                    .build(),
            )
            .build()

    private fun syncFromPlayer() {
        val c = controller ?: return
        val item = c.currentMediaItem ?: return
        // A player-reported transition/discontinuity is authoritative: any
        // optimistic seek preview is confirmed (or superseded) by it.
        pendingSeekMs = null
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
