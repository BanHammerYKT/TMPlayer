package com.tmplayer.player

import com.tmplayer.data.EpisodeNeighbours
import com.tmplayer.data.LessonSteps
import com.tmplayer.data.MediaItem
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Where a speed the viewer picks is remembered, and which remembered speed a video opens at.
 *
 * Somebody working through a course at 1.5x wants every lecture of it at 1.5x without saying so
 * each time, and the same viewer watching a film afterwards wants it at 1x. So a speed belongs to
 * what the video is part of rather than to the app: the show, for an episode; the course, for a
 * numbered lecture; otherwise the chat, or the one forum topic in a group that has topics, since a
 * study group's topics are its subjects.
 *
 * A video opens at, in order: its own saved speed (kept with its resume point, see [ResumeState]),
 * the series' or the course's, the chat's or the topic's, and otherwise 1x. Choosing 1x forgets
 * the speed everywhere this video would have read one, so the next one opens at 1x too rather than
 * finding a wider memory underneath.
 *
 * Pure, so the order is tested rather than discovered half way through a semester.
 */
object SpeedMemory {

    /**
     * The keys one video's speed is filed under, each null where it has none.
     *
     * [series] is for a show or a course, [chat] for the chat or its topic. A new choice is written
     * to [series] when there is one and to [chat] otherwise, the narrowest place it belongs.
     */
    data class Scopes(val series: String? = null, val chat: String? = null) {
        /** Where a newly picked speed is written. */
        val target: String? get() = series ?: chat

        /** Every key the video reads from, narrowest first. */
        val all: List<String> get() = listOfNotNull(series, chat)
    }

    /**
     * Where [item]'s speed belongs.
     *
     * [seriesKey] is what the track choice is filed under ([com.tmplayer.data.SettingsStore.trackChoice]),
     * passed in so the two memories of one show always agree on its name. It is used only when the
     * video reads as an episode; a lecture is filed under its course instead, because the track
     * key of "Physics Lecture 5" is that lecture's own name and would remember nothing for lecture 6.
     */
    fun scopesOf(item: MediaItem, seriesKey: String): Scopes {
        val lesson = LessonSteps.lessonOf(item)
        val show = EpisodeNeighbours.showOf(item)?.takeIf { lesson == null || it != lesson.searchFor }
        val series = when {
            show != null -> seriesKey.trim().takeIf { it.isNotEmpty() }?.let { SERIES + it }
            lesson != null -> lesson.course.trim().takeIf { it.isNotEmpty() }
                ?.let { COURSE + lesson.word + ":" + it }
            else -> null
        }
        return Scopes(series = series, chat = chatScope(item.chatId, item.topicId))
    }

    /** The chat's key, or the topic's in a group with topics; null for a file not from a chat. */
    fun chatScope(chatId: Long, topicId: Int): String? = when {
        chatId == 0L -> null
        topicId != 0 -> "$CHAT$chatId:$topicId"
        else -> "$CHAT$chatId"
    }

    /**
     * The speed to open at: [file], else [series], else [chat], else [PlaybackSpeed.DEFAULT].
     * A value that cannot be a speed is passed over as if it were not there.
     */
    fun resolve(file: Float?, series: Float?, chat: Float?): Float =
        listOf(file, series, chat).firstNotNullOfOrNull(::usable) ?: PlaybackSpeed.DEFAULT

    /**
     * What to write for a newly picked [value]: the speed, kept to two decimals and within what a
     * player can do, or null for 1x, which is written as nothing at all.
     */
    fun toKeep(value: Float): Float? = usable(value)?.takeIf { !isNormal(it) }

    /** Whether [value] is 1x, give or take the rounding of a float written to disk. */
    fun isNormal(value: Float): Boolean = abs(value - PlaybackSpeed.DEFAULT) < 0.005f

    private fun usable(value: Float?): Float? =
        value?.takeIf { !it.isNaN() && it > 0f }
            ?.let { (it * 100).roundToInt() / 100f }
            ?.coerceIn(PlaybackSpeed.MIN, PlaybackSpeed.MAX)

    private const val SERIES = "series:"
    private const val COURSE = "course:"
    private const val CHAT = "chat:"
}
