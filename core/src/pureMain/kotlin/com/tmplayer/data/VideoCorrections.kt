package com.tmplayer.data

import kotlin.concurrent.Volatile

import com.tmplayer.platform.Background
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * What the viewer has told the app about one file, where its name led the app astray.
 *
 * Names are all the app has to go on, and a guess is sometimes wrong: two different films with one
 * title folded into one tile, a poster for somebody else's film, a title nobody would recognise. So
 * each of those can be put right by hand, from the video's menu, and the answer is kept on this
 * device (and in a backup), never sent anywhere.
 */
data class VideoCorrection(
    /** "Not the same video": never folded together with any other file. */
    val separate: Boolean = false,
    /** "Wrong match": no poster or overview from the providers, the file's own name instead. */
    val wrongMatch: Boolean = false,
    /** "Rename": the title to read in place of the file name, for the tile and for grouping. */
    val title: String? = null,
) {
    val isEmpty: Boolean get() = !separate && !wrongMatch && title.isNullOrBlank()

    /**
     * One line per correction, so the stored value reads as what it means: `separate`, `wrong`,
     * `title=Night Train (2019)`. A title is the last line and keeps any characters it has.
     */
    fun encode(): String = buildList {
        if (separate) add(SEPARATE)
        if (wrongMatch) add(WRONG)
        title?.trim()?.takeIf { it.isNotEmpty() }?.let { add("$TITLE${it.replace('\n', ' ')}") }
    }.joinToString("\n")

    companion object {
        private const val SEPARATE = "separate"
        private const val WRONG = "wrong"
        private const val TITLE = "title="

        fun decode(text: String?): VideoCorrection {
            if (text.isNullOrBlank()) return VideoCorrection()
            val lines = text.lines()
            return VideoCorrection(
                separate = SEPARATE in lines,
                wrongMatch = WRONG in lines,
                title = lines.firstOrNull { it.startsWith(TITLE) }?.removePrefix(TITLE)?.trim()?.ifEmpty { null },
            )
        }
    }
}

/**
 * Every [VideoCorrection] on this device, by [MediaItem.id], for the code that groups and names
 * videos: [SeriesShelf] reads [current] as its default, so a correction holds on every screen that
 * arranges a chat (the grid, Home, the series pages) without each of them passing it along.
 *
 * Kept by [SettingsStore] under [PREFIX] names, one per file, which is also how a backup carries
 * them. A screen that draws an arrangement collects [state], so a correction redraws it at once.
 */
object VideoCorrections {

    /** The stored names: `fix_<chat>_<message>`. */
    const val PREFIX = "fix_"

    private val corrections = MutableStateFlow<Map<String, VideoCorrection>>(emptyMap())

    val state: StateFlow<Map<String, VideoCorrection>> = corrections.asStateFlow()

    val current: Map<String, VideoCorrection> get() = corrections.value

    @Volatile
    private var following = false

    /** Keeps [state] in step with [store], for the life of the process. Once is enough. */
    fun follow(store: CorrectionsSource) {
        if (following) return
        following = true
        Background.scope.launch { store.corrections.collect { corrections.value = it } }
    }

    /** Sets every correction at once. For tests, and for a process with no store to follow. */
    fun set(all: Map<String, VideoCorrection>) {
        corrections.value = all
    }

    /** One file's correction, after a change made here, ahead of the store's own echo. */
    internal fun put(id: String, correction: VideoCorrection) {
        corrections.value = if (correction.isEmpty) corrections.value - id else corrections.value + (id to correction)
    }

    fun of(item: MediaItem, all: Map<String, VideoCorrection> = current): VideoCorrection? = all[item.id]

    /** The longest title a rename keeps. */
    const val TITLE_MAX = 120

    /**
     * Every file a tile stands for: its copies and their pieces. A rename or a wrong match is
     * written to all of them, so it holds whichever copy the tile shows next time.
     */
    fun filesOf(item: MediaItem): List<MediaItem> =
        item.versions.ifEmpty { listOf(item) }.flatMap { copy -> listOf(copy) + copy.parts }.distinctBy { it.id }

    /** The title to show for a saved record of a video: the viewer's own where they gave one. */
    fun titleFor(chatId: Long, messageId: Long, fallback: String): String =
        current["$chatId:$messageId"]?.title ?: fallback

    /** The stored name for [chatId] and [messageId]'s correction. */
    fun keyName(chatId: Long, messageId: Long): String = "$PREFIX${chatId}_$messageId"

    /** The [MediaItem.id] a stored name stands for, or null for a name that is not one. */
    fun idOf(name: String): String? {
        if (!name.startsWith(PREFIX)) return null
        val parts = name.removePrefix(PREFIX).split('_')
        if (parts.size != 2) return null
        val chat = parts[0].toLongOrNull() ?: return null
        val message = parts[1].toLongOrNull() ?: return null
        return "$chat:$message"
    }
}

/** Where the corrections are stored: SettingsStore in the apps. */
interface CorrectionsSource {
    val corrections: kotlinx.coroutines.flow.Flow<Map<String, VideoCorrection>>
}
