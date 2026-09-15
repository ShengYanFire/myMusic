package com.mymusic.player.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mymusic.player.MyMusicApp
import com.mymusic.player.data.AppSettings
import com.mymusic.player.data.LibraryStore
import com.mymusic.player.data.Playlist
import com.mymusic.player.data.TrackRepository
import com.mymusic.player.domain.LyricLine
import com.mymusic.player.domain.Track
import com.mymusic.player.network.SearchItem
import com.mymusic.player.player.PlayerController
import com.mymusic.player.util.runSuspendCatching
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Shared [AndroidViewModel] for the whole app: search, recommendations,
 * library, lyrics and settings. The playback-queue pipeline (resolve cache,
 * in-flight dedup, queue-context decision, background fill) lives in its own
 * [PlaybackQueueCoordinator] — a plain class this ViewModel owns and forwards
 * the play requests to.
 */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    // Single shared repository/store instances (stateless singletons created
    // once in MyMusicApp): one OkHttp connection pool for the whole app.
    private val repo: TrackRepository = MyMusicApp.instance.trackRepo
    private val library: LibraryStore = MyMusicApp.instance.library
    private val settings = MyMusicApp.instance.settings

    // ---- Search ----
    val query = MutableStateFlow("")
    val searchResults = MutableStateFlow<List<SearchItem>>(emptyList())
    val searching = MutableStateFlow(false)
    val searchError = MutableStateFlow<String?>(null)
    val loadingMoreSearch = MutableStateFlow(false)
    val hasMoreSearch = MutableStateFlow(false)
    private var searchJob: Job? = null
    /** Next page to load for the current query ("到底部加载更多"). */
    private var searchPage = 1
    /** Bumped on every new search so stale load-more results are discarded. */
    private var searchGeneration = 0

    // ---- Settings ----
    val quality: StateFlow<String> = settings.quality
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings.DEFAULT_QUALITY)

    // ---- Music preference (喜欢的音乐类型 / 心情) ----
    /** IDs of the genres the user marked as favorites; empty = no preference. */
    val favoriteGenres: StateFlow<Set<String>> = settings.favoriteGenres
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** IDs of the listening moods the user marked as favorites; empty = none. */
    val favoriteMoods: StateFlow<Set<String>> = settings.favoriteMoods
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** Select/deselect a genre by id; persisted and picked up by the recommender. */
    fun toggleGenre(genreId: String) {
        // Atomic read-modify-write inside ONE DataStore edit: the old
        // read-flow-then-write raced a second rapid tap into a lost update.
        viewModelScope.launch { settings.toggleGenre(genreId) }
    }

    /** Select/deselect a mood by id; persisted and picked up by the recommender. */
    fun toggleMood(moodId: String) {
        viewModelScope.launch { settings.toggleMood(moodId) }
    }

    // ---- Library ----
    val favorites: StateFlow<List<Track>> = library.favorites
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playlists: StateFlow<List<Playlist>> = library.playlists
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- Messages ----

    /**
     * One-shot UI messages (snackbars), as a buffered Channel: unlike a
     * StateFlow it CONFLATES NOTHING — two rapid messages ("解析音源中…" then
     * "连播合集…") each get their own snackbar slot instead of the second
     * overwriting the first before it was shown, and there is no
     * consumeMessage() race between collectors.
     */
    private val _message = Channel<String>(capacity = Channel.BUFFERED)
    val message: Flow<String> = _message.receiveAsFlow()

    fun showMessage(msg: String) {
        _message.trySend(msg)
    }

    // ---- Lyrics ----
    private val lyricsRepo = MyMusicApp.instance.lyricsRepo

    private val _lyrics = MutableStateFlow(LyricsUiState())
    val lyrics: StateFlow<LyricsUiState> = _lyrics.asStateFlow()

    /** Bumped on every load request so a stale response never overwrites a newer song's lyrics. */
    private var lyricsGeneration = 0

    /**
     * Load lyrics for [track] (B站字幕 first, then LRCLIB). No-op when the
     * requested song is already loaded/loading; [force] bypasses the cache.
     * Keyed by uid — different 分P of one video have different subtitles.
     */
    fun loadLyrics(track: Track, force: Boolean = false) {
        val current = _lyrics.value
        if (!force && current.uid == track.uid &&
            (current.loading || current.source != null || current.error != null)
        ) {
            return
        }
        val gen = ++lyricsGeneration
        // Re-fetching the SAME track keeps its current lines on screen
        // (stale-while-revalidate) — a refresh should not blank the lyrics
        // page for the whole round trip.
        _lyrics.value = if (current.uid == track.uid) {
            current.copy(loading = true, error = null)
        } else {
            LyricsUiState(uid = track.uid, loading = true)
        }
        viewModelScope.launch {
            val result = runSuspendCatching { lyricsRepo.lyrics(track, force) }
            if (gen != lyricsGeneration) return@launch // a newer request won
            _lyrics.value = result.fold(
                onSuccess = { lyrics ->
                    if (lyrics != null) {
                        LyricsUiState(
                            uid = track.uid,
                            lines = lyrics.lines,
                            synced = lyrics.synced,
                            source = lyrics.source,
                        )
                    } else {
                        LyricsUiState(uid = track.uid, error = "暂无歌词")
                    }
                },
                onFailure = { e ->
                    LyricsUiState(uid = track.uid, error = e.message ?: "歌词加载失败")
                },
            )
        }
    }

    // ---- Search actions ----

    /** Reset all transient search state (results, error, pagination flags). */
    private fun resetSearchState() {
        searchJob?.cancel()
        searchResults.value = emptyList()
        searchError.value = null
        hasMoreSearch.value = false
        loadingMoreSearch.value = false
    }

    fun onQueryChange(value: String) {
        query.value = value
        // A blank query means "back to the recommendation feed": reset at
        // once — no reason to keep stale results/errors around for 400 ms.
        if (value.isBlank()) {
            resetSearchState()
            return
        }
        searchJob?.cancel()
        loadingMoreSearch.value = false
        searchJob = viewModelScope.launch {
            delay(400) // debounce
            doSearch(value.trim())
        }
    }

    fun searchNow(keyword: String) {
        if (keyword.isBlank()) return
        query.value = keyword
        searchJob?.cancel()
        loadingMoreSearch.value = false
        searchJob = viewModelScope.launch { doSearch(keyword.trim()) }
    }

    fun clearSearch() {
        query.value = ""
        resetSearchState()
    }

    private suspend fun doSearch(keyword: String) {
        searchGeneration++
        searching.value = true
        searchError.value = null
        searchPage = 1
        loadingMoreSearch.value = false
        // Drop the PREVIOUS search's results right away: showing stale rows
        // under a "searching" state invites tapping a result from the wrong
        // query, and the list keeps its scroll anyway.
        searchResults.value = emptyList()
        try {
            // distinctBy: never let duplicate bvids reach the LazyColumn keys.
            val items = repo.search(keyword, 1).distinctBy { it.bvid }
            searchResults.value = items
            hasMoreSearch.value = items.size >= SEARCH_PAGE_SIZE
        } catch (e: CancellationException) {
            // A superseding search cancelled this one: not an error — but
            // runCatching would have swallowed the cancellation and reported
            // a bogus "搜索失败" snackbar.
            throw e
        } catch (e: Exception) {
            searchError.value = e.message ?: "搜索失败，请检查网络"
            hasMoreSearch.value = false
        } finally {
            searching.value = false
        }
    }

    /** 到底部自动加载更多: fetch the next search page and append it. */
    fun loadMoreSearch() {
        if (searching.value || loadingMoreSearch.value) return
        if (!hasMoreSearch.value || query.value.isBlank()) return
        val gen = searchGeneration
        val keyword = query.value.trim()
        val page = searchPage + 1
        loadingMoreSearch.value = true
        viewModelScope.launch {
            try {
                val items = repo.search(keyword, page)
                if (gen != searchGeneration) return@launch // a newer search replaced this one
                // Bilibili pages overlap (the search ranking shifts between
                // requests): drop bvids already shown, otherwise the LazyColumn
                // crashes on a duplicate key.
                val seen = searchResults.value.asSequence().map { it.bvid }.toHashSet()
                val fresh = items.filter { it.bvid !in seen }
                searchResults.value = searchResults.value + fresh
                searchPage = page
                // A full page with nothing new means there is no more content.
                hasMoreSearch.value =
                    items.size >= SEARCH_PAGE_SIZE && fresh.isNotEmpty()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (gen == searchGeneration) {
                    showMessage("加载更多失败：${e.message ?: "请检查网络"}")
                }
            } finally {
                loadingMoreSearch.value = false
            }
        }
    }

    // ---- Recommendations (empty-query feed) ----
    val recommendations = MutableStateFlow<List<SearchItem>>(emptyList())
    val loadingRecommendations = MutableStateFlow(false)
    val loadingMoreRecommendations = MutableStateFlow(false)
    val hasMoreRecommendations = MutableStateFlow(false)
    val recommendError = MutableStateFlow<String?>(null)

    /** Cached mixed+shuffled pool from all recommendation sources (deduped). */
    private var recommendPool: List<SearchItem> = emptyList()
    /** Index into [recommendPool] where the next un-shown batch starts. */
    private var recommendCursor = 0

    /**
     * Set when a reload was requested while one was already in flight (a
     * genre/mood change mid-reload): the in-flight pool is built for the OLD
     * preference, so exactly ONE chained reload runs after it lands — not one
     * per change, and not zero (which silently kept a personalized-wrong feed
     * for minutes).
     */
    private var reloadPending = false
    private var reloadPendingKeepOnError = false

    /**
     * Bumped on every successful recommendation reload. The search page
     * observes it to scroll the feed back to its header — notably when the
     * user changes their genre preference and the feed is re-personalized.
     */
    private val _recommendGeneration = MutableStateFlow(0)
    val recommendGeneration: StateFlow<Int> = _recommendGeneration.asStateFlow()

    // ---- Playback queue pipeline (delegated to the coordinator) ----

    private val queue = PlaybackQueueCoordinator(
        repo = repo,
        scope = viewModelScope,
        onMessage = ::showMessage,
        shared = MyMusicApp.instance.audioCache,
    )

    /** uid of the track currently being resolved by [requestPlay] (row spinner). */
    val resolvingUid: StateFlow<String?> = queue.resolvingUid

    /** Start playing [tracks][index]; opens the play page via [onOpened]. */
    fun requestPlay(
        tracks: List<Track>,
        index: Int = 0,
        useListAsQueue: Boolean = false,
        startPositionMs: Long = 0L,
        onOpened: () -> Unit,
    ) = queue.requestPlay(tracks, index, useListAsQueue, startPositionMs, onOpened)

    /** Abort the current [requestPlay] resolution without starting playback. */
    fun cancelPlay() = queue.cancelPlay()

    init {
        loadRecommendations()
        // 下一首 on-demand: the player asks for entries it cannot play yet
        // through the needResolveNext FLOW (no static callback a dead
        // ViewModel could stay registered in), and the coordinator resolves
        // and inserts them.
        PlayerController.needResolveNext
            .onEach { queue.onNeedResolveNext(it) }
            .launchIn(viewModelScope)
        // 播单记忆上次进度: restore the last session's queue / song / position
        // (shown paused), and route a play tap on the not-yet-resolved restored
        // session back through the coordinator so it re-resolves and resumes.
        viewModelScope.launch {
            val saved = runSuspendCatching {
                MyMusicApp.instance.playbackProgress.state.first()
            }.getOrNull()
            if (saved != null) PlayerController.restoreProgress(saved)
        }
        PlayerController.needResume
            .onEach {
                val s = PlayerController.state.value
                val tracks = s.queue
                if (tracks.isEmpty()) return@onEach
                queue.requestPlay(
                    tracks = tracks,
                    index = s.queueIndex.coerceAtLeast(0).coerceAtMost(tracks.lastIndex),
                    useListAsQueue = true,
                    onOpened = {},
                    startPositionMs = PlayerController.positionMs.value.coerceAtLeast(0L),
                )
            }
            .launchIn(viewModelScope)
        // Re-personalize the feed whenever the genre/mood selection changes.
        // The first emission is just the persisted value loaded at startup —
        // the initial [loadRecommendations] already accounts for it.
        combine(settings.favoriteGenres, settings.favoriteMoods) { g, m -> g to m }
            .distinctUntilChanged()
            .drop(1)
            .onEach { reloadRecommendations(keepOnError = true) }
            .launchIn(viewModelScope)
    }

    fun loadRecommendations() = reloadRecommendations(keepOnError = false)

    /**
     * 下拉刷新/重新个性化: re-fetch every source into a fresh mixed+shuffled
     * pool and restart from the first batch; on failure keep the current list
     * and notify. Requests landing while a reload is in flight coalesce into
     * one chained reload.
     */
    fun refreshRecommendations() = reloadRecommendations(keepOnError = true)

    private fun reloadRecommendations(keepOnError: Boolean) {
        if (loadingRecommendations.value) {
            // Coalesce: whatever the in-flight reload fetches was built for
            // the old preference — one chained reload afterwards is enough.
            reloadPending = true
            reloadPendingKeepOnError = reloadPendingKeepOnError || keepOnError
            return
        }
        loadingRecommendations.value = true
        recommendError.value = null
        viewModelScope.launch {
            try {
                val pool = repo.recommendPool()
                if (pool.isEmpty()) throw RuntimeException("推荐列表为空")
                recommendPool = pool
                recommendCursor = RECOMMEND_BATCH
                recommendations.value = pool.take(RECOMMEND_BATCH)
                hasMoreRecommendations.value = pool.size > RECOMMEND_BATCH
                _recommendGeneration.value++
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (keepOnError && recommendations.value.isNotEmpty()) {
                    // Refresh failed: keep the current list visible, just notify.
                    showMessage("刷新失败：${e.message ?: "请检查网络"}")
                } else {
                    recommendError.value = e.message ?: "推荐加载失败，请检查网络"
                }
            } finally {
                loadingRecommendations.value = false
                val chain = reloadPending
                val chainKeep = reloadPendingKeepOnError
                reloadPending = false
                reloadPendingKeepOnError = false
                if (chain) {
                    // The preference changed mid-flight: rebuild for the NEW one.
                    reloadRecommendations(keepOnError = chainKeep)
                }
            }
        }
    }

    /** 到底部自动加载更多: append the next batch from the cached pool (no network). */
    fun loadMoreRecommendations() {
        if (loadingRecommendations.value || loadingMoreRecommendations.value) return
        if (!hasMoreRecommendations.value) return
        val pool = recommendPool
        val start = recommendCursor.coerceAtMost(pool.size)
        if (start >= pool.size) {
            hasMoreRecommendations.value = false
            return
        }
        val end = (start + RECOMMEND_BATCH).coerceAtMost(pool.size)
        // distinctBy: insurance so LazyColumn keys can never collide.
        recommendations.value =
            (recommendations.value + pool.subList(start, end)).distinctBy { it.bvid }
        recommendCursor = end
        if (end >= pool.size) hasMoreRecommendations.value = false
    }

    companion object {
        private const val RECOMMEND_BATCH = 20
        private const val SEARCH_PAGE_SIZE = 20
    }

    // ---- Library actions ----
    suspend fun toggleFavorite(track: Track) = library.toggleFavorite(track)
    suspend fun createPlaylist(name: String) = library.createPlaylist(name)
    suspend fun addToPlaylist(playlistId: String, track: Track) =
        library.addToPlaylist(playlistId, track)

    /** Remove the track with [uid] from a playlist (uid, not bvid: multi-P
     *  entries of one video are distinct songs). Returns false when it was
     *  not in the list. */
    suspend fun removeFromPlaylist(playlistId: String, uid: String) =
        library.removeFromPlaylist(playlistId, uid)
    suspend fun deletePlaylist(playlistId: String) = library.deletePlaylist(playlistId)
    suspend fun renamePlaylist(playlistId: String, name: String) =
        library.renamePlaylist(playlistId, name)

    // ---- Settings actions ----
    suspend fun saveCookie(value: String) = settings.setCookie(value)
    suspend fun saveQuality(value: String) = settings.setQuality(value)
    suspend fun testConnection(): Boolean = repo.testConnection()
}

/** Lyrics page state for the currently requested track. */
data class LyricsUiState(
    /** uid of the track these lyrics belong to (bvid, or bvid-P<n> for a 分P); null before the first request. */
    val uid: String? = null,
    val loading: Boolean = false,
    val lines: List<LyricLine> = emptyList(),
    /** False for plain untimed lyrics (static list, no highlight/scroll). */
    val synced: Boolean = false,
    /** Human-readable source badge, e.g. "B站AI字幕" / "网络歌词". */
    val source: String? = null,
    val error: String? = null,
)
