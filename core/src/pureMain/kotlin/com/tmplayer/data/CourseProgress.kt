package com.tmplayer.data

import com.tmplayer.platform.sortedByKey

/**
 * Whether a video counts as watched for a show's or a course's "12 of 40 watched": on the Watched
 * list, or its saved position past [ResumeRules.WATCHED_FRACTION]. The second is rare, since the
 * player forgets a position that far in and marks the video instead, but a position written by an
 * older build, or by a player that was closed before it could mark, still says the viewer got there.
 */
object Watching {
    fun counts(onList: Boolean, point: WatchPoint?): Boolean =
        onList || (point != null && ResumeRules.watched(point.positionMs, point.durationMs))
}

/**
 * How far through a course the viewer is: a chat or a forum topic of study videos numbered as one
 * run ("Lecture 1" to "Lecture 40", see [LessonSteps]), for the line and the bar over its listing.
 *
 * Counted by lesson number, not by file: a lecture posted twice is one lecture, watched when either
 * copy is. [next] is the first lesson not yet watched, in number order, which for a course is where
 * to carry on: a lecture skipped is a gap to go back for, not a sign the viewer has moved past it.
 */
data class CourseProgress(
    /** The course's name as its newest lesson writes it, or empty for a run named by its word alone. */
    val course: String,
    val watched: Int,
    val total: Int,
    /** The first unwatched lesson, in the copy partway through where there is one, else the newest. */
    val next: MediaItem?,
    /** "Lecture 13", what the button names, or null once every lesson is watched. */
    val nextCode: String?,
    /** Whether [next] has a saved position, so the button says Resume rather than Continue. */
    val nextStarted: Boolean,
) {
    val finished: Boolean get() = total > 0 && watched >= total

    companion object {
        /** Fewer lessons than this in a run is two clips that happen to say "Part", not a course. */
        const val MIN_LESSONS = 3

        /**
         * The course [items] make up, or null when they are not one: the longest run of lessons
         * among them, so long as it has [MIN_LESSONS] lessons and is at least half of what is
         * listed. A chat of films with a three-part interview in it is not a course.
         *
         * @param onList whether a video is on the Watched list.
         * @param point where the viewer stopped in a video, or null.
         */
        fun of(
            items: List<MediaItem>,
            onList: (MediaItem) -> Boolean,
            point: (MediaItem) -> WatchPoint?,
        ): CourseProgress? {
            if (items.size < MIN_LESSONS) return null
            val runs = items
                .mapNotNull { item -> LessonSteps.lessonOf(item)?.let { item to it } }
                .groupBy { (_, lesson) -> lesson.word to LessonSteps.key(lesson.course) }
            val run = runs.values.maxByOrNull { run -> run.map { it.second.number }.distinct().size } ?: return null
            val lessons = run.groupBy { it.second.number }.sortedByKey()
            if (lessons.size < MIN_LESSONS || run.size * 2 < items.size) return null
            fun counts(item: MediaItem) = Watching.counts(onList(item), point(item))
            val done = lessons.filterValues { copies -> copies.any { counts(it.first) } }.keys
            val next = lessons.entries.firstOrNull { it.key !in done }?.value?.let { all ->
                all.firstOrNull { (item, _) -> (point(item)?.positionMs ?: 0L) > 0L }
                    ?: all.maxBy { it.first.messageId }
            }
            val newest = run.maxBy { it.first.messageId }.second
            return CourseProgress(
                course = newest.course,
                watched = done.size,
                total = lessons.size,
                next = next?.first,
                nextCode = next?.second?.code,
                nextStarted = next != null && (point(next.first)?.positionMs ?: 0L) > 0L,
            )
        }
    }
}
