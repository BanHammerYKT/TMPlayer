package com.tmplayer.platform

import com.tmplayer.i18n.TmLocale
import kotlinx.coroutines.CoroutineDispatcher

/**
 * The few things pure code needs from the platform: the clock, the calendar, text that only the
 * platform's Unicode tables can answer, and a dispatcher for blocking work. The JDK on Android and
 * the desktop, the browser's own APIs on the web.
 */

/** Milliseconds since the epoch, as `System.currentTimeMillis()`. */
expect fun nowMillis(): Long

/** The year it is now on the device's calendar. */
expect fun currentYear(): Int

/**
 * A time zone by its IANA name ("Europe/Paris"), or the device's own when [id] is null. The
 * browser can only use the device's own; the JVM reads any.
 */
class TmZone(val id: String? = null)

/** The device's time zone. */
fun systemZone(): TmZone = TmZone()

/** The calendar date at [epochMillis] in [zone], as a count of days since 1970-01-01. */
expect fun localEpochDay(epochMillis: Long, zone: TmZone): Long

/** The calendar year at [epochMillis] in [zone]. */
expect fun localYear(epochMillis: Long, zone: TmZone): Int

/** "27 Sep", or "27 Sep 2025" with [withYear], at [epochMillis] in [zone], in [locale]. */
expect fun dayMonthIn(locale: TmLocale, epochMillis: Long, zone: TmZone, withYear: Boolean): String

/** A map several threads may read and write at once: ConcurrentHashMap on the JVM. */
expect fun <K : Any, V : Any> concurrentMap(): MutableMap<K, V>

/** Where blocking reads and writes run: Dispatchers.IO on the JVM, the default one in the browser. */
expect val ioDispatcher: CoroutineDispatcher

/** [text] decomposed (NFD) with its combining marks taken out: "Pokémon" to "Pokemon". */
expect fun stripCombiningMarks(text: String): String

/** Whether [codePoint] is in Unicode's "Symbol, other" category (emoji, dingbats, boxes). */
expect fun isOtherSymbol(codePoint: Int): Boolean

/** Compares text as a language orders it: `java.text.Collator` on the JVM, `Intl.Collator` in the browser. */
fun interface TextCollator {
    fun compare(source: String, target: String): Int
}

/** A collator for [locale]; [primary] ignores case and accents, as Collator.PRIMARY does. */
expect fun textCollator(locale: TmLocale, primary: Boolean = false): TextCollator

/** The device's own locale as a BCP 47 tag ("en-GB"), as `Locale.getDefault()` says it. */
expect fun deviceLocaleTag(): String

/** A lock for the few maps several threads share on the JVM; nothing to lock in the browser. */
expect class PlainLock() {
    fun <T> locked(block: () -> T): T
}
