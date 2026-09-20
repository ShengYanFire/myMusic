package com.mymusic.player.ui

import android.util.Log
import com.mymusic.player.data.AudioResolutionCache
import com.mymusic.player.data.ResolvedTrack
import com.mymusic.player.domain.Track
import com.mymusic.player.network.toTrack
import com.mymusic.player.player.PlayerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout

/**
 * The audio-resolution seam the coordinator needs from the data layer, so
 * unit tests can fake resolution ([PlaybackQueueCoordinatorTest]) without
 * BiliDirectClient/TrackRepository's real machinery.
 */
interface TrackResolver {
    /** Resolve the direct audio URL of [track] with the FEWEST B站 calls
     *  (one playurl round trip when the track knows its own cid). */
    suspend fun resolveAudioFast(track: Track): Track

    /** Resolve audio + the queue context (分P list / 合集 episodes). */
    suspend fun resolveAudioWithSeason(track: Track): ResolvedTrack
}

/**
 * Player timeline operations the coordinator drives. Everything the coordinator
 * needs to touch on the player side behind one seam, so unit tests can fake the
 * timeline ([PlaybackQueueCoordinatorTest]) without the PlayerController /
 * Media3 machinery.
 */
interface PlayerOps {
    /** Start playing [context][startIndex], resuming at [startPositionMs] (used
     *  by 播单记忆上次进度 to continue a restored session mid-song). */
    fun playQueue(context: List<Track>, startIndex: Int, startPositionMs: Long = 0L)
    fun insertQueueResolved(track: Track, pivotUid: String, before: Boolean, beforeOffset: Int): Boolean
    fun insertNextOnDemand(track: Track): Boolean
    fun clearLoadingNext()
    fun loadingNextUid(): String?
    fun currentUid(): String?

    companion object {
        /** The real thing: delegates to the app's player singleton. */
        val DEFAULT = object : PlayerOps {
            override fun playQueue(context: List<Track>, startIndex: Int, startPositionMs: Long) =
                PlayerController.playQueue(context, startIndex, startPositionMs)

            override fun insertQueueResolved(
                track: Track,
                pivotUid: String,
                before: Boolean,
                beforeOffset: Int,
            ) = PlayerController.insertQueueResolved(track, pivotUid, before, beforeOffset)

            override fun insertNextOnDemand(track: Track) =
                PlayerController.insertNextOnDemand(track)

            override fun clearLoadingNext() = PlayerController.clearLoadingNext()

            override fun loadingNextUid(): String? =
                PlayerController.state.value.loadingNextUid

            override fun currentUid(): String? =
                PlayerController.state.value.current?.uid
        }
    }
}

/**
 * The playback-queue pipeline, extracted from MainViewModel so the queue logic
 * is a plain class (B1) with explicit dependencies:
 *
 *  - shared in-flight resolution dedup + a TTL/LRU resolved-URL cache,
 *  - the queue-context decision (分P / 合集 / tapped list) of a play tap,
 *  - the ordered, chunked background fill of the player timeline,
 *  - the 下一首 on-demand resolution when the fill has not reached an entry.
 *
 * MainViewModel constructs one of these per scope and forwards the play calls,
 * keeping itself to search / recommendations / library / lyrics / settings.
 */
