package com.tmplayer.i18n

/**
 * The locale numbers, dates and language names are written in: `java.util.Locale` on Android and
 * the desktop (so code there keeps using it as one), a BCP 47 tag read through `Intl` in the
 * browser.
 */
expect class TmLocale

/** The locale for a BCP 47 tag. */
expect fun localeOf(tag: String): TmLocale

/** The language part of [locale], lower case: `es` for `es-419`. */
expect fun languageOf(locale: TmLocale): String

/**
 * What the platform's own locale data writes for us: digits and separators, dates in the
 * language's medium form, and the names of languages, scripts and regions. Everything else in
 * [LocaleFormatter] is the catalog's words around these.
 */
internal expect object PlatformText {
    /** [value] with [minFraction] to [maxFraction] fraction digits and grouping separators. */
    fun decimal(locale: TmLocale, value: Double, minFraction: Int, maxFraction: Int): String

    /** A whole number, grouped, padded with zeros to [minDigits] when [grouping] is off. */
    fun integer(locale: TmLocale, value: Long, grouping: Boolean, minDigits: Int): String

    /** [fraction] (0.62) as the language writes a percentage. */
    fun percent(locale: TmLocale, fraction: Double): String

    /** A calendar date in the medium form, in the device's time zone. */
    fun date(locale: TmLocale, epochMillis: Long): String

    /** A date in the medium form and a time in the short form. */
    fun dateTime(locale: TmLocale, epochMillis: Long): String

    /** The day and month ("6 Oct"), and with [withYear] the year too, for "watched on". */
    fun dayMonth(locale: TmLocale, epochMillis: Long, withYear: Boolean): String

    /** [iso1]'s language named in [locale], or "" when the platform has no name for it. */
    fun languageName(iso1: String, locale: TmLocale): String

    /** The script of [tag] ("zh-Hant") named in [locale], or null when the tag has none. */
    fun scriptName(tag: String, locale: TmLocale): String?

    /** The region of [tag] ("pt-BR") named in [locale], or null when the tag has none. */
    fun regionName(tag: String, locale: TmLocale): String?

    /** ISO 639-2 and 639-3 codes to their two letter ISO 639-1 form. */
    fun threeLetterCodes(): Map<String, String>

    /** [text]'s first letter in title case, as [locale] has it (Turkish dotted I). */
    fun capitalize(text: String, locale: TmLocale): String
}

/** `i18n/<tag>.json` as the platform ships it (the classpath on the JVM), or null. */
internal expect fun bundledCatalog(tag: String): String?
