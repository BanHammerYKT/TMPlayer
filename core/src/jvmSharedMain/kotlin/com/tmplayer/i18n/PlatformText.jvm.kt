package com.tmplayer.i18n

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

actual typealias TmLocale = Locale

actual fun localeOf(tag: String): TmLocale = Locale.forLanguageTag(tag)

actual fun languageOf(locale: TmLocale): String = locale.language

internal actual object PlatformText {
    // NumberFormat is not thread safe, so each call makes its own; they are cheap next to a frame.
    actual fun decimal(locale: TmLocale, value: Double, minFraction: Int, maxFraction: Int): String =
        NumberFormat.getInstance(locale).apply {
            minimumFractionDigits = minFraction
            maximumFractionDigits = maxFraction
        }.format(value)

    actual fun integer(locale: TmLocale, value: Long, grouping: Boolean, minDigits: Int): String =
        NumberFormat.getIntegerInstance(locale).apply {
            minimumIntegerDigits = minDigits
            isGroupingUsed = grouping
        }.format(value)

    actual fun percent(locale: TmLocale, fraction: Double): String =
        NumberFormat.getPercentInstance(locale).format(fraction)

    actual fun date(locale: TmLocale, epochMillis: Long): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    actual fun dateTime(locale: TmLocale, epochMillis: Long): String = dateTime(locale, epochMillis, ZoneId.systemDefault())

    fun dateTime(locale: TmLocale, epochMillis: Long, zone: ZoneId): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT).withLocale(locale)
            .format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    fun date(locale: TmLocale, epochMillis: Long, zone: ZoneId): String =
        DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)
            .format(Instant.ofEpochMilli(epochMillis).atZone(zone))

    actual fun dayMonth(locale: TmLocale, epochMillis: Long, withYear: Boolean): String =
        DateTimeFormatter.ofPattern(if (withYear) "d MMM yyyy" else "d MMM", locale)
            .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    actual fun languageName(iso1: String, locale: TmLocale): String =
        Locale.forLanguageTag(iso1).getDisplayLanguage(locale)

    actual fun scriptName(tag: String, locale: TmLocale): String? {
        val parsed = Locale.forLanguageTag(tag)
        return parsed.script.takeIf { it.isNotEmpty() }?.let { parsed.getDisplayScript(locale) }
    }

    actual fun regionName(tag: String, locale: TmLocale): String? {
        val parsed = Locale.forLanguageTag(tag)
        return parsed.country.takeIf { it.isNotEmpty() }?.let { parsed.getDisplayCountry(locale) }
    }

    actual fun threeLetterCodes(): Map<String, String> {
        val out = HashMap<String, String>()
        for (two in Locale.getISOLanguages()) {
            val three = runCatching { Locale.forLanguageTag(two).isO3Language }.getOrNull()
            if (!three.isNullOrEmpty()) out[three] = two
        }
        return out
    }

    actual fun englishLanguageNames(): Map<String, String> =
        Locale.getISOLanguages().associateBy { Locale.forLanguageTag(it).getDisplayLanguage(Locale.ENGLISH).lowercase() }

    actual fun iso3Of(iso1: String): String? = runCatching { Locale.forLanguageTag(iso1).isO3Language }.getOrNull()

    actual fun capitalize(text: String, locale: TmLocale): String =
        text.replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

/** A date in the language's medium form, in [zone] rather than the device's. */
fun LocaleFormatter.date(epochMillis: Long, zone: ZoneId): String = PlatformText.date(locale, epochMillis, zone)

/** A date and a time in the language's medium form, in [zone] rather than the device's. */
fun LocaleFormatter.dateTime(epochMillis: Long, zone: ZoneId): String = PlatformText.dateTime(locale, epochMillis, zone)

internal actual fun bundledCatalog(tag: String): String? =
    Translator::class.java.getResourceAsStream("/i18n/$tag.json")?.use { it.readBytes().decodeToString() }

internal actual fun parseCatalog(json: String): Map<String, String> = catalogFromJson(json)
