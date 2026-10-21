package com.tmplayer.data

import dev.g000sha256.tdl.dto.CountryInfo
import java.util.Locale

/**
 * One row of the country picker.
 *
 * A trimmed [CountryInfo]. Kept out of the UI package because the matching below is ordinary
 * logic that a test can reach, and in :core so the phone, the TV and the desktop share it.
 */
data class Country(
    /** ISO 3166-1 alpha-2, as Telegram spells it: "IN", "GB". */
    val iso: String,
    val name: String,
    /** Every code that reaches this country, most common first. */
    val dialCodes: List<String>,
) {
    /** The one shown in the field. Countries with several codes are led by their usual one. */
    val dialCode: String get() = dialCodes.firstOrNull().orEmpty()

    val flag: String get() = flagEmoji(iso)
}

object PhoneCountries {

    /** Telegram's list, in the shape the picker wants, sorted the way a reader scans it. */
    fun from(countries: List<CountryInfo>): List<Country> = countries
        .filterNot { it.isHidden }
        .map { Country(it.countryCode, it.name, it.callingCodes.toList()) }
        .filter { it.dialCode.isNotEmpty() }
        .sortedBy { it.name.lowercase(Locale.ROOT) }

    /**
     * Whether a country answers to what has been typed in the search box.
     *
     * People look for "India" and people look for "91", so a query is tried against the name and
     * against the codes. A leading plus is ignored, since typing one is the natural thing to do.
     */
    fun matches(country: Country, query: String): Boolean {
        val q = query.trim().removePrefix("+").lowercase(Locale.ROOT)
        if (q.isEmpty()) return true
        if (country.name.lowercase(Locale.ROOT).contains(q)) return true
        if (country.iso.lowercase(Locale.ROOT) == q) return true
        return country.dialCodes.any { it.startsWith(q) }
    }

    /** The country whose dial code the user has typed, longest code first so "1" loses to "1242". */
    fun byDialCode(countries: List<Country>, dial: String): Country? {
        val digits = dial.filter { it.isDigit() }
        if (digits.isEmpty()) return null
        return countries.filter { it.dialCodes.contains(digits) }.minByOrNull { it.name }
    }

    fun byIso(countries: List<Country>, iso: String): Country? =
        countries.firstOrNull { it.iso.equals(iso, ignoreCase = true) }

    /**
     * A whole international number, pasted or autofilled into a field that holds only part of one,
     * taken apart into its dial code and the national number after it.
     *
     * Null when [raw] is not written as an international number (no "+" or "00" in front), since
     * then the digits are the national number already and belong where they were typed. The dial
     * code is the longest one Telegram knows that the digits begin with, so "+1242..." is the
     * Bahamas rather than "+1" and a stray "242". With no country list yet the longest guess is
     * not possible, and the digits are left whole as the national number under no code at all.
     */
    fun splitInternational(raw: String, countries: List<Country>): Pair<String, String>? {
        val trimmed = raw.trim()
        val digits = trimmed.filter { it.isDigit() }
        val international = trimmed.startsWith('+') || (trimmed.startsWith("00") && digits.length > 6)
        if (!international || digits.isEmpty()) return null
        val body = if (trimmed.startsWith('+')) digits else digits.removePrefix("00")
        val codes = countries.flatMap { it.dialCodes }.toSet()
        for (take in minOf(MAX_DIAL_DIGITS, body.length - 1) downTo 1) {
            val dial = body.take(take)
            if (dial in codes) return dial to body.drop(take)
        }
        return null
    }

    /** The longest dial code Telegram issues ("1242", "1868"). */
    const val MAX_DIAL_DIGITS = 4
}

/**
 * A national number drawn grouped as it is written in its country, without the grouping ever
 * being typed into the field.
 *
 * The field holds digits and nothing else: a space written into the text to the left of a still
 * caret moves it, and some keyboards then jump further. So the grouping is applied when drawing,
 * through [format] and the two offset maps a visual transformation needs.
 *
 * The pattern is TDLib's own ([Td.formatPhone]): the national number laid out with the separators
 * its country uses and a "-" for every digit still to come. Without one the digits are shown
 * as they are.
 */
object PhoneFormat {

    /**
     * [digits] laid onto [pattern]: each digit takes the next digit or "-" slot, the separators
     * between them are kept, and anything after the last digit typed is dropped so no dangling
     * space trails the caret. Digits beyond the pattern are appended as they are.
     */
    fun format(digits: String, pattern: String?): String {
        if (pattern.isNullOrEmpty() || digits.isEmpty()) return digits
        val out = StringBuilder()
        var used = 0
        for (ch in pattern) {
            if (used == digits.length) break
            if (ch.isDigit() || ch == '-') {
                out.append(digits[used++])
            } else {
                out.append(ch)
            }
        }
        if (used < digits.length) out.append(digits, used, digits.length)
        return out.toString()
    }

    /**
     * A whole international number, "+" and digits as held in the television's one field, drawn
     * as "+44 7700 900123": the dial code Telegram knows it by, a space, and the rest laid onto
     * [pattern]. Left as it is when no dial code is recognised.
     */
    fun formatInternational(raw: String, countries: List<Country>, pattern: String?): String {
        val (dial, national) = PhoneCountries.splitInternational(raw, countries) ?: return raw
        return if (national.isEmpty()) "+$dial" else "+$dial ${format(national, pattern)}"
    }

    /** Where the character at [offset] in the raw text (digits, and a leading "+") lands in [formatted]. */
    fun toFormatted(offset: Int, formatted: String): Int {
        if (offset <= 0) return 0
        var seen = 0
        for ((i, ch) in formatted.withIndex()) {
            if (ch.isDigit() || ch == '+') {
                seen++
                if (seen == offset) return i + 1
            }
        }
        return formatted.length
    }

    /** The other way: a caret at [offset] in [formatted] stands after this many raw digits. */
    fun toRaw(offset: Int, formatted: String): Int =
        formatted.take(offset.coerceIn(0, formatted.length)).count { it.isDigit() || it == '+' }
}

/**
 * The flag for an ISO code, built out of regional indicator letters.
 *
 * No flag image to ship: the two letters of the country code map onto the two code points the
 * platform's own emoji font draws as that flag.
 */
fun flagEmoji(iso: String): String {
    val code = iso.trim().uppercase(Locale.ROOT)
    if (code.length != 2 || code.any { it !in 'A'..'Z' }) return ""
    val builder = StringBuilder()
    for (letter in code) builder.appendCodePoint(REGIONAL_INDICATOR_A + (letter - 'A'))
    return builder.toString()
}

private const val REGIONAL_INDICATOR_A = 0x1F1E6
