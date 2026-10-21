package com.tmplayer.player

import com.tmplayer.i18n.PlatformText
import com.tmplayer.platform.textCollator
import com.tmplayer.data.ResumeState
import com.tmplayer.data.TrackChoice
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.TrackCodes
import com.tmplayer.i18n.Translator

/**
 * When subtitles come on by themselves, for a video nothing has been chosen for yet.
 *
 * [OffUnlessChosen] still lets a forced track through, the few lines a film subtitles on purpose
 * (a sign, a scene in another language): those are part of the film rather than a choice.
 */
enum class SubtitleMode(val key: String, private val labelText: () -> String, private val detailText: () -> String) {
    /**
     * The default, and what the app did before these settings: a track the file marks as its
     * default comes on, which most anime and film releases rely on, and nothing else does.
     */
    AsTheVideoSays("file", { L.settingsSubtitleModeFile }, { L.settingsSubtitleModeFileDetail }),
    OffUnlessChosen("off", { L.settingsSubtitleModeOff }, { L.settingsSubtitleModeOffDetail }),
    Always("always", { L.settingsSubtitleModeAlways }, { L.settingsSubtitleModeAlwaysDetail }),
    ForeignAudio("foreign", { L.settingsSubtitleModeForeign }, { L.settingsSubtitleModeForeignDetail }),
    ;

    val label: String get() = labelText()
    val detail: String get() = detailText()

    companion object {
        fun from(key: String?): SubtitleMode = entries.firstOrNull { it.key == key } ?: AsTheVideoSays
    }
}

/**
 * The app-wide languages from Settings: what a video opens with when neither the file's own saved
 * tracks nor the series it belongs to say otherwise.
 *
 * Languages are kept normalised ([TrackLanguages.normalise]); null is "Automatic".
 */
data class TrackPreferences(
    val audioLanguage: String? = null,
    val subtitleLanguage: String? = null,
    /** "Off" picked as the subtitle language: never on by themselves, whatever [mode] says. */
    val subtitlesOff: Boolean = false,
    val mode: SubtitleMode = SubtitleMode.AsTheVideoSays,
) {
    /** The stored form of the subtitle language row: empty for Automatic, [OFF], or a code. */
    val subtitleKey: String get() = if (subtitlesOff) OFF else subtitleLanguage.orEmpty()

    /** What the audio row says is set, on every platform. */
    val audioSummary: String get() = audioLanguage?.let(TrackLanguages::name) ?: L.settingsAudioLanguageAuto

    /** What the subtitle language row says is set. */
    val subtitleSummary: String
        get() = when {
            subtitlesOff -> L.settingsSubtitleLanguageOff
            subtitleLanguage != null -> TrackLanguages.name(subtitleLanguage)
            else -> L.settingsSubtitleLanguageAuto
        }

    companion object {
        const val OFF = "off"

        /** Reads the three stored values back, anything unreadable falling back to Automatic. */
        fun from(audio: String?, subtitle: String?, mode: String?) = TrackPreferences(
            audioLanguage = TrackLanguages.normalise(audio),
            subtitleLanguage = subtitle?.takeIf { it != OFF }?.let(TrackLanguages::normalise),
            subtitlesOff = subtitle == OFF,
            mode = SubtitleMode.from(mode),
        )
    }
}

/** Which of the four places a choice came from, highest first. */
enum class TrackSource { SavedFile, Series, Settings, FileDefaults }

/** What to do about subtitles when a video opens. */
sealed interface SubtitlePlan {
    /** None, though a forced track may still come on when [allowForced]. */
    data class Off(val allowForced: Boolean = false) : SubtitlePlan

    /**
     * On, in [language] where the file has it. [fallBack] takes the file's default or first track
     * when it does not; without it the file's own pick stands.
     */
    data class On(val language: String?, val fallBack: Boolean = false) : SubtitlePlan

    /** On in [language] when the soundtrack is in none of [viewerLanguages], off otherwise. */
    data class IfForeignAudio(val language: String?, val viewerLanguages: Set<String>) : SubtitlePlan

    /** Whatever the file marks as its default. */
    data object FileDefault : SubtitlePlan
}

/** The languages a video opens with, and where each came from. */
data class TrackPlan(
    val audioLanguage: String?,
    val audioSource: TrackSource,
    val subtitles: SubtitlePlan,
    val subtitleSource: TrackSource,
)

