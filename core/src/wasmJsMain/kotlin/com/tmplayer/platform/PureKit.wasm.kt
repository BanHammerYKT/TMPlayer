package com.tmplayer.platform

import com.tmplayer.i18n.TmLocale
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

private fun jsNow(): Double = js("Date.now()")

private fun jsYear(): Int = js("new Date().getFullYear()")

/** Days since the epoch of the local calendar date: the local midnight's own UTC fields. */
private fun jsLocalEpochDay(millis: Double): Double =
    js("(() => { const d = new Date(millis); return Math.floor(Date.UTC(d.getFullYear(), d.getMonth(), d.getDate()) / 86400000); })()")

private fun jsLocalYear(millis: Double): Int = js("new Date(millis).getFullYear()")

private fun jsDayMonth(tag: String, millis: Double, year: Boolean): String =
    js("new Intl.DateTimeFormat(tag, year ? { day: 'numeric', month: 'short', year: 'numeric' } : { day: 'numeric', month: 'short' }).format(new Date(millis))")

private fun jsStripMarks(text: String): String = js("text.normalize('NFD').replace(/\\p{M}/gu, '')")

private fun jsIsOtherSymbol(code: Int): Boolean = js("/\\p{So}/u.test(String.fromCodePoint(code))")

private fun jsCompare(tag: String, primary: Boolean, a: String, b: String): Int =
    js("new Intl.Collator(tag, primary ? { sensitivity: 'base' } : {}).compare(a, b)")

actual fun nowMillis(): Long = jsNow().toLong()

actual fun currentYear(): Int = jsYear()

actual fun localEpochDay(epochMillis: Long, zone: TmZone): Long = jsLocalEpochDay(epochMillis.toDouble()).toLong()

actual fun localYear(epochMillis: Long, zone: TmZone): Int = jsLocalYear(epochMillis.toDouble())

actual fun dayMonthIn(locale: TmLocale, epochMillis: Long, zone: TmZone, withYear: Boolean): String =
    jsDayMonth(locale.tag, epochMillis.toDouble(), withYear)

/** One thread in the browser: an ordinary map is safe. */
actual fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V> = HashMap()

actual val ioDispatcher: CoroutineDispatcher get() = Dispatchers.Default

actual fun stripCombiningMarks(text: String): String = jsStripMarks(text)

actual fun isOtherSymbol(codePoint: Int): Boolean = jsIsOtherSymbol(codePoint)

actual fun textCollator(locale: TmLocale, primary: Boolean): TextCollator {
    val tag = locale.tag
    return TextCollator { a, b -> jsCompare(tag, primary, a, b) }
}

private fun jsLanguage(): String = js("(navigator.languages && navigator.languages[0]) || navigator.language || 'en-US'")

actual fun deviceLocaleTag(): String = runCatching { jsLanguage() }.getOrDefault("en-US")

actual class PlainLock actual constructor() {
    actual fun <T> locked(block: () -> T): T = block()
}

private fun jsNowMonotonic(): Double = js("performance.now()")

private fun jsRandomByte(): Int = js("crypto.getRandomValues(new Uint8Array(1))[0]")

actual fun monotonicMillis(): Long = jsNowMonotonic().toLong()

actual fun secureRandomBytes(count: Int): ByteArray = ByteArray(count) { jsRandomByte().toByte() }
