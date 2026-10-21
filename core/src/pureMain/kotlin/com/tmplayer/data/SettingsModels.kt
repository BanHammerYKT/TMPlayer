package com.tmplayer.data

/**
 * The soundtrack and the subtitles a series is watched with.
 *
 * [subtitlesOn] is separate from [textLanguage] having a value, because "off" is a real choice and
 * has to survive: without it, a viewer who turned subtitles off would get them back the moment the
 * next episode's default selection picked a track.
 */
data class TrackChoice(
    val audioLanguage: String? = null,
    val textLanguage: String? = null,
    val subtitlesOn: Boolean = false,
) {
    /** Nothing has been chosen yet, so the player's own defaults are the honest answer. */
    val empty: Boolean get() = audioLanguage == null && textLanguage == null && !subtitlesOn
}

/** How far into a video the viewer got, and how long it runs. */
data class WatchPoint(val positionMs: Long, val durationMs: Long) {
    val fraction: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

/** How resume points, Watched marks and Watch later are keyed: `<chat id>_<message id>`. */
object ProgressKeys {
    fun of(chatId: Long, messageId: Long): String = "${chatId}_$messageId"
}