/** One subtitle track as the pick sees it. */
data class SubtitleTrackInfo(val language: String?, val isDefault: Boolean = false, val forced: Boolean = false)

/** The outcome of [TrackRules.pickSubtitle]. */
sealed interface SubtitlePick {
    /** Leave the player's own selection alone. */
    data object Leave : SubtitlePick
    data object None : SubtitlePick
    data class Track(val index: Int) : SubtitlePick
}

/**
 * The order a video's soundtrack and subtitles are decided in, the same on every platform: the
 * file's own saved tracks (a resume puts back exactly what was playing), then what its series was
 * last watched with, then the languages in Settings, then whatever the file marks as default.
 *
 * The soundtrack and the subtitles are decided apart: a series that only ever had its audio
 * remembered still takes its subtitles from Settings.
 */
object TrackRules {
    fun plan(resume: ResumeState?, series: TrackChoice, prefs: TrackPreferences, uiLanguage: String): TrackPlan {
        val (audio, audioSource) = when {
            resume?.audioTrack != null -> TrackLanguages.normalise(resume.audioLanguage) to TrackSource.SavedFile
            series.audioLanguage != null -> TrackLanguages.normalise(series.audioLanguage) to TrackSource.Series
            prefs.audioLanguage != null -> prefs.audioLanguage to TrackSource.Settings
            else -> null to TrackSource.FileDefaults
        }
        val (subtitles, subtitleSource) = when {
            resume?.subtitleTrack != null -> if (resume.subtitlesOff) {
                SubtitlePlan.Off() to TrackSource.SavedFile
            } else {
                SubtitlePlan.On(TrackLanguages.normalise(resume.subtitleLanguage)) to TrackSource.SavedFile
            }
            // "Off" is a real choice for a series (see [TrackChoice]), so a memory with only its
            // audio set still means the viewer watched it without subtitles.
            !series.empty -> if (series.subtitlesOn) {
                SubtitlePlan.On(TrackLanguages.normalise(series.textLanguage)) to TrackSource.Series
            } else {
                SubtitlePlan.Off() to TrackSource.Series
            }
            else -> settingsPlan(prefs, uiLanguage) to TrackSource.Settings
        }
        return TrackPlan(audio, audioSource, subtitles, subtitleSource)
    }

    private fun settingsPlan(prefs: TrackPreferences, uiLanguage: String): SubtitlePlan {
        if (prefs.subtitlesOff) return SubtitlePlan.Off()
        val wanted = prefs.subtitleLanguage ?: TrackLanguages.normalise(uiLanguage)
        return when (prefs.mode) {
            SubtitleMode.AsTheVideoSays -> SubtitlePlan.FileDefault
            SubtitleMode.OffUnlessChosen -> SubtitlePlan.Off(allowForced = true)
            SubtitleMode.Always -> SubtitlePlan.On(wanted, fallBack = true)
            SubtitleMode.ForeignAudio -> SubtitlePlan.IfForeignAudio(
                wanted,
                listOfNotNull(prefs.subtitleLanguage, TrackLanguages.normalise(uiLanguage)).toSet(),
            )
        }
    }

    /**
     * Which subtitle track [plan] wants among [tracks], once the player has listed them and
     * [audioLanguage] is what is playing.
     */
    fun pickSubtitle(plan: SubtitlePlan, audioLanguage: String?, tracks: List<SubtitleTrackInfo>): SubtitlePick {
        if (tracks.isEmpty()) return SubtitlePick.Leave
        return when (plan) {
            SubtitlePlan.FileDefault -> SubtitlePick.Leave
            is SubtitlePlan.Off -> if (plan.allowForced) forcedFor(audioLanguage, tracks) else SubtitlePick.None
            is SubtitlePlan.On -> inLanguage(plan.language, tracks)?.let(SubtitlePick::Track)
                ?: if (plan.fallBack) fallBack(tracks) else SubtitlePick.Leave
            is SubtitlePlan.IfForeignAudio -> {
                val audio = TrackLanguages.normalise(audioLanguage)
                // A soundtrack with no language said is not known to be foreign, so it is left
                // without, as most home videos and lectures are.
                if (audio == null || audio in plan.viewerLanguages) {
                    forcedFor(audioLanguage, tracks)
                } else {
                    inLanguage(plan.language, tracks)?.let(SubtitlePick::Track) ?: fallBack(tracks)
                }
            }
        }
    }

