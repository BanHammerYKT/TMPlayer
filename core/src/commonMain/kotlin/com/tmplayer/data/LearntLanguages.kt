package com.tmplayer.data

import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator

/**
 * One series' remembered sound and subtitles, as Settings lists them. [key] is the stored form of
 * the series name, which is what Forget clears; [name] is the series as the player last named it,
 * or the key itself for a memory written before names were kept.
 */
data class LearntSeries(
    val key: String,
    val name: String,
    val choice: TrackChoice,
) {
    /** "Sound: Malayalam, Subtitles: English". */
    fun line(): String {
        val format = Translator.messages.formatter
        val sound = choice.audioLanguage?.let { format.trackLanguage(it) ?: it } ?: L.settingsLearntLanguagesUnset
        val subtitles = when {
            !choice.subtitlesOn -> L.settingsLearntLanguagesOff
            choice.textLanguage != null -> format.trackLanguage(choice.textLanguage) ?: choice.textLanguage
            else -> L.settingsLearntLanguagesOn
        }
        return L.settingsLearntLanguagesLine(sound = sound, subtitles = subtitles)
    }
}

/**
 * Reads the per series memory ([SettingsStore.trackChoice]) back out of the raw store, for the
 * list of what the player has learnt.
 */
object LearntLanguages {
    const val AUDIO = "audio_"
    const val TEXT = "text_"
    const val SUBS = "subs_"
    const val NAME = "series_name_"

    /** The stored names a series' memory lives under: what Forget removes. */
    fun keysOf(key: String): List<String> = listOf(AUDIO + key, TEXT + key, SUBS + key, NAME + key)

    /**
     * Every series with something learnt, by name. A series whose memory says nothing (no
     * language for either and subtitles off, which is what a film watched once leaves behind)
     * is left out: there is nothing in it to show or forget.
     */
    fun from(stored: Map<String, Any?>): List<LearntSeries> {
        val keys = stored.keys.mapNotNull { name ->
            when {
                name.startsWith(AUDIO) -> name.removePrefix(AUDIO)
                name.startsWith(TEXT) -> name.removePrefix(TEXT)
                name.startsWith(SUBS) -> name.removePrefix(SUBS)
                else -> null
            }
        }.toSet()
        return keys.mapNotNull { key ->
            val choice = TrackChoice(
                audioLanguage = (stored[AUDIO + key] as? String)?.takeIf { it.isNotBlank() },
                textLanguage = (stored[TEXT + key] as? String)?.takeIf { it.isNotBlank() },
                subtitlesOn = stored[SUBS + key] as? Boolean ?: false,
            )
            if (choice.empty) return@mapNotNull null
            val name = (stored[NAME + key] as? String)?.takeIf { it.isNotBlank() } ?: key
            LearntSeries(key, name, choice)
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
    }
}
