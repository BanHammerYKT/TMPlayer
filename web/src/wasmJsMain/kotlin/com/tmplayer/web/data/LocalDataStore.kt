package com.tmplayer.web.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.tmplayer.web.js.TmHost
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The apps' preference store (SettingsStore, WatchedStore, BookmarkStore all take a
 * DataStore of Preferences) over the browser's localStorage, one JSON object per store under
 * [key]. Each value keeps its type beside it ("b", "i", "l", "f", "d", "s", "ss"), so a key reads
 * back as the kind it was written as, which is what DataStore's typed keys need.
 *
 * The same keys as on the phone and the desktop, so a backup and the sorting choices
 * (vfilter_<chatId>, downloads_sort, history_sort, hidden_chats) mean the same here.
 */
class LocalDataStore(private val key: String) : DataStore<Preferences> {
    private val state = MutableStateFlow(load())
    private val writing = Mutex()

    override val data: Flow<Preferences> get() = state

    override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = writing.withLock {
        val next = transform(state.value).toPreferences()
        if (next != state.value) {
            state.value = next
            runCatching { TmHost.setItem(key, encode(next)) }
        }
        next
    }

    private fun Preferences.toPreferences(): Preferences = (this as? MutablePreferences)?.toPreferences() ?: this

    private fun load(): Preferences {
        val text = runCatching { TmHost.getItem(key) }.getOrNull() ?: return emptyPreferences()
        val root = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyPreferences()
        val prefs = mutablePreferencesOf()
        for ((name, entry) in root) {
            val pair = (entry as? JsonArray)?.takeIf { it.size == 2 } ?: continue
            val kind = (pair[0] as? JsonPrimitive)?.content ?: continue
            val v = pair[1]
            val p = v as? JsonPrimitive
            when (kind) {
                "b" -> p?.booleanOrNull?.let { prefs[booleanPreferencesKey(name)] = it }
                "i" -> p?.longOrNull?.let { prefs[intPreferencesKey(name)] = it.toInt() }
                "l" -> p?.longOrNull?.let { prefs[longPreferencesKey(name)] = it }
                "f" -> p?.doubleOrNull?.let { prefs[floatPreferencesKey(name)] = it.toFloat() }
                "d" -> p?.doubleOrNull?.let { prefs[doublePreferencesKey(name)] = it }
                "s" -> p?.content?.let { prefs[stringPreferencesKey(name)] = it }
                "ss" -> runCatching { v.jsonArray.mapNotNull { (it as? JsonPrimitive)?.content }.toSet() }.getOrNull()
                    ?.let { prefs[stringSetPreferencesKey(name)] = it }
            }
        }
        return prefs.toPreferences()
    }

    private fun encode(prefs: Preferences): String = buildJsonObject {
        for ((k, value) in prefs.asMap()) {
            val pair = when (value) {
                is Boolean -> buildJsonArray { add(JsonPrimitive("b")); add(JsonPrimitive(value)) }
                is Int -> buildJsonArray { add(JsonPrimitive("i")); add(JsonPrimitive(value)) }
                is Long -> buildJsonArray { add(JsonPrimitive("l")); add(JsonPrimitive(value)) }
                is Float -> buildJsonArray { add(JsonPrimitive("f")); add(JsonPrimitive(value)) }
                is Double -> buildJsonArray { add(JsonPrimitive("d")); add(JsonPrimitive(value)) }
                is String -> buildJsonArray { add(JsonPrimitive("s")); add(JsonPrimitive(value)) }
                is Set<*> -> buildJsonArray {
                    add(JsonPrimitive("ss"))
                    add(buildJsonArray { value.forEach { add(JsonPrimitive(it.toString())) } })
                }
                else -> null
            } ?: continue
            put(k.name, pair)
        }
    }.toString()
}
