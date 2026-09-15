package com.mymusic.player.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mymusic.player.domain.MusicGenres
import com.mymusic.player.domain.MusicMoods
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Persistent app settings: the Bilibili cookie (SESSDATA, optional) used to
 * unlock higher-quality audio, the quality preference, and the user's
 * favorite music genres (音乐偏好) that personalize the recommendation feed.
 * The app is fully self-contained — there is no backend server to configure.
 */
class AppSettings(private val context: Context) {

    private val cookieKey = stringPreferencesKey("cookie")
    private val qualityKey = stringPreferencesKey("quality")
    private val repeatModeKey = intPreferencesKey("repeat_mode")
    private val favoriteGenresKey = stringSetPreferencesKey("favorite_genres")
    private val favoriteMoodsKey = stringSetPreferencesKey("favorite_moods")

    val cookie: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[cookieKey] ?: ""
    }

    /** "high" = highest bandwidth (default), "low" = least data. */
    val quality: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[qualityKey] ?: DEFAULT_QUALITY
    }

    suspend fun setCookie(value: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[cookieKey] = value.trim()
        }
    }

    suspend fun setQuality(value: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[qualityKey] = if (value == QUALITY_LOW) QUALITY_LOW else QUALITY_HIGH
        }
    }

    /**
     * Atomically toggle one genre id. The read AND the write happen inside the
     * same DataStore edit transaction — two rapid taps can never read the same
     * stale set and overwrite each other's selection.
     */
    suspend fun toggleGenre(id: String) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[favoriteGenresKey] ?: emptySet()
            prefs[favoriteGenresKey] = if (id in current) current - id else current + id
        }
    }

    /** Same as [toggleGenre] for listening moods. */
    suspend fun toggleMood(id: String) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs[favoriteMoodsKey] ?: emptySet()
            prefs[favoriteMoodsKey] = if (id in current) current - id else current + id
        }
    }

    /** Repeat mode: 0 = off, 1 = one, 2 = all (Media3 Player.REPEAT_MODE_*). */
    val repeatMode: Flow<Int> = context.settingsDataStore.data.map { prefs ->
        prefs[repeatModeKey] ?: 0
    }

    suspend fun setRepeatMode(mode: Int) {
        context.settingsDataStore.edit { prefs ->
            prefs[repeatModeKey] = mode.coerceIn(0, 2)
        }
    }

    /**
     * IDs of the music genres the user likes (音乐偏好, used to personalize the
     * recommendation feed). Empty = no preference set yet → generic music feed.
     */
    val favoriteGenres: Flow<Set<String>> = context.settingsDataStore.data.map { prefs ->
        prefs[favoriteGenresKey] ?: emptySet()
    }

    /** Persists the selected genre ids (unknown/stale ids are dropped). */
    suspend fun setFavoriteGenres(ids: Set<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[favoriteGenresKey] = ids.filter { MusicGenres.byId(it) != null }.toSet()
        }
    }

    /**
     * IDs of the listening moods the user likes (心情偏好, personalizes the
     * recommendation feed together with [favoriteGenres]). Empty = none.
     */
    val favoriteMoods: Flow<Set<String>> = context.settingsDataStore.data.map { prefs ->
        prefs[favoriteMoodsKey] ?: emptySet()
    }

    /** Persists the selected mood ids (unknown/stale ids are dropped). */
    suspend fun setFavoriteMoods(ids: Set<String>) {
        context.settingsDataStore.edit { prefs ->
            prefs[favoriteMoodsKey] = ids.filter { MusicMoods.byId(it) != null }.toSet()
        }
    }

    companion object {
        const val DEFAULT_QUALITY = "high"

        /** Quality preference values — referenced by settings UI and the player. */
        const val QUALITY_HIGH = "high"
        const val QUALITY_LOW = "low"
    }
}
