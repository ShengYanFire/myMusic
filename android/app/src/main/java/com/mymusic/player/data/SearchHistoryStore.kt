package com.mymusic.player.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

private val Context.searchHistoryDataStore by preferencesDataStore(name = "search_history")

/**
 * 搜索历史: a most-recent-first list of the keywords the user actually
 * submitted — the search button / IME action / a history-or-hot chip tap, but
 * NEVER the live-as-you-type debounce (which would record partial words like
 * "周" then "周杰" then "周杰伦"). Persisted as a JSON array in DataStore, capped
 * at [MAX_ENTRIES] and de-duplicated: re-searching a term moves it to the front.
 *
 * Parsing/serialization runs on [Dispatchers.Default] (the flows are collected
 * on the main dispatcher), mirroring LibraryStore so a larger list never parses
 * on the UI thread.
 */
class SearchHistoryStore(private val context: Context) {

    private val gson = Gson()
    private val key = stringPreferencesKey("history")

    val history: Flow<List<String>> = context.searchHistoryDataStore.data
        .map { prefs -> parse(prefs[key] ?: "[]") }
        .flowOn(Dispatchers.Default)

    /** Record [query] at the front of the list (no-op for blank input). */
    suspend fun add(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        context.searchHistoryDataStore.edit { prefs ->
            val current = parse(prefs[key] ?: "[]").toMutableList()
            current.removeAll { it == q } // de-dupe: re-search moves it to the front
            current.add(0, q)
            while (current.size > MAX_ENTRIES) current.removeAt(current.lastIndex)
            prefs[key] = gson.toJson(current)
        }
    }

    /** Remove every occurrence of [query] (normally exactly one). */
    suspend fun remove(query: String) {
        val q = query.trim()
        if (q.isEmpty()) return
        context.searchHistoryDataStore.edit { prefs ->
            val current = parse(prefs[key] ?: "[]").toMutableList()
            val before = current.size
            current.removeAll { it == q }
            if (current.size != before) prefs[key] = gson.toJson(current)
        }
    }

    suspend fun clear() {
        context.searchHistoryDataStore.edit { prefs ->
            prefs.remove(key)
        }
    }

    private fun parse(raw: String): List<String> =
        runCatching {
            gson.fromJson(raw, Array<String>::class.java)
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?: emptyList()
        }.getOrDefault(emptyList())

    companion object {
        private const val MAX_ENTRIES = 20
    }
}