    private fun inLanguage(language: String?, tracks: List<SubtitleTrackInfo>): Int? {
        language ?: return null
        val matching = tracks.withIndex().filter { TrackLanguages.same(it.value.language, language) }
        // A full track before the forced one in the same language, which carries only the signs.
        return (matching.firstOrNull { !it.value.forced } ?: matching.firstOrNull())?.index
    }

    private fun fallBack(tracks: List<SubtitleTrackInfo>): SubtitlePick {
        val at = tracks.indexOfFirst { it.isDefault && !it.forced }.takeIf { it >= 0 }
            ?: tracks.indexOfFirst { !it.forced }.takeIf { it >= 0 }
            ?: 0
        return SubtitlePick.Track(at)
    }

    private fun forcedFor(audioLanguage: String?, tracks: List<SubtitleTrackInfo>): SubtitlePick {
        val forced = tracks.withIndex().filter { it.value.forced }
        val pick = forced.firstOrNull { TrackLanguages.same(it.value.language, audioLanguage) }
            ?: forced.singleOrNull()?.takeIf { TrackLanguages.normalise(it.value.language) == null }
        return pick?.let { SubtitlePick.Track(it.index) } ?: SubtitlePick.None
    }
}

/**
 * Language codes as tracks carry them, made comparable.
 *
 * Containers write ISO 639-2 (`jpn`, `eng`, `mal`, and the bibliographic `ger` and `fre`), Media3
 * and Android write 639-1 (`ja`), subtitle files and a few muxers write a whole tag (`en-US`) or
 * even the English name. Everything is brought to the two letter code where one exists, the three
 * letter one otherwise, with the region dropped: a viewer who wants English subtitles wants them
 * whether the file says British or American.
 */
object TrackLanguages {
    private val UNDETERMINED = setOf("und", "mis", "mul", "zxx", "qaa", "xx")

    private val englishNames: Map<String, String> by lazy {
        PlatformText.englishLanguageNames()
    }

    /** The comparable code for [code], or null for none or for "undetermined". */
    fun normalise(code: String?): String? {
        val raw = code?.trim()?.replace('_', '-')?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
        val base = raw.substringBefore('-')
        if (base in UNDETERMINED) return null
        if (base.length > 3) return englishNames[raw] ?: englishNames[base]
        if (base.length < 2 || !base.all { it in 'a'..'z' }) return null
        return TrackCodes.toIso1(base)
    }

    fun same(a: String?, b: String?): Boolean {
        val left = normalise(a) ?: return false
        return left == normalise(b)
    }

    /**
     * Every spelling of [code] a file might use, for mpv's `alang` and `slang`, which compare the
     * strings as written: `de,deu,ger` for German.
     */
    fun spellings(code: String?): List<String> {
        val iso1 = normalise(code) ?: return emptyList()
        val three = if (iso1.length == 2) {
            PlatformText.iso3Of(iso1)?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        return listOfNotNull(iso1, three, TrackCodes.bibliographicOf(iso1)).distinct()
    }

    /** Languages offered beyond the app's own, for what people watch with subtitles most. */
    private val COMMON = listOf(
        "en", "ja", "ko", "zh", "hi", "ml", "ta", "te", "kn", "mr", "bn", "ur", "es", "pt", "fr",
        "de", "it", "ru", "ar", "tr", "id", "th", "vi", "pl", "uk", "fa", "nl", "sv", "he", "el",
        "ms", "fil",
    )

    /**
     * The languages the Settings rows offer: the app's UI languages and the common ones, each
     * once, in the alphabetical order of their names in the UI language.
     */
    fun choices(): List<String> {
        val codes = (Languages.tags.mapNotNull(::normalise) + COMMON).distinct()
        val locale = Translator.messages.locale
        val collator = textCollator(locale)
        return codes.sortedWith { a, b -> collator.compare(name(a), name(b)) }
    }

    /** [code]'s name in the UI language: "Japanese", "Japonais", "ജാപ്പനീസ്". */
    fun name(code: String): String = Translator.messages.formatter.trackLanguage(code) ?: code
}
