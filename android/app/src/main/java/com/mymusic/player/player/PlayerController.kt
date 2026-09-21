package com.mymusic.player.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.HttpDataSource
import androidx.media3.session.MediaController
import com.google.common.util.concurrent.MoreExecutors
import com.mymusic.player.MyMusicApp
import com.mymusic.player.data.SavedPlaybackState
import com.mymusic.player.domain.Track
import com.mymusic.player.network.AudioUrlExpiry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Player state for the UI. Deliberately EXCLUDES the playback position: a
 * 2 Hz position tick must not re-emit this object (it feeds whole screens via
 * collectAsState) — the position lives in its own [PlayerController.positionMs]
 * flow that only progress-bearing leaves collect. Annotated [Immutable] so
 * Compose can skip composables holding it.
 */
@Immutable
data class PlayerUiState(
    val current: Track? = null,
    /**
     * The whole play queue behind [current], in order — including entries
     * whose audio URL has not been resolved yet (they join the player itself
     * only as the background resolution fills it in). Drives the
     * 正在播放列表 sheet on the Now Playing screen.
     */
    val queue: List<Track> = emptyList(),
    /** Index of [current] inside [queue]; -1 when it is not (yet) in it. */
    val queueIndex: Int = -1,
    val isPlaying: Boolean = false,
    val durationMs: Long = 0,
    val error: String? = null,
    /** True while auto-falling back to an alternate audio URL after an error. */
    val retrying: Boolean = false,
    /**
     * uid of the queue entry a 下一首 tap requested while it was not yet in
     * the player (still being resolved in the background, or its earlier
     * resolve failed and the fill skipped it). Drives the "正在解析下一首…"
     * hint; cleared once it actually starts playing or resolution fails.
     */
    val loadingNextUid: String? = null,
    /**
     * True while the player is buffering the current item and wants to play
     * (ExoPlayer STATE_BUFFERING + playWhenReady) — the visible "缓冲中…"
     * phase between a seek/track switch and audio actually starting.
     */
    val buffering: Boolean = false,
)

/**
 * Singleton bridge between the Compose UI and the MediaController bound to
 * [PlaybackService]. Exposes playback state as a [StateFlow].
 */
object PlayerController {

    private const val TAG = "MyMusicPlayer"

    /** Debounce window before a progress change is flushed to DataStore. */
    private const val PROGRESS_SAVE_DEBOUNCE_MS = 600L

    /** While playing, persist the position at most this often. */
    private const val PROGRESS_PERIODIC_SAVE_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var appContext: Context? = null
    private var controller: MediaController? = null
    private var pendingQueue: QueueRequest? = null

    /** A queued [playQueue] that arrived before the controller connected. */
    private data class QueueRequest(
        val tracks: List<Track>,
        val startIndex: Int,
        val startPositionMs: Long,
    )

    // ---- Progress persistence (播单记忆上次进度) ----
    /** In-flight debounced save; cancelled and restarted on every change. */
    private var progressSaveJob: Job? = null
    private var lastProgressSaveAtMs = 0L

    /**
     * Emitted when 下一首 is requested but the next queue entry is not in the
     * player timeline yet — the background fill is still resolving it, or its
     * earlier resolution failed and the fill skipped it. The UI layer
     * (MainViewModel) collects this flow, resolves the entry on demand and
     * feeds it back through [insertNextOnDemand] so the skip lands instead of
     * silently no-oping.
     *
     * A flow (instead of a var callback) so subscribers come and go with the
     * ViewModel lifecycle — no stale ViewModel reference can stay hooked into
     * this singleton, and a dead scope simply stops receiving.
     */
    private val _needResolveNext = MutableSharedFlow<Track>(extraBufferCapacity = 8)
    val needResolveNext: SharedFlow<Track> = _needResolveNext.asSharedFlow()

    /**
     * Emitted when play is tapped on a session whose visible current track
     * cannot play yet — its timeline is still empty (restored from the last
     * session but not re-resolved). The UI layer re-resolves the queue through
     * the coordinator and resumes from the remembered position, mirroring how
     * [needResolveNext] offloads on-demand resolution.
     */
    private val _needResume = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val needResume: SharedFlow<Unit> = _needResume.asSharedFlow()

    /**
     * Playback position, ~2 Hz while playing (a Long StateFlow — equal values
     * are conflated, so a paused player emits nothing). Collected ONLY by
     * progress-bearing leaves (seek bar, lyrics highlight, mini progress bar);
     * screens that just need isPlaying/current must NOT collect this.
     */
    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    // Candidate audio URLs per mediaId (bvid), best-first, for automatic
    // fallback when a CDN node refuses the primary URL (e.g. HTTP 403).
    private val pendingUrls = mutableMapOf<String, List<String>>()
    private val pendingIndex = mutableMapOf<String, Int>()
    private val pendingTrack = mutableMapOf<String, Track>()

    /**
     * Media ids that already got a fresh-URL re-resolution in the current
     * queue episode, so an error never loops re-resolving the same track.
     * Cleared whenever [playQueue] starts a new queue.
     */
    private val freshResolved = mutableSetOf<String>()

    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _repeatMode = MutableStateFlow(Player.REPEAT_MODE_OFF)
    val repeatMode: StateFlow<Int> = _repeatMode.asStateFlow()

    private val _shuffleEnabled = MutableStateFlow(false)
    val shuffleEnabled: StateFlow<Boolean> = _shuffleEnabled.asStateFlow()

