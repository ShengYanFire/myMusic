package com.mymusic.player.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mymusic.player.MyMusicApp
import com.mymusic.player.data.AppSettings
import com.mymusic.player.data.LibraryStore
import com.mymusic.player.data.LyricsRepository
import com.mymusic.player.data.Playlist
import com.mymusic.player.data.TrackRepository
import com.mymusic.player.domain.LyricLine
import com.mymusic.player.domain.Track
import com.mymusic.player.network.LyricsClient
import com.mymusic.player.network.SearchItem
import com.mymusic.player.player.PlayerController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** Shared [AndroidViewModel] for the whole app (search, library, settings). */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = TrackRepository(MyMusicApp.instance.api, MyMusicApp.instance.settings)
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
        viewModelScope.launch {
            // Read from the DataStore flow (source of truth) — the StateFlow
            // may still hold its initial empty value on a very first tap.
            val current = settings.favoriteGenres.first()
            settings.setFavoriteGenres(
                if (genreId in current) current - genreId else current + genreId,
            )
        }
    }

    /** Select/deselect a mood by id; persisted and picked up by the recommender. */
    fun toggleMood(moodId: String) {
        viewModelScope.launch {
            val current = settings.favoriteMoods.first()
            settings.setFavoriteMoods(
                if (moodId in current) current - moodId else current + moodId,
            )
        }
    }

    // ---- Library ----
    val favorites: StateFlow<List<Track>> = library.favorites
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    val playlists: StateFlow<List<Playlist>> = library.playlists
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    // ---- Messages ----
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun showMessage(msg: String) {
        _message.value = msg
    }

    fun consumeMessage() {
        _message.value = null
    }

    // ---- Lyrics ----
    private val lyricsRepo = LyricsRepository(
        MyMusicApp.instance.api,
        LyricsClient(MyMusicApp.instance.settings),
    )

    private val _lyrics = MutableStateFlow(LyricsUiState())
    val lyrics: StateFlow<LyricsUiState> = _lyrics.asStateFlow()

    /** Bumped on every load request so a stale response never overwrites a newer song's lyrics. */
    private var lyricsGeneration = 0

    /**
     * Load lyrics for [track] (B站字幕 first, then LRCLIB). No-op when the
     * requested song is already loaded/loading; [force] bypasses the cache.
     */
    fun loadLyrics(track: Track, force: Boolean = false) {
        val current = _lyrics.value
        if (!force && current.bvid == track.bvid &&
            (current.loading || current.source != null || current.error != null)
        ) {
            return
        }
        val gen = ++lyricsGeneration
        _lyrics.value = LyricsUiState(bvid = track.bvid, loading = true)
        viewModelScope.launch {
            val result = runCatching { lyricsRepo.lyrics(track, force) }
            if (gen != lyricsGeneration) return@launch // a newer request won
            _lyrics.value = result.fold(
                onSuccess = { lyrics ->
                    if (lyrics != null) {
                        LyricsUiState(
                            bvid = track.bvid,
                            lines = lyrics.lines,
                            synced = lyrics.synced,
                            source = lyrics.source,
                        )
                    } else {
                        LyricsUiState(bvid = track.bvid, error = "暂无歌词")
                    }
                },
                onFailure = { e ->
                    LyricsUiState(bvid = track.bvid, error = e.message ?: "歌词加载失败")
                },
            )
        }
    }

    // ---- Search actions ----
    fun onQueryChange(value: String) {
        query.value = value
        searchJob?.cancel()
        loadingMoreSearch.value = false
        searchJob = viewModelScope.launch {
            delay(400) // debounce
            if (value.isBlank()) {
                searchResults.value = emptyList()
                searchError.value = null
                hasMoreSearch.value = false
                return@launch
            }
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
        searchResults.value = emptyList()
        searchError.value = null
        hasMoreSearch.value = false
        loadingMoreSearch.value = false
        searchJob?.cancel()
    }

    private suspend fun doSearch(keyword: String) {
        searchGeneration++
        searching.value = true
        searchError.value = null
        searchPage = 1
        loadingMoreSearch.value = false
        try {
            // distinctBy: never let duplicate bvids reach the LazyColumn keys.
            val items = repo.search(keyword, 1).distinctBy { it.bvid }
            searchResults.value = items
            hasMoreSearch.value = items.size >= SEARCH_PAGE_SIZE
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
     * Bumped on every successful recommendation reload. The search page
     * observes it to scroll the feed back to its header — notably when the
     * user changes their genre preference and the feed is re-personalized.
     */
    private val _recommendGeneration = MutableStateFlow(0)
    val recommendGeneration: StateFlow<Int> = _recommendGeneration.asStateFlow()

    init {
        loadRecommendations()
        // Re-personalize the feed whenever the genre/mood selection changes.
        // The first emission is just the persisted value loaded at startup —
        // the initial [loadRecommendations] already accounts for it.
        viewModelScope.launch {
            var previous: Pair<Set<String>, Set<String>>? = null
            combine(settings.favoriteGenres, settings.favoriteMoods) { g, m -> g to m }
                .collect { ids ->
                    val last = previous
                    previous = ids
                    if (last != null && ids != last) reloadRecommendations(keepOnError = true)
                }
        }
    }

    fun loadRecommendations() = reloadRecommendations(keepOnError = false)

    /**
     * 下拉刷新: re-fetch every source into a fresh mixed+shuffled pool and
     * restart from the first batch; on failure keep the current list and notify.
     */
    fun refreshRecommendations() = reloadRecommendations(keepOnError = true)

    private fun reloadRecommendations(keepOnError: Boolean) {
        if (loadingRecommendations.value) return
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
            } catch (e: Exception) {
                if (keepOnError && recommendations.value.isNotEmpty()) {
                    // Refresh failed: keep the current list visible, just notify.
                    showMessage("刷新失败：${e.message ?: "请检查网络"}")
                } else {
                    recommendError.value = e.message ?: "推荐加载失败，请检查网络"
                }
            } finally {
                loadingRecommendations.value = false
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

    // ---- Playback support ----

    /** Background job extending the play queue of the last [playFromList] call. */
    private var queueJob: Job? = null

    /** Bumped on every [playFromList] call so stale background results are dropped. */
    private var queueGeneration = 0

    /**
     * Play [tracks][index] in its list context:
     * 1. resolve the tapped song (and, in parallel, the next one) and start
     *    playback right away — as fast as the old single-song flow;
     * 2. resolve the rest of the list in the background and arrange the songs
     *    around the playing one, so 上一首/下一首 walk the whole list instead
     *    of being stuck on a one-song queue.
     *
     * Returns false (with a toast) when the tapped song has no playable audio.
     */
    suspend fun playFromList(tracks: List<Track>, index: Int = 0): Boolean {
        if (tracks.isEmpty()) return false
        val i = index.coerceIn(tracks.indices)
        val tapped = tracks[i]
        val gen = ++queueGeneration
        queueJob?.cancel()
        showMessage("解析音源中…")
        // Resolve the tapped song first so playback starts immediately; the
        // next song resolves in parallel so 下一首 responds right away.
        val (tappedResult, resolvedNext) = coroutineScope {
            val nextDeferred = async {
                tracks.getOrNull(i + 1)?.let { next -> resolveAudioResult(next).getOrNull() }
            }
            val result = resolveAudioResult(tapped)
            if (result.getOrDefault(tapped).hasAudio) {
                result to nextDeferred.await()
            } else {
                nextDeferred.cancel() // failed anyway — don't wait for the lookahead
                result to null
            }
        }
        if (gen != queueGeneration) return true // a newer request took over
        val resolvedTapped = tappedResult.getOrDefault(tapped)
        if (!resolvedTapped.hasAudio) {
            showMessage("播放失败：${tappedResult.exceptionOrNull()?.message ?: "无法获取音源"}")
            return false
        }
        val head = buildList {
            add(resolvedTapped)
            if (resolvedNext?.hasAudio == true) add(resolvedNext)
        }
        PlayerController.playQueue(head)
        // Build the rest of the queue in the background: the songs before the
        // tapped one are later inserted ahead of it, the rest appended after.
        if (tracks.size > head.size) {
            queueJob = viewModelScope.launch {
                val skipNext = resolvedNext?.hasAudio == true
                val before = tracks.take(i)
                val after = tracks.drop(i + if (skipNext) 2 else 1)
                val resolved = resolveQueue(before + after)
                if (gen != queueGeneration) return@launch // superseded meanwhile
                PlayerController.insertQueue(
                    before = resolved.take(before.size).filter { it.hasAudio },
                    after = resolved.drop(before.size).filter { it.hasAudio },
                    pivotBvid = resolvedTapped.bvid,
                )
            }
        }
        return true
    }

    /** [resolveAudio][TrackRepository.resolveAudio] wrapped as a [Result], letting cancellation through. */
    private suspend fun resolveAudioResult(track: Track): Result<Track> = try {
        Result.success(repo.resolveAudio(track))
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * Resolve audio URLs for a whole queue in parallel with bounded concurrency
     * (avoid hammering Bilibili with N simultaneous calls), preserving input
     * order. A track that fails to resolve keeps its original value; the player
     * skips entries without a usable URL.
     */
    suspend fun resolveQueue(
        tracks: List<Track>,
        concurrency: Int = 4,
    ): List<Track> = withContext(Dispatchers.Default) {
        val semaphore = Semaphore(concurrency)
        coroutineScope {
            tracks.map { t ->
                async {
                    semaphore.withPermit {
                        runCatching { repo.resolveAudio(t) }.getOrDefault(t)
                    }
                }
            }.awaitAll()
        }
    }

    // ---- Library actions ----
    suspend fun toggleFavorite(track: Track) = library.toggleFavorite(track)
    suspend fun createPlaylist(name: String) = library.createPlaylist(name)
    suspend fun addToPlaylist(playlistId: String, track: Track) =
        library.addToPlaylist(playlistId, track)
    suspend fun removeFromPlaylist(playlistId: String, bvid: String) =
        library.removeFromPlaylist(playlistId, bvid)
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
    /** bvid of the track these lyrics belong to; null before the first request. */
    val bvid: String? = null,
    val loading: Boolean = false,
    val lines: List<LyricLine> = emptyList(),
    /** False for plain untimed lyrics (static list, no highlight/scroll). */
    val synced: Boolean = false,
    /** Human-readable source badge, e.g. "B站AI字幕" / "网络歌词". */
    val source: String? = null,
    val error: String? = null,
)
