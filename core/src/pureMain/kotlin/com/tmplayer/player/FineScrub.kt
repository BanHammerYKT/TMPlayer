package com.tmplayer.player

import com.tmplayer.i18n.L
import kotlin.math.abs

/**
 * Fine scrubbing, the way YouTube's phone app does it: while a finger drags along the bar, or
 * swipes sideways over the picture to seek, taking it further from where it started (up or down)
 * slows the travel to a half and then a quarter, so a few seconds can be found on a two hour video
 * without letting go.
 *
 * Measured in dp, so a finger means the same on every screen; the activity converts. Pure, so the
 * bands and the accumulation are tested rather than felt for on a sofa.
 */
object FineScrub {

    /** Closer than this to where the drag started (or to the bar), the travel is at full speed. */
    const val HALF_FROM_DP = 56f

    /** Further than this, a quarter. */
    const val QUARTER_FROM_DP = 128f

    /** The rate for a finger [offsetDp] away from the line it is scrubbing along, either side. */
    fun rate(offsetDp: Float): Float {
        val away = abs(offsetDp)
        return when {
            away >= QUARTER_FROM_DP -> 0.25f
            away >= HALF_FROM_DP -> 0.5f
            else -> 1f
        }
    }

    /** The small line over the scrub that names a slowed rate; null at full speed. */
    fun hint(rate: Float): String? = when {
        rate <= 0.25f -> L.playerScrubQuarter
        rate < 1f -> L.playerScrubHalf
        else -> null
    }

    /**
     * Where a fine drag has got to, along the line it scrubs. Fed every move with the finger's
     * position; the travel since the last move is scaled by the rate for where the finger is now,
     * so slowing down part way does not jump the position back, and speeding up again carries on
     * from where the slow part left it.
     *
     * [x] is in whatever unit the caller works in (pixels on a bar, pixels over the picture);
     * [offsetDp] is the finger's distance from the line, in dp.
     */
    class Tracker(startX: Float) {
        private var lastX = startX

        /** The position along the line the scrub stands at, in the caller's unit. */
        var x: Float = startX
            private set

        /** The rate the last move was taken at. */
        var rate: Float = 1f
            private set

        fun move(fingerX: Float, offsetDp: Float): Float {
            rate = rate(offsetDp)
            x += (fingerX - lastX) * rate
            lastX = fingerX
            return x
        }

        /** Keeps [x] inside a line from [from] to [to], so a drag past an end does not bank travel. */
        fun clamp(from: Float, to: Float) {
            x = x.coerceIn(from, to)
        }
    }
}
