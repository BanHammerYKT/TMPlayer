package com.tmplayer.player

import com.tmplayer.i18n.L

/** One chapter mark of the file: where it starts, and its name when the file gives one. */
data class Chapter(val startMs: Long, val title: String? = null)

/**
 * Chapter navigation, the way mpv and every disc player do it: forward goes to the next mark,
 * back goes to the start of the chapter playing, or to the one before when that start was only
 * just passed.
 *
 * Shared by the desktop, where mpv reads the marks, and the Android player, where [MkvChapters]
 * does (Media3 reads none). Pure, so the arithmetic is tested rather than found out on a video
 * with twenty chapters.
 */
object Chapters {

    /**
     * Back from further into a chapter than this goes to its own start; closer, to the chapter
     * before. A second press straight after the first therefore keeps stepping back.
     */
    const val RESTART_AFTER_MS = 3_000L

    /**
     * Slack for a seek that lands a little short of a mark (mpv seeks to a keyframe), so the
     * chapter just jumped to still counts as the one playing.
     */
    const val LANDING_SLACK_MS = 1_000L

    /** The chapter playing at [positionMs], or -1 before the first mark. */
    fun at(chapters: List<Chapter>, positionMs: Long): Int =
        chapters.indexOfLast { it.startMs <= positionMs + LANDING_SLACK_MS }

    /** The chapter forward goes to, or null in the last one. */
    fun next(chapters: List<Chapter>, positionMs: Long): Int? =
        chapters.indexOfFirst { it.startMs > positionMs + LANDING_SLACK_MS }.takeIf { it >= 0 }

    /** The chapter back goes to, or null with no chapters at all (or none started yet). */
    fun previous(chapters: List<Chapter>, positionMs: Long): Int? {
        val current = at(chapters, positionMs)
        if (current < 0) return null
        val into = positionMs - chapters[current].startMs
        return if (into > RESTART_AFTER_MS) current else (current - 1).coerceAtLeast(0)
    }

    /** "Chapter 3 of 12" with its name after a colon, for the flash. */
    fun label(chapters: List<Chapter>, index: Int): String {
        val name = chapters.getOrNull(index)?.title?.trim()?.takeIf { it.isNotEmpty() && !isGeneric(it) }
        val count = L.playerChapterOf(index + 1, chapters.size)
        return if (name == null) count else L.playerChapterNamed(count, name)
    }

    /**
     * The chapter's own name for a line beside the time, or "Chapter 3 of 12" where the file has
     * none worth reading; null before the first mark.
     */
    fun labelAt(chapters: List<Chapter>, positionMs: Long): String? {
        val index = at(chapters, positionMs)
        return if (index < 0) null else label(chapters, index)
    }

    /**
     * Where the bar draws a mark: every chapter start but one at the very beginning, which would
     * only be a notch at the bar's own end. Inside [durationMs] when it is known.
     */
    fun marks(chapters: List<Chapter>, durationMs: Long): List<Long> =
        chapters.map { it.startMs }.filter { it > 0 && (durationMs <= 0 || it < durationMs) }.distinct()

    /** "Chapter 03", "Chapter 3": a name that says nothing the count does not. */
    private fun isGeneric(name: String): Boolean = Regex("(?i)chapter\\s*\\d+").matches(name)
}
