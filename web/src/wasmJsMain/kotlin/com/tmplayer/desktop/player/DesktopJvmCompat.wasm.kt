// What the desktop's screens name of the JDK, for their browser build (web/build.gradle.kts,
// desktopWebSources): the browser has one thread, so a lock is the block itself.
package com.tmplayer.desktop.player

internal inline fun <R> synchronized(@Suppress("UNUSED_PARAMETER") lock: Any, block: () -> R): R = block()

internal object System {
    fun currentTimeMillis(): Long = com.tmplayer.platform.nowMillis()
    fun nanoTime(): Long = com.tmplayer.platform.monotonicMillis() * 1_000_000
    fun identityHashCode(x: Any?): Int = x.hashCode()
    /** No system properties or environment in a page: every switch read from them is off. */
    @Suppress("UNUSED_PARAMETER")
    fun getProperty(key: String): String? = null
    @Suppress("UNUSED_PARAMETER")
    fun getProperty(key: String, default: String): String = default
    @Suppress("UNUSED_PARAMETER")
    fun getenv(name: String): String? = null
}

internal fun <K, V> Map<K, V>.getOrDefault(key: K, defaultValue: V): V = this[key] ?: defaultValue

internal fun Char.titlecase(@Suppress("UNUSED_PARAMETER") locale: com.tmplayer.i18n.TmLocale): String = titlecase()

internal fun String.uppercase(@Suppress("UNUSED_PARAMETER") locale: com.tmplayer.i18n.TmLocale): String = uppercase()
internal fun String.lowercase(@Suppress("UNUSED_PARAMETER") locale: com.tmplayer.i18n.TmLocale): String = lowercase()
internal fun String.format(@Suppress("UNUSED_PARAMETER") locale: com.tmplayer.i18n.TmLocale, vararg args: Any?): String = com.tmplayer.platform.printf(this, args)
internal fun String.format(vararg args: Any?): String = com.tmplayer.platform.printf(this, args)
internal fun String.Companion.format(@Suppress("UNUSED_PARAMETER") locale: com.tmplayer.i18n.TmLocale, format: String, vararg args: Any?): String = com.tmplayer.platform.printf(format, args)
internal fun String.Companion.format(format: String, vararg args: Any?): String = com.tmplayer.platform.printf(format, args)
internal fun String.codePointAt(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length && this[index + 1].isLowSurrogate()) {
        return ((high.code - 0xD800) shl 10) + (this[index + 1].code - 0xDC00) + 0x10000
    }
    return high.code
}
internal fun String(chars: CharArray): String = chars.concatToString()

internal object Character {
    fun toChars(codePoint: Int): CharArray = if (codePoint < 0x10000) {
        charArrayOf(codePoint.toChar())
    } else {
        val cp = codePoint - 0x10000
        charArrayOf((0xD800 + (cp shr 10)).toChar(), (0xDC00 + (cp and 0x3FF)).toChar())
    }
}

internal object Math {
    fun floorMod(a: Int, b: Int): Int = ((a % b) + b) % b
    fun floorMod(a: Long, b: Long): Long = ((a % b) + b) % b
    fun toDegrees(radians: Double): Double = radians * 180.0 / kotlin.math.PI
    fun toRadians(degrees: Double): Double = degrees * kotlin.math.PI / 180.0
    fun abs(x: Float): Float = kotlin.math.abs(x)
    fun abs(x: Double): Double = kotlin.math.abs(x)
    fun abs(x: Int): Int = kotlin.math.abs(x)
    fun abs(x: Long): Long = kotlin.math.abs(x)
}

/** No blocking on the browser's one thread: a first read that would wait answers with nothing. */
internal fun <T> runBlocking(@Suppress("UNUSED_PARAMETER") block: suspend kotlinx.coroutines.CoroutineScope.() -> T): T? = null
