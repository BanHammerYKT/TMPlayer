package com.tmplayer.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * A DataStore preferences file as a [KeyValueStore], so the pure stores (WatchedBook,
 * WatchLaterBook, SettingsBook) read and write the very file the apps already keep, key for key.
 */
class DataStoreKeyValueStore(private val store: DataStore<Preferences>) : KeyValueStore {

    override val data: Flow<Map<String, Any>> = store.data.map { prefs -> prefs.asMap().mapKeys { it.key.name } }

    override suspend fun snapshot(): Map<String, Any> = data.first()

    override suspend fun edit(change: (MutableMap<String, Any>) -> Unit) {
        store.edit { prefs ->
            val keys = prefs.asMap().keys.associateBy { it.name }
            val before = prefs.asMap().mapKeys { it.key.name }
            val after = LinkedHashMap(before)
            change(after)
            for (gone in before.keys - after.keys) keys[gone]?.let { prefs.remove(it) }
            for ((name, value) in after) {
                if (before[name] == value) continue
                require(isStorable(value)) { "a value of a type DataStore does not keep: $name" }
                // A name kept with another type is replaced, not added beside it.
                keys[name]?.let { prefs.remove(it) }
                prefs.putTyped(name, value)
            }
        }
    }
}

private fun MutablePreferences.putTyped(name: String, value: Any) {
    when (value) {
        is String -> this[stringPreferencesKey(name)] = value
        is Boolean -> this[booleanPreferencesKey(name)] = value
        is Int -> this[intPreferencesKey(name)] = value
        is Long -> this[longPreferencesKey(name)] = value
        is Float -> this[floatPreferencesKey(name)] = value
        is Double -> this[doublePreferencesKey(name)] = value
        is Set<*> -> this[stringSetPreferencesKey(name)] = value.mapTo(HashSet()) { it.toString() }
    }
}
