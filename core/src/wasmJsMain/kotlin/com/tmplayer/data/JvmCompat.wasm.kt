// The JDK as far as the browser build of :core's JVM half needs it (core/web-sources.txt).
// Kotlin/Wasm has no JDK; these stand in for the few JDK types and functions those files use,
// with the browser's single thread in mind: no lock is needed where there is no second thread,
// and a file is a name, since TDLib keeps its own files in tdweb's storage.
package com.tmplayer.data

/** `synchronized` on the JVM; one thread, so the block simply runs. */
internal inline fun <R> synchronized(@Suppress("UNUSED_PARAMETER") lock: Any, block: () -> R): R = block()

/** The two clocks of `java.lang.System` the shared code reads. */
internal object System {
    fun currentTimeMillis(): Long = com.tmplayer.platform.nowMillis()
    fun nanoTime(): Long = com.tmplayer.platform.monotonicMillis() * 1_000_000
}

/** Backup.kt's raw write, for the stores the browser build compiles without Backup itself. */
internal fun androidx.datastore.preferences.core.MutablePreferences.putRaw(name: String, value: Any) {
    @Suppress("UNCHECKED_CAST")
    when (value) {
        is Boolean -> this[androidx.datastore.preferences.core.booleanPreferencesKey(name)] = value
        is Int -> this[androidx.datastore.preferences.core.intPreferencesKey(name)] = value
        is Long -> this[androidx.datastore.preferences.core.longPreferencesKey(name)] = value
        is Float -> this[androidx.datastore.preferences.core.floatPreferencesKey(name)] = value
        is Double -> this[androidx.datastore.preferences.core.doublePreferencesKey(name)] = value
        is String -> this[androidx.datastore.preferences.core.stringPreferencesKey(name)] = value
        is Set<*> -> this[androidx.datastore.preferences.core.stringSetPreferencesKey(name)] = value as Set<String>
    }
}

/** `java.util.Map.getOrDefault`, which Kotlin's maps have only on the JVM. */
internal fun <K, V> Map<K, V>.getOrDefault(key: K, defaultValue: V): V = this[key] ?: defaultValue

/**
 * What UpdateScheduler remembers between launches (core/src/commonMain/.../UpdateScheduler.kt).
 * The browser build has no updater, the site being the update, so only the interface the
 * settings store implements comes along, word for word.
 */
interface UpdatePrefs {
    suspend fun notify(): Boolean
    suspend fun lastCheck(): Long
    suspend fun setLastCheck(at: Long)
    suspend fun skippedVersion(): String
    suspend fun setSkippedVersion(version: String)
    suspend fun snoozedUntil(): Long
    suspend fun setSnoozedUntil(at: Long)
    suspend fun popupShownFor(): String
    suspend fun setPopupShownFor(version: String)
}