    /**
     * Shuffle round over the LOGICAL queue (indices into [PlayerUiState.queue],
     * which is stable for the lifetime of a [playQueue] session — the background
     * fill only resolves entries in place, never reorders or resizes it).
     *
     * The two deques form one ordered walk: `shuffleHistory (old → new) ‖ current
     * ‖ shuffleUpcoming (front = next)`. 下一首 pops from the front of upcoming
     * and pushes the current index onto the back of history; 上一首 pops the back
     * of history and pushes the current index onto the FRONT of upcoming — the
     * symmetric undo, so forward/back never lose a song. A fresh round is built
     * when the queue starts (or shuffle is turned on) and reshuffled from scratch
     * once exhausted under 列表循环.
     */
    private val shuffleUpcoming = ArrayDeque<Int>()
    private val shuffleHistory = ArrayDeque<Int>()
    /**
     * The queue index the shuffle walk currently treats as "current". Updated
     * only by the walk itself (draw / undo) and by (re)seeding; compared
     * against [PlayerUiState.queueIndex] in [reconcileShuffleAfterTransition]
     * to detect external jumps (queue-list tap, error skip) that must rebuild
     * the round. -1 = no round built yet.
     */
    private var shuffleAnchorIndex = -1

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
            if (!isPlaying) scheduleProgressSave()
        }

        override fun onPlayerError(error: PlaybackException) {
            // The player is in an unknown position now; any optimistic seek
            // preview is meaningless.
            pendingSeekMs = null
            _state.value = _state.value.copy(loadingNextUid = null, buffering = false)
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

            // 1) Cheap local fallback: rotate among the stored mirror URLs.
            //    This can only rescue a SINGLE dead CDN node. When the URL that
            //    just failed is already past its `deadline`, every mirror in the
            //    same playurl response is past it too, so rotation is pure waste
            //    — jump straight to re-resolution (step 2) instead.
            val deadlineExpired = AudioUrlExpiry.isExpired(candidateUrls.getOrNull(idx))
            if (retryable && c != null && mediaId != null && !deadlineExpired &&
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

            // 2) Stale URL — the classic cause of "everything fails after the
            //    app sat in the background": Bilibili direct-stream URLs carry
            //    a deadline and expire, and every stored candidate shares the
            //    same expiry, so rotating mirrors (step 1) can never help.
            //    Re-ask the API for a brand-new URL (once per track per queue).
            val track = mediaId?.let { pendingTrack[it] }
                ?: _state.value.queue.firstOrNull { it.uid == mediaId }
            if (retryable && c != null && mediaId != null &&
                track != null && mediaId !in freshResolved
            ) {
                freshResolved.add(mediaId)
                _state.value = _state.value.copy(error = null, retrying = true)
                scope.launch {
                    val freshResult = resolveFresh(track)
                    if (controller !== c) return@launch // a newer controller took over
                    val fresh = freshResult.getOrNull()
                    if (fresh == null || !fresh.hasAudio) {
                        // Re-resolution failed too — give up on this track.
                        val reason = freshResult.exceptionOrNull()?.message
                        _state.value = _state.value.copy(
                            error = if (reason == null) "播放失败，重新解析音源也失败"
                                else "播放失败，重新解析音源也失败：$reason",
                            retrying = false,
                            isPlaying = false,
                        )
                        skipAfterFailure(c)
                        return@launch
                    }
                    pendingUrls[mediaId] = fresh.usableAudioUrls
                    pendingIndex[mediaId] = 0
                    pendingTrack[mediaId] = fresh
                    // Reflect the fresh URL back onto the visible queue so a
                    // later replay/skip of this entry uses the new link instead
                    // of the expired one still cached in PlayerUiState.
                    mergeResolvedIntoQueue(fresh)
                    val pos = c.currentMediaItemIndex
                    // The queue may have grown while resolving; never replace
                    // anything but the still-failed item.
                    if (pos >= 0 && pos < c.mediaItemCount &&
                        c.getMediaItemAt(pos).mediaId != mediaId
                    ) {
                        return@launch
                    }
                    Log.w(TAG, "音源已重新解析 bvid=$mediaId")
                    c.replaceMediaItem(pos, toMediaItem(fresh, fresh.usableAudioUrls.first()))
                    c.seekTo(pos, 0L)
                    c.prepare()
                    c.play()
                }
                return
            }

            // 3) Exhausted every candidate: report the error, then skip over
            //    the dead track so the queue keeps flowing in the background
            //    instead of wedging the player in STATE_IDLE.
            val causeMsg = error.cause?.message ?: error.message ?: "未知错误"
            val failedUrl = (error.cause as? HttpDataSource.InvalidResponseCodeException)
                ?.dataSpec?.uri?.toString()
            _state.value = _state.value.copy(
                error = "播放失败（错误码 ${error.errorCode}）：$causeMsg" +
                    (failedUrl?.let { "\n$it" } ?: ""),
                isPlaying = false,
            )
            c?.let(::skipAfterFailure)
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            _state.value = _state.value.copy(
                error = null,
                retrying = false,
                loadingNextUid = null,
            )
            syncFromPlayer()
            reconcileShuffleAfterTransition(reason)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            val c = controller
            _state.value = _state.value.copy(
                durationMs = c?.duration?.coerceAtLeast(0) ?: 0,
                // Real buffering feedback: the seek/track switch happened and
                // the player wants to play but the stream is still loading —
                // previously this phase was indistinguishable from "paused".
                buffering = playbackState == Player.STATE_BUFFERING &&
                    c?.playWhenReady == true,
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
            // Restore the persisted repeat mode + shuffle toggle on the
            // (re)created player; a shuffle round is (re)built lazily from the
            // current queue on first use if none exists yet.
            scope.launch {
                val saved = MyMusicApp.instance.settings.repeatMode.first()
                c.repeatMode = saved
                _repeatMode.value = saved
                _shuffleEnabled.value = MyMusicApp.instance.settings.shuffleEnabled.first()
            }
            pendingQueue?.let { q ->
                pendingQueue = null
                playQueue(q.tracks, q.startIndex, q.startPositionMs)
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
                // Only tick the position while actually playing (or a seek
                // preview is in flight): a paused player emits nothing at all.
                if (_state.value.current != null && (c.isPlaying || pendingSeekMs != null)) {
                    _positionMs.value = resolvePositionMs(c)
                }
                // Remember playback progress while playing, throttled so a long
                // session never leaves a stale position after an abrupt kill.
                if (_state.value.current != null && c.isPlaying) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastProgressSaveAtMs >= PROGRESS_PERIODIC_SAVE_MS) {
                        lastProgressSaveAtMs = now
                        scheduleProgressSave()
                    }
                }
            }
        }
    }

    /**
     * Restore the last session's play queue + current song + position (播单记忆
     * 上次进度), shown paused. The player timeline stays empty — the audio URLs
     * were deliberately not persisted (they expire) — so the next play tap
     * routes through [needResume] to re-resolve and continue from [positionMs].
     * No-op once something is already playing.
     */
    fun restoreProgress(saved: SavedPlaybackState) {
        val queue = saved.queue
        if (queue.isEmpty() || _state.value.current != null) return
        val index = queue.indexOfFirst { it.uid == saved.currentUid }
            .takeIf { it >= 0 } ?: 0
        val current = queue[index]
        val durationMs = current.duration.toLong() * 1000L
        _state.value = PlayerUiState(
            current = current,
            queue = queue,
            queueIndex = index,
            isPlaying = false,
            durationMs = durationMs,
            error = null,
        )
        _positionMs.value = saved.positionMs.coerceIn(0L, durationMs.coerceAtLeast(0L))
        Log.d(TAG, "恢复上次播放进度 uid=${current.uid} pos=${_positionMs.value}")
    }

    /** Mark the persisted progress dirty; flush it after a short debounce. */
    private fun scheduleProgressSave() {
        progressSaveJob?.cancel()
        progressSaveJob = scope.launch {
            delay(PROGRESS_SAVE_DEBOUNCE_MS)
            saveProgress()
        }
    }

    /** Persist the current queue + track + position (audio URLs are stripped in
     *  the store). No-op while nothing is loaded. */
    private suspend fun saveProgress() {
        val track = _state.value.current ?: return
        val queue = _state.value.queue
        MyMusicApp.instance.playbackProgress.save(
            queue = queue.ifEmpty { listOf(track) },
            currentUid = track.uid,
            positionMs = _positionMs.value.coerceAtLeast(0L),
        )
    }

    /**
     * Start playing [tracks][startIndex].
     *
     * [tracks] is the LOGICAL queue — the full list the UI shows, including
     * entries whose audio URL has not been resolved yet. ExoPlayer only
     * receives the resolved ones (an unresolved entry has no URI to play);
     * the rest is appended around the start item by [insertQueueResolved] as
     * the background resolution completes, so playback begins as soon as the
     * first track is ready while the queue list is complete from the start.
     */
    fun playQueue(tracks: List<Track>, startIndex: Int = 0, startPositionMs: Long = 0L) {
        val c = controller
        if (c == null) {
            pendingQueue = QueueRequest(tracks, startIndex, startPositionMs)
            return
        }
        // Drop tracks without a usable URL (e.g. not-yet-resolved entries) so
        // ExoPlayer never gets an empty URI.
        val playable = tracks.mapIndexedNotNull { i, t ->
            val urls = t.usableAudioUrls
            if (urls.isEmpty()) null else i to (t to urls)
        }
        if (playable.isEmpty()) {
            _state.value = _state.value.copy(
                current = null,
                queue = tracks,
                queueIndex = -1,
                isPlaying = false,
                durationMs = 0,
                error = "所选内容没有可播放的音源",
            )
            _positionMs.value = 0
            return
        }
        // Register per-track candidate URL lists (best-first) for fallback.
        pendingUrls.clear()
        pendingIndex.clear()
        pendingTrack.clear()
        freshResolved.clear()
        playable.forEach { (_, pair) ->
            val t = pair.first
            pendingUrls[t.uid] = pair.second
            pendingIndex[t.uid] = 0
            pendingTrack[t.uid] = t
        }
        val items = playable.map { (_, pair) -> toMediaItem(pair.first, pair.second.first()) }
        // Map the requested start index onto the filtered list: prefer the first
        // playable track at or after startIndex, falling back to the last one.
        val clampedStart = startIndex.coerceIn(0, tracks.lastIndex.coerceAtLeast(0))
        val startItem = playable.firstOrNull { it.first >= clampedStart } ?: playable.last()
        val startPos = playable.indexOf(startItem).coerceAtLeast(0)
        val startTrack = playable[startPos].second.first
        val resumeAt = startPositionMs.coerceAtLeast(0L)
        Log.d(TAG, "playQueue start=$startPos/${items.size} uid=${startTrack.uid} pos=$resumeAt")
        c.setMediaItems(items, startPos, resumeAt)
        c.prepare()
        c.play()
        _state.value = PlayerUiState(
            current = startTrack,
            queue = tracks,
            queueIndex = tracks.indexOfFirst { it.uid == startTrack.uid }
                .takeIf { it >= 0 } ?: -1,
            isPlaying = true,
            durationMs = 0,
            error = null,
        )
        _positionMs.value = resumeAt
        // A new queue starts a fresh shuffle round (seeded only when on).
        resetShuffleRound()
        scheduleProgressSave()
    }

    fun togglePlayPause() {
        val c = controller ?: return
        if (c.isPlaying) {
            c.pause()
            return
        }
        // Restored-but-unresolved session: a visible current track but an empty
        // player timeline. play() would be a silent no-op, so route the tap to
        // the queue coordinator (via needResume) to re-resolve and resume.
        if (c.mediaItemCount == 0) {
            if (_state.value.current != null) _needResume.tryEmit(Unit)
            return
        }
        // play() is a silent no-op from STATE_IDLE (the state a fatal
        // error leaves the player in) — re-prepare so the play button
        // recovers a failed song instead of doing nothing until the queue
        // is replayed from scratch.
        if (c.playbackState == Player.STATE_IDLE && c.mediaItemCount > 0) c.prepare()
        c.play()
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
        if (_shuffleEnabled.value) {
            playNextShuffled(c)
            return
        }
        val cur = c.currentMediaItemIndex
        val next = cur + 1
        when {
            // A real next song exists AND it is the next logical queue entry:
            // instant skip. (When the fill skipped a dead entry, the timeline's
            // next item is a LATER song — jumping to it would silently pass
            // over the logical next, so that case falls through to the
            // on-demand path below instead.)
            next < count && isNextInTimeline(c) && !isTimelineItemUrlExpired(c, next) ->
                seekAndPlay(c, next)
            // 列表循环: wrap around to the first song — never onto the current one.
            c.repeatMode == Player.REPEAT_MODE_ALL && count > 1 &&
                !isTimelineItemUrlExpired(c, 0) -> seekAndPlay(c, 0)
            // Repeat off / 单曲循环 at the end of the player timeline: the next
            // LOGICAL queue entry is still being resolved in the background (or
            // was skipped by the fill after a failed resolve). Resolve it on
            // demand instead of silently doing nothing — that silent no-op was
            // the "点下一首没反应 / 加载很慢" complaint.
            else -> requestNextOnDemand(c)
        }
    }

    /**
     * True when the media item right after the current one in the player
     * timeline is the next logical queue entry — i.e. no earlier entry was
     * skipped by the fill (its resolve failed), which would otherwise make
     * 下一首 jump silently over a song. When there is nothing after the
     * current entry in the LOGICAL queue, the index check alone is fine.
     */
    private fun isNextInTimeline(c: MediaController): Boolean {
        val q = _state.value.queue
        val qi = _state.value.queueIndex
        if (qi < 0 || qi + 1 >= q.size) return true
        val j = c.currentMediaItemIndex + 1
        return j < c.mediaItemCount && c.getMediaItemAt(j).mediaId == q[qi + 1].uid
    }

    /** True when the media item at [index] carries a direct URL past its deadline. */
    private fun isTimelineItemUrlExpired(c: MediaController, index: Int): Boolean {
        if (index < 0 || index >= c.mediaItemCount) return false
        return isTrackUrlExpired(c.getMediaItemAt(index).mediaId)
    }

    /**
     * True when [uid]'s currently-registered direct URL is provably past its
     * `deadline`. No registered URL (track not yet resolved / inserted) or an
     * unparsable deadline both mean "unknown", deliberately NOT "expired" — the
     * reactive 403 → mirror-rotation / re-resolve path still covers those.
     */
    private fun isTrackUrlExpired(uid: String?): Boolean {
        if (uid == null) return false
        val url = pendingTrack[uid]?.usableAudioUrls?.firstOrNull()
            ?: pendingUrls[uid]?.firstOrNull()
        return AudioUrlExpiry.isExpired(url)
    }

    /**
     * 下一首 to an entry that has not reached the player yet. Computes the
     * linear next entry (wrap under 列表循环) and hands it to [requestResolveOnDemand].
     */
    private fun requestNextOnDemand(c: MediaController) {
        val q = _state.value.queue
        val qi = _state.value.queueIndex
        if (qi < 0 || q.isEmpty()) return
        val next = when {
            qi + 1 < q.size -> q[qi + 1]
            c.repeatMode == Player.REPEAT_MODE_ALL && q.size > 1 -> q[0]
            else -> return
        }
        requestResolveOnDemand(c, next)
    }

    /**
     * Play [target] even though it may not have reached the player timeline yet.
     * If it is already there (the background fill beat us to it) a plain seek —
     * same semantics as the healthy path, a paused player stays paused — is all
     * it takes; otherwise publish a 正在解析 hint and ask the UI layer to
     * resolve-and-insert it on demand. Shared by the linear 下一首 wrap, the
     * shuffle draw, and the shuffle history rollback (上一首 under shuffle).
     */
    private fun requestResolveOnDemand(c: MediaController, target: Track) {
        val currentUid = _state.value.current?.uid
        if (target.uid == currentUid) return
        // Already in the player timeline (the background fill beat us to it):
        // a plain seek is instant, mirroring the healthy-path seekAndPlay — but
        // only when its URL is not deadline-expired. A long-idle session keeps
        // dead URLs in the timeline, and seeking into one would instantly 403;
        // re-resolve on demand instead.
        for (j in 0 until c.mediaItemCount) {
            if (c.getMediaItemAt(j).mediaId == target.uid) {
                if (!isTrackUrlExpired(target.uid)) {
                    seekAndPlay(c, j)
                    return
                }
                break
            }
        }
        // Already being resolved on demand from a previous tap — don't fire a
        // second Bilibili resolution for the same entry.
        if (_state.value.loadingNextUid == target.uid) return
        _state.value = _state.value.copy(loadingNextUid = target.uid)
        if (!_needResolveNext.tryEmit(target)) {
            // Buffer full (a slow subscriber): drop the hint, it would show a
            // "解析中" state nothing is working on otherwise.
            _state.value = _state.value.copy(loadingNextUid = null)
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
        if (_shuffleEnabled.value) {
            playPreviousShuffled(c)
            return
        }
        val cur = c.currentMediaItemIndex
        when {
            c.currentPosition > restartThresholdMs(c) -> seekAndPlay(c, cur, 0L)
            cur > 0 -> seekAndPlay(c, cur - 1)
            c.repeatMode == Player.REPEAT_MODE_ALL && count > 1 -> seekAndPlay(c, count - 1)
            // First song of the queue with repeat off / one: restart it.
            else -> seekAndPlay(c, cur, 0L)
        }
    }

    /**
     * 下一首 under shuffle: draw the next random queue entry from the upcoming
     * deck (reshuffling all-but-current once the round runs dry under 列表循环),
     * then play it — a pure seek when it is already resolved, otherwise the same
     * [requestResolveOnDemand] path (resolve-and-insert) the linear 下一首 uses.
     * Returns true once a next entry was drawn and handed off (false = nothing
     * to play: single-track queue, or the round is exhausted under 顺序播放).
     */
    private fun playNextShuffled(c: MediaController): Boolean {
        val q = _state.value.queue
        if (q.size <= 1) return false
        ensureShuffleSeeded(q)
        val next = drawShuffleNext(c, q) ?: return false
        if (next == shuffleAnchorIndex) return false
        if (shuffleAnchorIndex >= 0) shuffleHistory.addLast(shuffleAnchorIndex)
        shuffleAnchorIndex = next
        requestResolveOnDemand(c, q[next])
        return true
    }

    /**
     * 上一首 under shuffle: both deques mirror the forward walk, so this is the
     * symmetric undo — pop the most recent history entry and put the current
     * song back at the FRONT of the upcoming deck. Standard 上一首 semantics
     * still apply first: a song that has played a few seconds just restarts.
     */
    private fun playPreviousShuffled(c: MediaController) {
        val q = _state.value.queue
        if (q.size <= 1) return
        ensureShuffleSeeded(q)
        // Restart either because the song is a few seconds in, or because the
        // round has no history left behind it (nothing to go back to).
        if (c.currentPosition > restartThresholdMs(c) || shuffleHistory.isEmpty()) {
            seekAndPlay(c, c.currentMediaItemIndex, 0L)
            return
        }
        val prev = shuffleHistory.removeLast()
        if (shuffleAnchorIndex >= 0) shuffleUpcoming.addFirst(shuffleAnchorIndex)
        shuffleAnchorIndex = prev
        requestResolveOnDemand(c, q[prev])
    }

    /** Position past which 上一首 restarts the current song instead of going
     *  back: media3 uses 3 s, halved for very short tracks so it still works. */
    private fun restartThresholdMs(c: MediaController): Long =
        if (c.duration > 0) minOf(3_000L, c.duration / 2) else 3_000L

    /**
     * Seed the shuffle deck once for the current queue when no round exists yet
     * (e.g. a session restored from 播单记忆 on startup, where [playQueue] never
     * ran and thus never built one). No-op once a round is already in motion
     * (anchor set) or the queue has no meaningful current index.
     */
    private fun ensureShuffleSeeded(q: List<Track>) {
        if (shuffleUpcoming.isEmpty() && shuffleHistory.isEmpty() && shuffleAnchorIndex < 0) {
            seedShuffleRound(q, _state.value.queueIndex)
        }
    }

    /**
     * Drop the current shuffle round, then seed a fresh one over the whole
     * queue (every entry except the one playing now) when shuffle is on. Used
     * when a new queue starts ([playQueue]) and whenever the toggle flips.
     */
    private fun resetShuffleRound() {
        if (_shuffleEnabled.value) {
            seedShuffleRound(_state.value.queue, _state.value.queueIndex)
        } else {
            shuffleUpcoming.clear()
            shuffleHistory.clear()
            shuffleAnchorIndex = -1
        }
    }

    /**
     * (Re)build the shuffle round around [aroundIdx]: the deck becomes every
     * queue entry except the one playing now, history is emptied, and the
     * walk's anchor is set to [aroundIdx]. Callers guard on shuffle being on.
     */
    private fun seedShuffleRound(q: List<Track>, aroundIdx: Int) {
        shuffleUpcoming.clear()
        shuffleHistory.clear()
        shuffleAnchorIndex = aroundIdx
        if (aroundIdx >= 0 && q.size > 1) {
            shuffleUpcoming.addAll(q.indices.filter { it != aroundIdx }.shuffled())
        }
    }

    /**
     * Draw the next shuffle index without mutating the walk's history/anchor
     * (the caller commits them). Rewraps into a fresh all-but-current deck once
     * the upcoming deck runs dry under 列表循环; returns null when the round is
     * genuinely exhausted (顺序播放 / 单曲循环).
     */
    private fun drawShuffleNext(c: MediaController, q: List<Track>): Int? {
        var next = shuffleUpcoming.removeFirstOrNull()
        if (next == null && c.repeatMode == Player.REPEAT_MODE_ALL) {
            shuffleUpcoming.addAll(q.indices.filter { it != shuffleAnchorIndex }.shuffled())
            next = shuffleUpcoming.removeFirstOrNull()
        }
        return next
    }

    /**
     * Keep the app-side shuffle walk in step with the player after a media-item
     * transition, so 连播 / 跳歌 all honour the random order:
     *  - AUTO (a song finished and ExoPlayer auto-advanced to the linear next):
     *    redraw so background / lock-screen playback keeps the random order;
     *    when the round is spent under 顺序播放, stop the player instead of
     *    letting it fall through to linear replay of already-heard songs.
     *  - SEEK / other where the player landed on a different logical index than
     *    the walk's anchor (queue-list tap, error skip): rebuild the round
     *    around that new index.
     *  - otherwise (the walk's own seek already committed the anchor): nothing.
     */
    private fun reconcileShuffleAfterTransition(reason: Int) {
        if (!_shuffleEnabled.value) return
        val c = controller ?: return
        if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
            if (!playNextShuffled(c) && c.repeatMode != Player.REPEAT_MODE_ALL) {
                c.pause()
            }
            return
        }
        val newQi = _state.value.queueIndex
        if (newQi >= 0 && newQi != shuffleAnchorIndex) {
            seedShuffleRound(_state.value.queue, newQi)
        }
    }

    /**
     * Insert a freshly resolved [track] into the player timeline at its exact
     * position: after the pivot for the "after" fill, or [beforeOffset] slots
     * before the pivot for the "before" fill (so the before section keeps the
     * caller's order instead of reversing on repeated inserts). Tracks already
     * in the timeline are a no-op success. Returns true when the entry is now
     * in the player.
     */
    fun insertQueueResolved(
        track: Track,
        pivotUid: String,
        before: Boolean,
        beforeOffset: Int = 0,
    ): Boolean {
        val c = controller ?: return false
        val urls = track.usableAudioUrls
        if (urls.isEmpty()) return false
        // While a 下一首 on-demand request owns this entry (loadingNextUid),
        // only THAT path may insert it: the fill and the on-demand handler
        // wake on the SAME shared resolution future, and two adds dispatched
        // before the controller's timeline syncs would double-insert the
        // song. The on-demand path inserts and seeks itself.
        if (track.uid == _state.value.loadingNextUid) return false
        var pivot = -1
        val existing = HashSet<String>(c.mediaItemCount)
        for (j in 0 until c.mediaItemCount) {
            val id = c.getMediaItemAt(j).mediaId
            if (id == pivotUid) pivot = j
            existing.add(id)
        }
        if (pivot < 0) return false
        if (!existing.add(track.uid)) return true // already queued — nothing to do
        // Register candidate URL lists (best-first) for the error fallback,
        // mirroring playQueue.
        pendingUrls[track.uid] = urls
        pendingIndex[track.uid] = 0
        pendingTrack[track.uid] = track
        val item = toMediaItem(track, urls.first())
        if (before) {
            c.addMediaItems(pivot + beforeOffset, listOf(item))
        } else {
            c.addMediaItems(listOf(item))
        }
        // Merge the resolved copy back into the visible queue (fresh URL / cid /
        // duration), mirroring the old insertQueue behaviour — but skip the
        // list rebuild + emission when nothing actually changed (each rebuild
        // is O(queue) and re-notifies every collector).
        mergeResolvedIntoQueue(track)
        return true
    }

    /** O(queue) merge of a resolved track into the visible queue, no-op when
     *  the stored copy is already identical. */
    private fun mergeResolvedIntoQueue(track: Track) {
        val q = _state.value.queue
        val idx = q.indexOfFirst { it.uid == track.uid }
        if (idx < 0 || q[idx] == track) return
        val merged = q.toMutableList()
        merged[idx] = track
        _state.value = _state.value.copy(queue = merged)
    }

    /**
     * 下一首 on-demand: put [track] right after the currently playing item and
     * jump to it, even when the background fill skipped it (its earlier
     * resolve failed) or has not reached it yet.
     *
     * The insert AND the jump are issued as ORDERED session commands
     * (addMediaItems → seekTo → play): MediaController applies state changes
     * in its local cache only asynchronously, after a binder round trip — the
     * controller's own docs already note this for seekTo positions. Scanning
     * the timeline right after the insert (what the old skipTo-based flow
     * did) therefore reads a STALE timeline, misses the new item, and leaves
     * the track inserted but never playing — the "点下一首解析完还卡着" bug.
     */
    fun insertNextOnDemand(track: Track): Boolean {
        val c = controller ?: return false
        val urls = track.usableAudioUrls
        if (urls.isEmpty()) return false
        val cur = c.currentMediaItemIndex
        for (j in 0 until c.mediaItemCount) {
            if (c.getMediaItemAt(j).mediaId == track.uid) {
                // Already in the timeline. If its registered URL is deadline-
                // expired, we just resolved a FRESH one on demand — swap the
                // item's URI first, or the seek replays the dead link and 403s
                // again. Otherwise this is the plain fill-beat-us race: seek.
                if (isTrackUrlExpired(track.uid)) {
                    pendingUrls[track.uid] = urls
                    pendingIndex[track.uid] = 0
                    pendingTrack[track.uid] = track
                    c.replaceMediaItem(j, toMediaItem(track, urls.first()))
                    c.seekTo(j, 0L)
                    c.prepare()
                    c.play()
                    mergeResolvedIntoQueue(track)
                } else {
                    seekAndPlay(c, j)
                    c.play()
                }
                return true
            }
        }
        pendingUrls[track.uid] = urls
        pendingIndex[track.uid] = 0
        pendingTrack[track.uid] = track
        val target = cur + 1
        c.addMediaItems(target, listOf(toMediaItem(track, urls.first())))
        if (c.playbackState == Player.STATE_IDLE) {
            c.seekTo(target, C.TIME_UNSET)
            c.prepare()
        } else {
            c.seekTo(target, C.TIME_UNSET)
        }
        c.play()
        // Merge the resolved copy back into the visible queue.
        mergeResolvedIntoQueue(track)
        return true
    }

    /** Drop the "正在解析下一首…" hint (resolution finished or failed). */
    fun clearLoadingNext() {
        _state.value = _state.value.copy(loadingNextUid = null)
        // The on-demand resolve failed / was abandoned: if the shuffle walk had
        // optimistically advanced toward it, rebuild the round around the song
        // actually playing now — the confirming transition never happened, so
        // without this the walk drifts (phantom history / anchor) after a failed
        // resolution.
        if (_shuffleEnabled.value) {
            seedShuffleRound(_state.value.queue, _state.value.queueIndex)
        }
    }

    /**
     * Whether the LOGICAL queue (or the shuffle round) still has something
     * after / before the current song — used by [SessionNavigationPlayer] to
     * keep the notification / lock-screen 下一首·上一首 enabled even when the
     * player timeline itself has not been filled that far yet.
     */
    fun hasNextInQueue(): Boolean {
        val q = _state.value.queue
        if (q.isEmpty()) return false
        if (_shuffleEnabled.value) {
            return shuffleUpcoming.isNotEmpty() || _repeatMode.value == Player.REPEAT_MODE_ALL
        }
        val qi = _state.value.queueIndex
        return qi >= 0 && qi + 1 < q.size
    }

    fun hasPreviousInQueue(): Boolean {
        val q = _state.value.queue
        if (q.isEmpty()) return false
        if (_shuffleEnabled.value) {
            return shuffleHistory.isNotEmpty()
        }
        val qi = _state.value.queueIndex
        return qi > 0
    }

    /**
     * Jump to the queue entry with [uid]. When that entry is already loaded
     * in the player this is a pure seek — instant, no network. Returns false
     * when the entry is not in the player yet (its audio URL is still being
     * resolved), so the caller can fall back to the resolve-and-play flow.
     */
    fun skipTo(uid: String): Boolean {
        val c = controller ?: return false
        for (j in 0 until c.mediaItemCount) {
            if (c.getMediaItemAt(j).mediaId == uid) {
                if (c.playbackState == Player.STATE_IDLE && c.mediaItemCount > 0) {
                    // Recover from a previous error the same way seekAndPlay does.
                    c.seekTo(j, C.TIME_UNSET)
                    c.prepare()
                    c.play()
                } else {
                    c.seekTo(j, C.TIME_UNSET)
                    c.play()
                }
                return true
            }
        }
        return false
    }

    fun seekTo(positionMs: Long) {
        val c = controller ?: return
        val pos = positionMs.coerceAtLeast(0)
        // A restored-but-unresolved session has no timeline to seek: remember
        // the target as the resume position without arming the seek preview
        // (there is no player to confirm it, so the 5 s preview timeout would
        // otherwise snap the bar back to 0).
        if (c.mediaItemCount == 0 && _state.value.current != null) {
            _positionMs.value = pos
            return
        }
        if (c.playbackState == Player.STATE_IDLE && c.mediaItemCount > 0) c.prepare()
        c.seekTo(pos)
        // Publish the seek target immediately (the UI must not wait for the
        // async round trip) and remember it so the 500 ms ticker suppresses
        // the stale pre-seek position until the player confirms the seek
        // (via onPositionDiscontinuity → syncFromPlayer).
        pendingSeekMs = pos
        pendingSeekSetAtMs = SystemClock.elapsedRealtime()
        _positionMs.value = pos
    }

    // Seek-preview heuristic constants — see [resolvePositionMs].
    /**
     * Position to publish on a ticker tick while a committed seek may still
     * be in flight: hold the seek target until the player's position reaches
     * it (normally [syncFromPlayer] confirms it first on the discontinuity),
     * with a safety timeout so a seek that never lands cannot pin the bar.
     */
    private const val SEEK_CONFIRM_MIN_AGE_MS = 600L
    private const val SEEK_CONFIRM_TOLERANCE_MS = 750L
    private const val SEEK_PREVIEW_TIMEOUT_MS = 5_000L

    private fun resolvePositionMs(c: MediaController): Long {
        val pos = c.currentPosition.coerceAtLeast(0)
        val pending = pendingSeekMs ?: return pos
        val age = SystemClock.elapsedRealtime() - pendingSeekSetAtMs
        return when {
            // The player reached the seek target → confirmed; resume tracking.
            // (Skip the first tick: right after a seek the controller still
            // reports the old position, which can be arbitrarily close to the
            // target for short seeks and would falsely clear the preview.)
            age > SEEK_CONFIRM_MIN_AGE_MS && abs(pos - pending) < SEEK_CONFIRM_TOLERANCE_MS -> {
                pendingSeekMs = null
                pos
            }
            // Safety net: drop a preview that never got confirmed.
            age > SEEK_PREVIEW_TIMEOUT_MS -> {
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

    /** Toggle shuffle; turning it on starts a fresh random round over the queue. */
    fun toggleShuffle() {
        val next = !_shuffleEnabled.value
        _shuffleEnabled.value = next
        // On: seed a fresh round. Off: just drop the walk on the floor.
        resetShuffleRound()
        scope.launch { MyMusicApp.instance.settings.setShuffleEnabled(next) }
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

    /**
     * Stop playback, stop the background service, then leave the app the
     * platform way: a Home intent backgrounds the whole task, and Android
     * reclaims the now-idle process on its own schedule. (The old
     * Process.killProcess shortcut could leave a stale notification behind
     * and skipped all normal teardown.)
     */
    private fun stopAndExit() {
        val c = controller
        c?.pause()
        c?.stop()
        appContext?.let { ctx ->
            runCatching {
                ctx.stopService(Intent(ctx, PlaybackService::class.java))
            }
            runCatching {
                ctx.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        _state.value = _state.value.copy(isPlaying = false, error = null)
    }

    /**
     * Seek to [index] (at [positionMs] when given). From a healthy player this
     * mirrors the original 上一首/下一首 behaviour (a paused player stays
     * paused). From STATE_IDLE — the state a fatal playback error leaves the
     * player in — seekTo() alone never starts playback, so re-prepare the
     * target item and play explicitly; this is what keeps 上一首/下一首 working
     * after a song fails instead of wedging the whole queue until a fresh
     * [playQueue] is issued from the search page.
     */
    private fun seekAndPlay(c: MediaController, index: Int, positionMs: Long = C.TIME_UNSET) {
        if (c.playbackState == Player.STATE_IDLE && c.mediaItemCount > 0) {
            c.seekTo(index, positionMs)
            c.prepare()
            c.play()
        } else {
            c.seekTo(index, positionMs)
        }
    }

    /**
     * Re-resolve a fresh audio URL for [track] through the shared repository
     * singleton. Direct-stream URLs carry a deadline and expire, so a
     * song that failed long after its queue was built (typical for background
     * playback) can be given a brand-new URL instead of being given up on.
     * Returns a failed [Result] (with the original error) when resolution
     * fails, so the caller can surface the real reason — e.g. a risk-control
     * cooldown message — rather than a blanket "无法获取音源".
     */
    private suspend fun resolveFresh(track: Track): Result<Track> =
        MyMusicApp.instance.audioCache.resolveShared(track, force = true).await()

    /**
     * After giving up on the current track, jump to the next one so a dead
     * song cannot wedge the whole queue while playing in the background. The
     * player is in STATE_IDLE after a fatal error, where seekTo() alone would
     * be a silent no-op, so re-prepare the target item and play explicitly.
     * No-op for the only/last song (the error stays visible there).
     */
    private fun skipAfterFailure(c: MediaController) {
        val count = c.mediaItemCount
        if (count <= 1) return // only/last song: keep the error visible
        val cur = c.currentMediaItemIndex
        val next = when {
            cur + 1 < count -> cur + 1
            c.repeatMode == Player.REPEAT_MODE_ALL -> 0
            else -> return
        }
        if (next == cur) return
        Log.w(TAG, "跳过无法播放的曲目 $cur -> $next")
        c.seekTo(next, C.TIME_UNSET)
        c.prepare()
        c.play()
    }

    private fun toMediaItem(track: Track?, url: String): MediaItem =
        MediaItem.Builder()
            .setUri(url)
            .setMediaId(track?.uid ?: url)
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
        // Prefer the queue's / registry's own copy of the entry — it carries
        // the 分P page, resolved cid and duration the media metadata lacks.
        val track = _state.value.queue.firstOrNull { it.uid == item.mediaId }
            ?: pendingTrack[item.mediaId]
            ?: fallbackTrack(item.mediaId, meta)
        _state.value = _state.value.copy(
            current = track,
            isPlaying = c.isPlaying,
            durationMs = c.duration.coerceAtLeast(0),
            // Keep the 正在播放列表 highlight on whatever the player is on.
            queueIndex = _state.value.queue
                .indexOfFirst { it.uid == item.mediaId }
                .takeIf { it >= 0 } ?: -1,
        )
        // Confirmed timeline position (also the seek-confirmation path).
        pendingSeekMs = null
        _positionMs.value = c.currentPosition.coerceAtLeast(0)
        scheduleProgressSave()
    }

    /**
     * Last-resort [Track] for a media item the queue / registry no longer
     * knows (e.g. a foreign item): rebuild identity from [mediaId], which is a
     * uid — a bare bvid for page 1, or "bvid-P<n>" for a 分P. The "-P<n>"
     * suffix must never leak into `bvid`, or later 搜索/收藏 lookups on this
     * entry would resolve the wrong video.
     */
    private fun fallbackTrack(mediaId: String, meta: MediaMetadata): Track {
        val page = mediaId.substringAfterLast("-P").toIntOrNull()?.takeIf { it > 1 } ?: 1
        val bvid = if (page > 1) mediaId.substringBeforeLast("-P") else mediaId
        return Track(
            bvid = bvid,
            title = meta.title?.toString() ?: "",
            author = meta.artist?.toString(),
            cover = meta.artworkUri?.toString(),
            page = page,
        )
    }
}
