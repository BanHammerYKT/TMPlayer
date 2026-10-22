package com.tmplayer.ui.auth

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Country
import com.tmplayer.data.PhoneCountries
import com.tmplayer.ui.components.SkeletonBox
import com.tmplayer.ui.components.rememberShimmer
import com.tmplayer.ui.i18n.LocalStrings

/**
 * The number as Telegram's own apps take it: a country row, then the dial code beside the
 * national number. One model for the phone and the desktop and browser forms (the television's
 * single international field shares the rules below through [PhoneRules]).
 *
 * The country is worked out from the code typed, not the other way round, as Telegram does: a code
 * typed by hand switches the country, a code no country has shows as invalid rather than as a
 * wrong flag. Where a code is shared (+1, +7, +44, +39...), TDLib's area codes decide when they
 * can ([areaIso]), else the country already chosen or guessed is kept while it still shares the
 * code. A pick from the list or anything typed marks the entry touched, and a guess that lands
 * late never replaces it.
 */
@Stable
class PhoneEntry(dial: String = "", national: String = "", chosenIso: String? = null, touched: Boolean = false) {
    var dial by mutableStateOf(dial)
        private set
    var national by mutableStateOf(national)
        private set

    /** The country picked from the list, or the one guessed, kept while its code is the one typed. */
    var chosenIso by mutableStateOf(chosenIso)
        private set

    /** The viewer has typed, pasted or picked: from here a guess no longer fills anything in. */
    var touched by mutableStateOf(touched)
        private set

    /** The number Telegram is sent. */
    val full: String get() = "+$dial$national"

    /** The dial code field changed. A whole number pasted into it is taken apart instead. */
    fun typeDial(raw: String, countries: List<Country>) {
        touched = true
        if (take(raw, countries)) return
        dial = PhoneRules.digits(raw).take(PhoneCountries.MAX_DIAL_DIGITS)
    }

    /** The national field changed: digits only, the trunk zero dropped, capped at E.164's length. */
    fun typeNational(raw: String, countries: List<Country>) {
        touched = true
        if (take(raw, countries)) return
        national = PhoneRules.national(PhoneRules.digits(raw), dial)
    }

    /** A country picked from the list: its code goes in front, the national number stays. */
    fun pick(country: Country) {
        touched = true
        chosenIso = country.iso
        dial = country.dialCode
        national = PhoneRules.national(national, dial)
    }

    /** The guessed country, only into an entry nobody has touched. */
    fun guess(country: Country) {
        if (touched || dial.isNotEmpty() || national.isNotEmpty()) return
        chosenIso = country.iso
        dial = country.dialCode
    }

    /**
     * A whole international number ("+44 7700 900123", "0044 (7700) 900-123") into its two halves.
     * False when [raw] is not written as one, so the caller keeps it as typed.
     */
    fun take(raw: String, countries: List<Country>): Boolean {
        val (d, n) = PhoneRules.splitInternational(raw, countries) ?: return false
        dial = d
        national = PhoneRules.national(n, d)
        touched = true
        return true
    }

    /** Long enough to send, as Telegram counts it: seven digits with the code, and a known code. */
    fun ready(country: Country?): Boolean = country != null && (dial + national).length >= PhoneRules.MIN_DIGITS

    companion object {
        val Saver = listSaver<PhoneEntry, Any?>(
            save = { listOf(it.dial, it.national, it.chosenIso, it.touched) },
            restore = { PhoneEntry(it[0] as String, it[1] as String, it[2] as String?, it[3] as Boolean) },
        )
    }
}

/** The rules every sign in form applies to a number, kept out of Compose so a test can reach them. */
object PhoneRules {
    /** Telegram's own floor (Web A's MIN_NUMBER_LENGTH): seven digits, the dial code counted. */
    const val MIN_DIGITS = 7

    /** E.164 allows fifteen digits, the dial code included. */
    const val MAX_DIGITS = 15

    /**
     * Calling codes whose national numbers keep a leading 0: Italy and the Vatican's landlines,
     * San Marino, Ivory Coast, Gabon and Congo. Everywhere else a 0 in front is the trunk prefix
     * dialled at home ("07700 900123" in the UK), which the international number drops.
     */
    private val KEEPS_ZERO = setOf("39", "378", "225", "241", "242")

    /**
     * Which country a shared code means when nothing else says: the one most numbers belong to.
     */
    private val USUAL = mapOf(
        "1" to "US", "7" to "RU", "44" to "GB", "39" to "IT", "47" to "NO", "61" to "AU",
        "262" to "RE", "290" to "SH", "358" to "FI", "590" to "GP", "599" to "CW", "212" to "MA",
    )

    /**
     * The digits of [raw] in ASCII, everything else dropped. Arabic-Indic, Persian and any other
     * script's digits are numbers too, and Telegram wants them as 0 to 9.
     */
    fun digits(raw: String): String = buildString {
        for (c in raw) {
            val d = c.digitToIntOrNull() ?: continue
            append('0' + d)
        }
    }

