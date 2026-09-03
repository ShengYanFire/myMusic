package com.mymusic.player.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.mymusic.player.domain.Track
import kotlinx.coroutines.flow.Flow
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
 */
class LibraryStore(private val context: Context) {

    private val gson = Gson()
    private val favoritesKey = stringPreferencesKey("favorites")
    private val playlistsKey = stringPreferencesKey("playlists")

    val favorites: Flow<List<Track>> = context.libraryDataStore.data.map { prefs ->
        parse(prefs[favoritesKey] ?: "[]")
    }

    val playlists: Flow<List<Playlist>> = context.libraryDataStore.data.map { prefs ->
        parsePlaylists(prefs[playlistsKey] ?: "[]")
    }

    // All mutations read the current value inside the atomic DataStore edit
    // transaction (no read-outside / write-inside race between concurrent edits).

    suspend fun toggleFavorite(track: Track) {
        context.libraryDataStore.edit { prefs ->
            val current = parse(prefs[favoritesKey] ?: "[]").toMutableList()
            if (current.any { it.bvid == track.bvid }) {
                current.removeAll { it.bvid == track.bvid }
            } else {
                current.add(0, track)
            }
            prefs[favoritesKey] = gson.toJson(current)
        }
    }

    suspend fun createPlaylist(name: String) {
        context.libraryDataStore.edit { prefs ->
            val current = parsePlaylists(prefs[playlistsKey] ?: "[]").toMutableList()
            current.add(
                0,
                Playlist(id = "p${System.currentTimeMillis()}", name = name, tracks = emptyList()),
            )
            prefs[playlistsKey] = gson.toJson(current)
        }
    }

    suspend fun addToPlaylist(playlistId: String, track: Track) {
        context.libraryDataStore.edit { prefs ->
            val current = parsePlaylists(prefs[playlistsKey] ?: "[]").toMutableList()
            val index = current.indexOfFirst { it.id == playlistId }
            if (index >= 0) {
                val playlist = current[index]
                val tracks = playlist.tracks.toMutableList()
                if (tracks.none { it.bvid == track.bvid }) tracks.add(track)
                current[index] = playlist.copy(tracks = tracks)
                prefs[playlistsKey] = gson.toJson(current)
            }
        }
    }

    suspend fun removeFromPlaylist(playlistId: String, bvid: String) {
        context.libraryDataStore.edit { prefs ->
            val current = parsePlaylists(prefs[playlistsKey] ?: "[]").toMutableList()
            val index = current.indexOfFirst { it.id == playlistId }
            if (index >= 0) {
                val playlist = current[index]
                current[index] =
                    playlist.copy(tracks = playlist.tracks.filterNot { it.bvid == bvid })
                prefs[playlistsKey] = gson.toJson(current)
            }
        }
    }

    suspend fun deletePlaylist(playlistId: String) {
        context.libraryDataStore.edit { prefs ->
            val current = parsePlaylists(prefs[playlistsKey] ?: "[]")
                .filterNot { it.id == playlistId }
            prefs[playlistsKey] = gson.toJson(current)
        }
    }

    suspend fun renamePlaylist(playlistId: String, newName: String) {
        val name = newName.trim()
        if (name.isEmpty()) return
        context.libraryDataStore.edit { prefs ->
            val current = parsePlaylists(prefs[playlistsKey] ?: "[]").toMutableList()
            val index = current.indexOfFirst { it.id == playlistId }
            if (index >= 0) {
                current[index] = current[index].copy(name = name)
                prefs[playlistsKey] = gson.toJson(current)
            }
        }
    }

    private fun parse(raw: String): List<Track> =
        runCatching {
            gson.fromJson(raw, Array<Track>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())

    private fun parsePlaylists(raw: String): List<Playlist> =
        runCatching {
            gson.fromJson(raw, Array<Playlist>::class.java)?.toList() ?: emptyList()
        }.getOrDefault(emptyList())
}
