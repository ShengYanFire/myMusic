package com.mymusic.player.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mymusic.player.MyMusicApp
import com.mymusic.player.data.AppSettings
import com.mymusic.player.data.LibraryStore
import com.mymusic.player.data.Playlist
import com.mymusic.player.data.TrackRepository
import com.mymusic.player.domain.Track
import com.mymusic.player.network.SearchItem
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
    private var searchJob: Job? = null

    // ---- Settings ----
    val cookie: StateFlow<String> = settings.cookie
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val quality: StateFlow<String> = settings.quality
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings.DEFAULT_QUALITY)

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

    // ---- Search actions ----
    fun onQueryChange(value: String) {
        query.value = value
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(400) // debounce
            if (value.isBlank()) {
                searchResults.value = emptyList()
                searchError.value = null
                return@launch
            }
            doSearch(value.trim())
        }
    }

    fun searchNow(keyword: String) {
        if (keyword.isBlank()) return
        query.value = keyword
        searchJob?.cancel()
        searchJob = viewModelScope.launch { doSearch(keyword.trim()) }
    }

    fun clearSearch() {
        query.value = ""
        searchResults.value = emptyList()
        searchError.value = null
    }

    private suspend fun doSearch(keyword: String) {
        searching.value = true
        searchError.value = null
        try {
            searchResults.value = repo.search(keyword)
        } catch (e: Exception) {
            searchError.value = e.message ?: "搜索失败，请检查网络"
        } finally {
            searching.value = false
        }
    }

    // ---- Playback support ----
    suspend fun resolveAudio(track: Track): Track = repo.resolveAudio(track)

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
