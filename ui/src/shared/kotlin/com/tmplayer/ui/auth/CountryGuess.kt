package com.tmplayer.ui.auth

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.tmplayer.data.Country
import com.tmplayer.data.PhoneCountries
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The country a sign in's number starts on, filled in before the viewer types, the same way on
 * the phone, the television, the desktop and the browser.
 *
 * Telegram's own guess comes first: it reads the address the connection comes from. The device's
 * answer is the fallback (the SIM and the network on Android, the locale on a computer, the
 * browser's language region on the web). None of these needs a permission: a guess that would
 * have to ask for one, such as the browser's location, is not worth a prompt on the first screen.
 */
object CountryGuess {
    /**
     * How long the number waits for the guess, from the pane appearing: Telegram's country list and
     * its answer together. Past this the field opens with whatever was found, or "+" alone.
     */
    const val LIMIT_MS = 3_000L

    /**
     * The country to start on, or null when nothing could be found in time.
     *
     * [countries] suspends until Telegram's list is in. Telegram's guess shares [limitMs] with the
     * list; when it is late or blank, [localIso] still answers as long as the list arrived.
     */
    suspend fun country(
        countries: suspend () -> List<Country>,
        telegramIso: suspend () -> String,
        localIso: () -> String,
        limitMs: Long = LIMIT_MS,
    ): Country? {
        var list: List<Country> = emptyList()
        val fromTelegram = withTimeoutOrNull(limitMs) {
            list = countries()
            try {
                telegramIso()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ""
            }
        }.orEmpty()
        if (list.isEmpty()) return null
        return PhoneCountries.byIso(list, fromTelegram.trim())
            ?: PhoneCountries.byIso(list, runCatching(localIso).getOrDefault("").trim())
    }

    /**
     * The first region named in a list of language tags: "IN" for "en-IN", "TW" for "zh-Hant-TW".
     * A numeric region ("es-419", Latin America) names no one country and is passed over.
     */
    fun regionOf(tags: List<String>): String = tags.firstNotNullOfOrNull { tag ->
        tag.trim().split('-', '_').drop(1).firstOrNull { it.length == 2 && it.all(Char::isLetter) }?.uppercase()
    }.orEmpty()
}

/**
 * Fills in the country through [onFound] and says whether the guess is still running, so the
 * number field can stay closed until it is: typing never races a code being put in front of it.
 *
 * [wanted] is read once, when the pane first appears: false when a number is being changed from
 * the code step, or anything is already in the field, and then nothing is guessed or held back.
 */
@Composable
fun rememberCountryGuess(
    countries: List<Country>,
    wanted: Boolean,
    localIso: () -> String,
    onFound: (Country) -> Unit,
): Boolean {
    var finished by rememberSaveable { mutableStateOf(!wanted) }
    val latest by rememberUpdatedState(countries)
    val found by rememberUpdatedState(onFound)
    val local by rememberUpdatedState(localIso)
    LaunchedEffect(Unit) {
        if (finished) return@LaunchedEffect
        CountryGuess.country(
            countries = { snapshotFlow { latest }.first { it.isNotEmpty() } },
            telegramIso = { com.tmplayer.data.Td.guessedCountryCode() },
            localIso = { local() },
        )?.let { found(it) }
        finished = true
    }
    return !finished
}
