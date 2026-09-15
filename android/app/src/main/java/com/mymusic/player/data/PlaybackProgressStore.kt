package com.mymusic.player.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.mymusic.player.domain.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.progressDataStore by preferencesDataStore(name = "playback_progress")

/**
 * The last playback progress, remembered across restarts (播单记忆上次进度):
 * the whole play queue, which entry was playing, and how far into it playback
 * had reached. Audio URLs are never persisted — B站 direct links expire within
 * hours and are re-resolved on play.
 */
data class SavedPlaybackState(
    val queue: List<Track>,
    val currentUid: String,
    val positionMs: Long,
)

/**
 * Persists [SavedPlaybackState] as JSON in DataStore, mirroring [LibraryStore]
 * (no database needed for a lightweight player). The persisted tracks are
 * sanitized exactly like the library's — no audio URLs, repaired 分P page —
 * so a stale or malformed entry can never crash the restore path.
 */
class PlaybackProgressStore(private val context: Context) {

    private val gson = Gson()
    private val stateKey = stringPreferencesKey("playback_state")

    val state: Flow<SavedPlaybackState?> = context.progressDataStore.data
        .map { prefs -> parse(prefs[stateKey]) }
        .flowOn(Dispatchers.Default)

    /** Persist the last queue / current song / position. An empty queue clears it. */
    suspend fun save(queue: List<Track>, currentUid: String, positionMs: Long) {
        if (queue.isEmpty()) {
            clear()
            return
        }
        // Serialize inside the edit transform (DataStore runs it on its own
        // dispatcher), so a large queue never JSON-encodes on the caller's
        // (Main) thread — same principle as LibraryStore.
        context.progressDataStore.edit { prefs ->
            prefs[stateKey] = gson.toJson(
                SavedPlaybackState(
                    queue = queue.map(::sanitizeForSave),
                    currentUid = currentUid,
                    positionMs = positionMs.coerceAtLeast(0L),
                ),
            )
        }
    }

    suspend fun clear() {
        context.progressDataStore.edit { prefs -> prefs.remove(stateKey) }
    }

    /** Strip the direct URLs that expire after hours before persisting. */
    private fun sanitizeForSave(track: Track): Track =
        if (track.audioUrl != null || track.audioUrls.isNotEmpty()) {
            track.copy(audioUrl = null, audioUrls = emptyList())
        } else {
            track
        }

    // Parsed manually from JsonObject (not gson.fromJson<SavedPlaybackState>):
    // Gson Unsafe-instantiates data classes with no constructor, so a missing
    // field would read as null through a non-null Kotlin property and crash
    // the restore path. JsonObject extraction validates each field explicitly.
    private fun parse(raw: String?): SavedPlaybackState? {
        if (raw.isNullOrBlank()) return null
        val obj = runCatching { gson.fromJson(raw, JsonObject::class.java) }.getOrNull()
            ?: return null
        val queue = runCatching {
            gson.fromJson(obj.get("queue"), Array<Track>::class.java)
        }.getOrNull()?.mapNotNull(::sanitizeLoaded).orEmpty()
        val currentUid = (obj.get("currentUid") as? JsonPrimitive)?.asString
        val positionMs = (obj.get("positionMs") as? JsonPrimitive)
            ?.takeIf { it.isNumber }
            ?.asLong ?: 0L
        if (queue.isEmpty() || currentUid.isNullOrBlank()) return null
        return SavedPlaybackState(
            queue = queue,
            currentUid = currentUid,
            positionMs = positionMs.coerceAtLeast(0L),
        )
    }

    /** Same repair the library applies: Gson/Unsafe leaves `page` 0 and null
     *  title on partial data — fix identity and drop entries without a bvid. */
    private fun sanitizeLoaded(track: Track?): Track? {
        val bvid = track?.bvid
        if (bvid.isNullOrBlank()) return null
        val page = if (track.page >= 1) track.page else 1
        return track.copy(
            title = track.title ?: "",
            page = page,
            audioUrl = null,
            audioUrls = emptyList(),
        )
    }
}