class PlaybackQueueCoordinator(
    private val repo: TrackResolver,
    private val scope: CoroutineScope,
    private val onMessage: (String) -> Unit,
    private val player: PlayerOps = PlayerOps.DEFAULT,
    /**
     * Shared audio-resolution cache (TTL + in-flight dedup). Defaults to a
     * private one bound to [scope], so the unit tests construct the coordinator
     * exactly as before; the app injects MyMusicApp's singleton so the player's
     * error-recovery path and the queue fill share ONE cache / B站 round trip.
     */
    private val shared: AudioResolutionCache =
        AudioResolutionCache(repo::resolveAudioFast, scope),
) {

    private companion object {
        private const val TAG = "MyMusicPlayer"

        /** Queue entries resolved per background chunk (bounds the request
         *  burst when filling a long 合集 and inserts results progressively). */
        private const val QUEUE_CHUNK = 4

        /** Total budget (ms) for resolving the tapped song's audio in the
         *  foreground — bounds how long "解析音源中…" can stall the play page
         *  when B站 is slow or unresponsive (the per-request OkHttp timeouts
         *  alone, connect 15s + read 30s × 2 requests, could mean ~90s). */
        private const val PLAY_RESOLVE_TIMEOUT_MS = 20_000L

        /** Per-track budget (ms) for the background queue resolution — a dead
         *  entry expires instead of wedging the whole 合集/选集 fill. */
        private const val QUEUE_RESOLVE_TIMEOUT_MS = 15_000L
    }

    /** Background job extending the play queue of the last [playFromList] call. */
    private var queueJob: Job? = null

    /** Bumped on every [playFromList] call so stale background results are dropped. */
    private var queueGeneration = 0

    // ---- Shared audio resolution (cache + in-flight dedup) ----
    // Lives in [shared] (AudioResolutionCache) so the queue fill, a 下一首
    // on-demand tap and a replay share one B站 round trip, and the player's
    // error-recovery path reuses the very same cache.

    // ---- Play requests ----

    /** uid of the track whose audio is currently being resolved by [requestPlay];
     *  null when idle. Drives the per-row spinner on the search/library lists. */
    private val _resolvingUid = MutableStateFlow<String?>(null)
    val resolvingUid = _resolvingUid

    /** The in-flight [requestPlay] job, so [cancelPlay] can abort a stuck
     *  resolution (the screen's own coroutine is not cancellable by the ViewModel). */
    private var playJob: Job? = null

    /** Abort the current [requestPlay] resolution without starting playback. */
    fun cancelPlay() {
        playJob?.cancel()
    }

    /**
     * Start playing [tracks][index] and open the play page once playback
     * starts. The resolution runs in the coordinator scope so it survives
     * screen switches and can be aborted with [cancelPlay]; [onOpened] is
     * invoked on the main thread once the tapped song is actually playing.
     *
     * [useListAsQueue] pins the play queue to the provided [tracks] as-is —
     * used by the library screens (收藏/歌单) so tapping a song there queues
     * the whole favorites/playlist instead of expanding into the B站 合集/分P
     * of the tapped video.
     */
    fun requestPlay(
        tracks: List<Track>,
        index: Int = 0,
        useListAsQueue: Boolean = false,
        /** Resume position (ms) for a restored session, passed through to the
         *  player's initial seek. Default 0 = start the track from the top. */
        startPositionMs: Long = 0L,
        onOpened: () -> Unit,
    ) {
        if (tracks.isEmpty()) return
        playJob?.cancel()
        playJob = scope.launch {
            val i = index.coerceIn(tracks.indices)
            val self = coroutineContext[Job]
            try {
                _resolvingUid.value = tracks[i].uid
                if (playFromList(tracks, i, useListAsQueue, startPositionMs)) onOpened()
            } finally {
                // Only the job that still owns resolvingUid may clear it: a
                // superseded request's cancellation must not blank a newer one.
                if (playJob === self) _resolvingUid.value = null
            }
        }
    }

    /**
     * 下一首 on-demand: the next queue entry is not in the player timeline yet
     * (the background fill is still resolving it, or its earlier resolve failed
     * and the fill skipped it) — resolve it right now and play it, instead of a
     * silent no-op that reads as "点下一首没反应 / 加载很慢".
     * The shared cache joins the fill's in-flight request for the same track
     * (or the 30 min cache) instead of firing a duplicate B站 round trip.
     */
    fun onNeedResolveNext(track: Track) {
        scope.launch {
            val pivot = player.currentUid() ?: run {
                player.clearLoadingNext()
                return@launch
            }
            // Await the shared future directly (not shared.resolve) so a failed
            // resolution keeps its reason — e.g. "请求过于频繁，请稍后再试"
            // during a risk-control cooldown — instead of collapsing every
            // failure into a generic "无法获取音源".
            val result = try {
                withTimeout(PLAY_RESOLVE_TIMEOUT_MS) { shared.resolveShared(track).await() }
            } catch (e: TimeoutCancellationException) {
                // Same contract as the tapped-song path: a slow B站 reads as a
                // clear message, not a silent "点下一首没反应".
                player.clearLoadingNext()
                onMessage("下一首解析超时，请检查网络后重试")
                return@launch
            } catch (e: CancellationException) {
                player.clearLoadingNext()
                return@launch
            } catch (e: Exception) {
                player.clearLoadingNext()
                onMessage("下一首解析失败：${e.message ?: "无法获取音源"}")
                return@launch
            }
            val resolved = result.getOrNull()
            if (resolved == null || !resolved.hasAudio) {
                player.clearLoadingNext()
                onMessage("下一首解析失败：${result.exceptionOrNull()?.message ?: "无法获取音源"}")
                return@launch
            }
            // The user navigated away / started a new queue meanwhile.
            if (player.currentUid() != pivot) {
                player.clearLoadingNext()
                return@launch
            }
            // insertNextOnDemand itself inserts right after the current item
            // and jumps to it with ORDERED session commands. (A timeline
            // re-scan right after addMediaItems — what the old skipTo call
            // did — races the controller's async cache update and could leave
            // the resolved track inserted but never playing.)
            if (!player.insertNextOnDemand(resolved)) {
                player.clearLoadingNext()
                onMessage("下一首暂时无法播放")
            }
        }
    }

    /**
     * Play [tracks][index] in its list context:
     * 1. resolve the tapped song and start playback right away — the play
     *    page opens as soon as ITS audio is ready, nothing else blocks it;
     * 2. pick the queue context, best match first: a multi-P video (视频选集)
     *    queues its own 分P pages, each page a song; otherwise a video
     *    belonging to a B站 合集 (ugc_season) with more than one episode
     *    queues the WHOLE collection from the tapped episode on; otherwise
     *    the queue is the list the song was tapped from — the 分P list and
     *    合集 ride along on the same /view call that resolves the audio, so
     *    neither costs an extra request. With [useListAsQueue] (library
     *    lists: 收藏/歌单) the expansion is skipped and [tracks] itself
     *    becomes the queue — a 合集/分P song tapped there plays the list it
     *    was tapped from, not the whole collection;
     * 3. fill the rest of the player queue in the background in small chunks
     *    — the songs AFTER the tapped one first (下一首 responds right away),
     *    then the ones before it — so 上一首/下一首 walk the whole list
     *    instead of being stuck on a one-song queue.
     *
     * Returns false (with a message) when the tapped song has no playable audio.
     */
    suspend fun playFromList(
        tracks: List<Track>,
        index: Int = 0,
        useListAsQueue: Boolean = false,
        startPositionMs: Long = 0L,
    ): Boolean {
        if (tracks.isEmpty()) return false
        val i = index.coerceIn(tracks.indices)
        val tapped = tracks[i]
        val gen = ++queueGeneration
        queueJob?.cancel()
        // Abandon the previous queue's resolutions the moment a new queue is
        // tapped: its background fill / 下一首 on-demand / 预热 workers are
        // already superseded, so cancel their in-flight B站 requests instead of
        // letting them run orphaned (fixes the "频繁切换堆孤儿请求" burst).
        shared.cancelInFlight()
        onMessage("解析音源中…")
        // Library lists (收藏/歌单) pin the queue to the list itself, so the
        // IMMEDIATE next track's resolution can START now, in parallel with
        // the tapped song's — 下一首 tapped right after the play page opens
        // then finds the entry resolved (or joins the in-flight future that
        // began earlier) instead of starting its own B站 round trip from zero.
        if (useListAsQueue) tracks.getOrNull(i + 1)?.let { shared.resolveShared(it) }
        // Resolve ONLY the tapped song here: the play page must open as soon
        // as it is ready. The rest of the list (下一首 first) is resolved and
        // merged into the player queue in the background below, so a slow or
        // stuck entry can never hold up starting playback.
        val tappedResult = try {
            withTimeout(PLAY_RESOLVE_TIMEOUT_MS) {
                if (useListAsQueue) {
                    // The queue is pinned to the list — no 分P/合集 info
                    // needed — so the shared resolver applies (cache /
                    // in-flight / one-call fast path).
                    shared.resolveShared(tapped).await()
                        .map { ResolvedTrack(track = it) }
                } else {
                    resolveWithSeasonResult(tapped)
                }
            }
        } catch (e: TimeoutCancellationException) {
            // B站 slow/unresponsive: fail fast with a clear message instead of
            // hanging on "解析音源中…" for up to ~90s of OkHttp timeouts.
            Result.failure(RuntimeException("解析音源超时，请检查网络后重试"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
        if (gen != queueGeneration) return true // a newer request took over
        val resolved = tappedResult.getOrNull()
        if (resolved == null || !resolved.track.hasAudio) {
            onMessage("播放失败：${tappedResult.exceptionOrNull()?.message ?: "无法获取音源"}")
            return false
        }
        val resolvedTapped = resolved.track

        // Queue context, best-match first:
        //  a) 分P (视频选集): a multi-P video queues its own pages — each
        //     page is a song of its own, played from the tapped page on;
        //  b) 合集 (ugc_season): the whole uploader collection becomes the
        //     queue, starting at the tapped episode;
        //  c) otherwise: the list the song was tapped from.
        // The resolved copy of the tapped entry is swapped in so the player
        // starts playable; the rest stays unresolved and is filled in the
        // background (its fresh URL/cid merges back via PlayerController).
        val pages = resolved.pages.sortedBy { it.page }
        val season = resolved.seasonEpisodes.distinctBy { it.bvid }
        val seasonIndex = season.indexOfFirst { it.bvid == tapped.bvid }
        val pageIndex = pages.indexOfFirst { it.page == tapped.page }.takeIf { it >= 0 } ?: 0

        // useListAsQueue (library lists: 收藏/歌单) pins the queue to the list
        // the song was tapped from — no 合集/分P expansion.
        val fromPages = !useListAsQueue && pages.size > 1
        val fromSeason =
            !useListAsQueue && !fromPages && season.size > 1 && seasonIndex >= 0
        val context: List<Track>
        val contextIndex: Int
        when {
            fromPages -> {
                val list = pages.map { p ->
                    val base = if (p.page == tapped.page) resolvedTapped else tapped
                    base.copy(
                        page = p.page,
                        cid = p.cid,
                        title = p.part.ifBlank { "${tapped.title} P${p.page}" },
                        duration = if (p.duration > 0) p.duration else base.duration,
                    )
                }.toMutableList()
                // Belt and braces: the entry playback starts on must be the
                // resolved one (unreachable while resolveAudioWithSeason
                // throws on a missing page, but never start unplayable).
                if (list[pageIndex].page != tapped.page) list[pageIndex] = resolvedTapped
                context = list
                contextIndex = pageIndex
            }
            fromSeason -> {
                context = season.map { it.toTrack() }.toMutableList()
                    .also { it[seasonIndex] = resolvedTapped }
                contextIndex = seasonIndex
            }
            else -> {
                // Strip the STORED audio URLs of every other entry: favorites /
                // playlists keep whatever direct URL a song had when it was last
                // favorited or played, and those links expire after hours. A
                // non-blank URL would put the entry straight into the player via
                // playQueue — where the background fill can never replace it
                // (uid dedup) — so 下一首 would hit an expired URL, grind
                // through the 403 → mirror-rotation → re-resolve fallback and
                // only then play: the "点下一首卡很久才播放" complaint.
                // Entries freshly resolved in THIS session keep their URLs
                // (shared cache) and queue instantly instead; search/合集 lists
                // carry no URLs, so the strip is a no-op for them.
                val list = tracks.mapIndexed { j, t ->
                    if (j == i) resolvedTapped
                    else shared.cached(t) ?: t.copy(audioUrl = null, audioUrls = emptyList())
                }
                context = list
                contextIndex = i
            }
        }

        // Visible feedback + a logcat breadcrumb for diagnosing which shape
        // a video matched (multi-P / 合集 / plain list).
        when {
            fromPages -> onMessage("连播视频选集：共 ${pages.size} 个分P")
            fromSeason -> onMessage("连播合集：共 ${season.size} 集")
        }
        Log.d(
            TAG,
            "queue context: pages=${pages.size} season=${season.size} " +
                "list=${tracks.size} -> " +
                (if (fromPages) "pages" else if (fromSeason) "season" else "list"),
        )

        // The whole context becomes the visible queue at once (including the
        // entries that are still unresolved); the player itself starts on the
        // resolved tapped song. Then the rest — 下一首 first, then the songs
        // before the tapped one — is resolved and inserted in the background,
        // chunk by chunk, so a slow entry can never block the play page from
        // opening or 上一首/下一首 from walking the whole list.
        val queued = try {
            player.playQueue(context, contextIndex, startPositionMs)
            true
        } catch (e: Exception) {
            false
        }
        if (!queued) {
            // The media session is broken — don't navigate into a dead player;
            // surface the error and leave the current screen in place.
            queueJob?.cancel()
            onMessage("播放失败：播放器不可用")
            return false
        }
        val before = context.take(contextIndex)
        val after = context.drop(contextIndex + 1)
        if (before.isNotEmpty() || after.isNotEmpty()) {
            queueJob = scope.launch {
                // 下一首 first, then the songs before the tapped one. Each entry
                // is resolved with bounded concurrency and inserted THE MOMENT it
                // resolves (in list order), so the immediate next track lands as
                // soon as its own two API calls finish — instead of waiting for a
                // whole 4-track chunk to complete before anything is inserted.
                if (gen != queueGeneration) return@launch // superseded
                fillQueueInOrder(after, before = false, pivotUid = resolvedTapped.uid)
                if (gen != queueGeneration) return@launch // superseded
                fillQueueInOrder(before, before = true, pivotUid = resolvedTapped.uid)
            }
        }
        return true
    }

    /**
     * Resolve [tracks] with bounded concurrency and insert each playable
     * result into the player THE MOMENT it resolves, strictly in list order —
     * the player timeline stays an ordered extension so 下一首's index+1
     * mapping is always right, and the immediate next entry lands as soon as
     * its own resolution finishes.
     *
     * Resolutions go through the shared resolver (cache + in-flight dedup +
     * the one-call fast path), so a 下一首 tapped mid-fill joins the very
     * future this loop started instead of duplicating the B站 round trip.
     *
     * A failed resolve is SKIPPED (never falls back to the stored copy, whose
     * B站 direct URL is long expired for favorites — planting it here would
     * turn the next 下一首 into a 403 → re-resolve round trip). The entry
     * stays visible in the queue and is resolved on demand if 下一首/点播
     * reaches it.
     */
    private suspend fun fillQueueInOrder(
        tracks: List<Track>,
        before: Boolean,
        pivotUid: String,
    ) {
        if (tracks.isEmpty()) return
        val semaphore = Semaphore(QUEUE_CHUNK)
        coroutineScope {
            val deferred = tracks.map { t ->
                async {
                    semaphore.withPermit {
                        try {
                            withTimeout(QUEUE_RESOLVE_TIMEOUT_MS) { shared.resolve(t) }
                        } catch (e: TimeoutCancellationException) {
                            null // per-track budget exceeded — skip this entry
                        } catch (e: CancellationException) {
                            throw e // real job cancellation — propagate
                        } catch (e: Exception) {
                            null // B站 error — skip this entry
                        }
                    }
                }
            }
            var insertedBefore = 0
            for (d in deferred) {
                val resolved = d.await() ?: continue
                if (!resolved.hasAudio) continue
                if (player.insertQueueResolved(
                        track = resolved,
                        pivotUid = pivotUid,
                        before = before,
                        beforeOffset = insertedBefore,
                    )
                ) {
                    insertedBefore++
                }
            }
        }
    }

    /** [TrackRepository.resolveAudioWithSeason] wrapped as a [Result], letting cancellation through. */
    private suspend fun resolveWithSeasonResult(track: Track): Result<ResolvedTrack> = try {
        Result.success(repo.resolveAudioWithSeason(track))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
}
