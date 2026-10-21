// The JDK as far as the browser build of :core's JVM half needs it (core/web-sources.txt).
// Kotlin/Wasm has no JDK; these stand in for the few JDK types and functions those files use,
// with the browser's single thread in mind: no lock is needed where there is no second thread,
// and a file is a name, since TDLib keeps its own files in tdweb's storage.
package com.tmplayer.platform

/** `synchronized` on the JVM; one thread, so the block simply runs. */
internal inline fun <R> synchronized(@Suppress("UNUSED_PARAMETER") lock: Any, block: () -> R): R = block()

/** The two clocks of `java.lang.System` the shared code reads. */
internal object System {
    fun currentTimeMillis(): Long = com.tmplayer.platform.nowMillis()
    fun nanoTime(): Long = com.tmplayer.platform.monotonicMillis() * 1_000_000
}

/** `java.util.Map.getOrDefault`, which Kotlin's maps have only on the JVM. */
internal fun <K, V> Map<K, V>.getOrDefault(key: K, defaultValue: V): V = this[key] ?: defaultValue
