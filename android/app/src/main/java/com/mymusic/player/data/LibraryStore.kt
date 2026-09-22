package com.mymusic.player.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.mymusic.player.domain.Track
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.libraryDataStore by preferencesDataStore(name = "library")

data class Playlist(
    val id: String,
    val name: String,
    val tracks: List<Track>,
)

/**
 * Lightweight local library: favorites + playlists, persisted as JSON in
 * DataStore (no database needed for a lightweight player).
 *
 * Identity is [Track.uid] (bvid, or bvid-P<n> for a 分P) everywhere — the same
 * identity the play queue uses — so two pages of one video can coexist in
 * favorites/playlists.
 *
 * Parsing/serialization runs on [Dispatchers.Default]: the flows are collected
 * in viewModelScope (main dispatcher), and a full-library Gson parse must not
 * run there.
 */
class LibraryStore(private val context: Context) {

    private val gson = Gson()
    private val favoritesKey = stringPreferencesKey("favorites")
    private val playlistsKey = stringPreferencesKey("playlists")

    val favorites: Flow<List<Track>> = context.libraryDataStore.data
        .map { prefs -> parse(prefs[favoritesKey] ?: "[]") }
        .flowOn(Dispatchers.Default)

    val playlists: Flow<List<Playlist>> = context.libraryDataStore.data
        .map { prefs -> parsePlaylists(prefs[playlistsKey] ?: "[]") }
        .flowOn(Dispatchers.Default)

    // All mutations read the current value inside the atomic DataStore edit
    // transaction (no read-outside / write-inside race between concurrent edits).

    suspend fun toggleFavorite(track: Track) {
        val track = track.sanitizeForSave()
        context.libraryDataStore.edit { prefs ->
            val current = parse(prefs[favoritesKey] ?: "[]")
            prefs[favoritesKey] = if (current.any { it.uid == track.uid }) {
                gson.toJson(current.filterNot { it.uid == track.uid })
            } else {
                gson.toJson(listOf(track) + current)
            }
        }
    }

    /**
     * Mutate the playlist list atomically: [transform] receives the parsed
     * list and returns whether anything changed; the result is persisted
     * only then (no-op edits never touch DataStore).
     */
    private suspend fun mutatePlaylists(transform: (MutableList<Playlist>) -> Boolean) {
        context.libraryDataStore.edit { prefs ->
            val current = parsePlaylists(prefs[playlistsKey] ?: "[]").toMutableList()
            if (transform(current)) {
                prefs[playlistsKey] = gson.toJson(current)
            }
        }
    }

    suspend fun createPlaylist(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        mutatePlaylists { playlists ->
            playlists.add(
                0,
                Playlist(id = "p${UUID.randomUUID()}", name = trimmed, tracks = emptyList()),
            )
            true
        }
    }

    suspend fun addToPlaylist(playlistId: String, track: Track): Boolean {
        val track = track.sanitizeForSave()
        var changed = false
        mutatePlaylists { playlists ->
            val index = playlists.indexOfFirst { it.id == playlistId }
            if (index < 0) return@mutatePlaylists false
            val playlist = playlists[index]
            if (playlist.tracks.any { it.uid == track.uid }) return@mutatePlaylists false
            playlists[index] = playlist.copy(tracks = playlist.tracks + track)
            changed = true
            true
        }
        return changed
    }

    suspend fun removeFromPlaylist(playlistId: String, uid: String) {
        mutatePlaylists { playlists ->
            val index = playlists.indexOfFirst { it.id == playlistId }
            if (index < 0) return@mutatePlaylists false
            val tracks = playlists[index].tracks
            val filtered = tracks.filterNot { it.uid == uid }
            if (filtered.size == tracks.size) return@mutatePlaylists false
            playlists[index] = playlists[index].copy(tracks = filtered)
            true
        }
    }

    suspend fun deletePlaylist(playlistId: String) {
        mutatePlaylists { playlists -> playlists.removeAll { it.id == playlistId } }
    }

    suspend fun renamePlaylist(playlistId: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        mutatePlaylists { playlists ->
            val index = playlists.indexOfFirst { it.id == playlistId }
            if (index < 0) return@mutatePlaylists false
            if (playlists[index].name == name) return@mutatePlaylists false
            playlists[index] = playlists[index].copy(name = name)
            true
        }
    }

    private fun parse(raw: String): List<Track> =
        runCatching {
            gson.fromJson(raw, Array<Track>::class.java)
                ?.mapNotNull(::sanitizeLoadedTrack)
                ?: emptyList()
        }.getOrDefault(emptyList())

    private fun parsePlaylists(raw: String): List<Playlist> =
        runCatching {
            gson.fromJson(raw, Array<Playlist>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
}
