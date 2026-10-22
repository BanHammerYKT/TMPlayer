package com.tmplayer.player

/**
 * Where subtitles stand while the transport row is up: how far they climb, and whether they give
 * way. All in pixels, measured in the player's root with the subtitles at rest.
 *
 * They climb at least far enough that their lowest line clears the row's top line, and on a phone
 * as far as the row reaches over the picture, as before. They never climb into the centre
 * buttons: [room] is how far they can go before the lines kept free under those buttons would
 * reach them. Only when even the least climb that clears the row does not fit under the buttons do
 * the subtitles fade while the row is up, never while a track picker is open (the subtitles behind
 * it are what its look lines preview).
 *
 * A television used to fade them whenever the climb could not reach the row's full height, and
 * its row is up for the first seconds of every video and the whole of a pause, so the first lines
 * and a paused frame showed none (2026-10-22). It now climbs only as far as clearing the row
 * takes, which its picture has room for.
 */
internal object SubtitleLift {

    data class Place(val lift: Int, val hidden: Boolean)

    /**
     * [textBottom] is where the lowest line draws at rest, [rowTop] the row's top line (null
     * when none of it is showing), [reach] the phone's climb (the row's height over the picture),
     * [room] the climb the centre buttons allow (null when they are not showing), [gap] the space
     * kept between the lines and the row. A television climbs only as far as clearing the row
     * takes ([tv]); a phone the whole [reach] where there is room for it.
     */
    fun place(
        controlsUp: Boolean,
        pickerOpen: Boolean,
        textBottom: Int,
        rowTop: Int?,
        reach: Int,
        room: Int?,
        gap: Int,
        tv: Boolean,
    ): Place {
        if (!controlsUp) return Place(0, hidden = false)
        val needed = rowTop?.let { (textBottom + gap - it).coerceAtLeast(0) } ?: 0
        val wanted = if (tv) needed else maxOf(reach.coerceAtLeast(0), needed)
        if (room == null) return Place(wanted, hidden = false)
        val lift = maxOf(needed, minOf(wanted, room.coerceAtLeast(0)))
        return Place(lift, hidden = !pickerOpen && room < needed)
    }
}
