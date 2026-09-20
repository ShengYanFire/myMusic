package com.mymusic.player.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.mymusic.player.domain.MusicGenres
import com.mymusic.player.domain.MusicMoods
import com.mymusic.player.network.BiliIdentity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Persistent app settings: the Bilibili cookie (SESSDATA, optional) used to
 * unlock higher-quality audio, the quality preference, and the user's
 * favorite music genres (音乐偏好) that personalize the recommendation feed.
 * The app is fully self-contained — there is no backend server to configure.
 */
class AppSettings(private val context: Context) {

    private val cookieKey = stringPreferencesKey("cookie")
    private val buvid3Key = stringPreferencesKey("buvid3")
    private val qualityKey = stringPreferencesKey("quality")
    private val repeatModeKey = intPreferencesKey("repeat_mode")
    private val shuffleEnabledKey = booleanPreferencesKey("shuffle_enabled")
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

    /** Clear the saved login cookie (退出登录). */
    suspend fun clearCookie() {
        context.settingsDataStore.edit { prefs ->
            prefs.remove(cookieKey)
        }
    }

    // ---- Device fingerprint (buvid3) ----

    /**
     * The anonymous per-install device fingerprint B站 uses for risk control.
     * Stable across launches, logins and logouts; generated once and never
     * cleared by 退出登录 (it identifies the device, not the account).
     */
    val buvid3: Flow<String> = context.settingsDataStore.data.map { prefs ->
        prefs[buvid3Key] ?: ""
    }

    private val buvidMutex = Mutex()

    /** In-process copy (hot path); the persisted DataStore value stays authoritative. */
    @Volatile
    private var cachedBuvid3: String? = null

    /**
     * Return the per-install buvid3, generating + persisting it on first use.
     * This runs on every api.bilibili.com request, so the result is cached in
     * [cachedBuvid3] (safe publication via @Volatile) — after the first call
     * it returns without taking the mutex or touching DataStore. The mutex
     * makes concurrent first-callers share one value (idempotent): no
     * duplicate fingerprints, no lost write.
     */
    suspend fun ensureBuvid3(): String {
        cachedBuvid3?.let { return it }
        return buvidMutex.withLock { cachedBuvid3 ?: loadOrCreateBuvid3() }
    }

    private suspend fun loadOrCreateBuvid3(): String {
        val current = context.settingsDataStore.data.first()[buvid3Key]
        if (!current.isNullOrBlank()) {
            cachedBuvid3 = current
            return current
        }
        val fresh = BiliIdentity.generateBuvid3()
        context.settingsDataStore.edit { prefs -> prefs[buvid3Key] = fresh }
        cachedBuvid3 = fresh
        return fresh
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

    /** Shuffle playback: random order over the queue, remembered across restarts. */
    val shuffleEnabled: Flow<Boolean> = context.settingsDataStore.data.map { prefs ->
        prefs[shuffleEnabledKey] ?: false
    }

    suspend fun setShuffleEnabled(value: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[shuffleEnabledKey] = value
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
