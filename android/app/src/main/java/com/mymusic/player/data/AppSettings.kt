package com.mymusic.player.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "settings")

/**
 * Persistent app settings: the Bilibili cookie (SESSDATA, optional) used to
 * unlock higher-quality audio, and the quality preference.
 * The app is fully self-contained — there is no backend server to configure.
 */
class AppSettings(private val context: Context) {

    private val cookieKey = stringPreferencesKey("cookie")
    private val qualityKey = stringPreferencesKey("quality")

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
            prefs[qualityKey] = if (value == "low") "low" else "high"
        }
    }

    companion object {
        const val DEFAULT_QUALITY = "high"
    }
}