    /**
     * [raw] split into dial code and national number when it is written internationally: "+" or
     * "00" in front, after any spaces or brackets. Null otherwise, or when no known code fits.
     */
    fun splitInternational(raw: String, countries: List<Country>): Pair<String, String>? {
        val start = raw.indexOfFirst { it == '+' || it.digitToIntOrNull() != null }
        if (start < 0) return null
        val lead = raw.substring(start)
        val plus = lead.startsWith('+')
        val all = digits(lead)
        val written = if (plus) "+$all" else all
        return PhoneCountries.splitInternational(written, countries)
    }

    /** National digits for [dial]: the trunk 0 dropped where it is one, capped at E.164's length. */
    fun national(digits: String, dial: String): String {
        val trimmed = if (dial !in KEEPS_ZERO) digits.trimStart('0') else digits
        return trimmed.take((MAX_DIGITS - dial.length).coerceAtLeast(0))
    }

    /** The countries a code reaches. */
    fun candidates(countries: List<Country>, dial: String): List<Country> =
        if (dial.isEmpty()) emptyList() else countries.filter { dial in it.dialCodes }

    /**
     * The country for [dial]: by area code ([areaIso], TDLib's answer) when the code is shared and
     * it knows, else the one already chosen while it shares the code, else the usual one. Null for
     * a code no country has, which the form shows as invalid.
     */
    fun country(countries: List<Country>, dial: String, areaIso: String?, chosenIso: String?): Country? {
        val reach = candidates(countries, dial)
        if (reach.isEmpty()) return null
        if (reach.size == 1) return reach.first()
        reach.firstOrNull { !areaIso.isNullOrEmpty() && it.iso.equals(areaIso, ignoreCase = true) }?.let { return it }
        reach.firstOrNull { !chosenIso.isNullOrEmpty() && it.iso.equals(chosenIso, ignoreCase = true) }?.let { return it }
        reach.firstOrNull { it.iso == USUAL[dial] }?.let { return it }
        return reach.firstOrNull { it.dialCode == dial } ?: reach.first()
    }

    /**
     * What the television's one international field keeps: "+" and ASCII digits, the trunk 0
     * after a known code dropped ("+44 07700..." is "+44 7700...").
     */
    fun international(raw: String, countries: List<Country>): String {
        val kept = keepInternational(raw)
        val (d, n) = PhoneCountries.splitInternational(kept, countries) ?: return kept
        return "+" + d + national(n, d)
    }
}

/**
 * The country the entry's code belongs to, asking TDLib for the area code only when the code is
 * shared, so typing "+1 204" turns the flag Canadian.
 */
@Composable
fun rememberEntryCountry(entry: PhoneEntry, countries: List<Country>): Country? {
    val shared = PhoneRules.candidates(countries, entry.dial).size > 1
    val areaIso by produceState<String?>(null, shared, entry.dial, entry.national.take(AREA_DIGITS)) {
        value = if (shared && entry.national.length >= 1) {
            runCatching { com.tmplayer.data.Td.phoneCountry(entry.dial + entry.national) }.getOrNull()
        } else {
            null
        }
    }
    return PhoneRules.country(countries, entry.dial, areaIso, entry.chosenIso)
}

/**
 * One [PhoneEntry] for a pane: empty, or [initial] (the number being changed from the code step)
 * taken apart once Telegram's country list is in.
 */
@Composable
fun rememberPhoneEntry(initial: String?, countries: List<Country>): PhoneEntry {
    val entry = rememberSaveable(saver = PhoneEntry.Saver) { PhoneEntry() }
    LaunchedEffect(countries) {
        if (initial == null || countries.isEmpty() || entry.dial.isNotEmpty() || entry.national.isNotEmpty()) return@LaunchedEffect
        entry.take("+" + PhoneRules.digits(initial), countries)
    }
    return entry
}

/**
 * Words that wait on the country guess: a shimmer block in their place while [waiting], faded
 * into [content] when it lands. The block is named for a screen reader; nothing is written on it.
 */
@Composable
fun GuessFade(
    waiting: Boolean,
    skeletonWidth: Dp,
    modifier: Modifier = Modifier,
    skeletonHeight: Dp = 18.dp,
    content: @Composable () -> Unit,
) {
    val words = LocalStrings.current.loginFindingCountry
    val spec = if (com.tmplayer.ui.theme.Motion.reduced) snap<Float>() else tween(FADE_MS)
    Crossfade(targetState = waiting, modifier = modifier, animationSpec = spec, label = "country guess") { shimmering ->
        Box(contentAlignment = Alignment.CenterStart) {
            if (shimmering) {
                SkeletonBox(
                    rememberShimmer(),
                    Modifier.size(skeletonWidth, skeletonHeight).semantics { contentDescription = words },
                )
            } else {
                content()
            }
        }
    }
}

private const val FADE_MS = 220

/** Enough of the national number for TDLib to tell area codes apart. */
private const val AREA_DIGITS = 4
