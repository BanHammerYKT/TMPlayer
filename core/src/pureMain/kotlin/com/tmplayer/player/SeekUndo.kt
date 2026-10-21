package com.tmplayer.player

import kotlin.math.abs

/**
 * "Back to 12:34": the way back from a long jump, as mpv's revert-seek has it.
 *
 * A digit pressed by mistake, a slip of the thumb on the bar or a chapter step one too far loses
 * the place, and on a stream from Telegram finding it again by hand costs a download window per
 * try. So every seek is told to this, and one that travels far enough from where the viewer was
 * puts up an offer to go straight back there, for a few seconds.
 *
 * Seeks close together are one journey: the presses of a double tap run, a burst of bar strides
 * or a few arrow keys in a row keep the place they started from, so the offer leads back to where
 * the viewer was before the first of them. While an offer stands, a nudge either side of the
 * landing keeps it (and its way back); a seek that returns to the place by hand ends it, as does
 * taking it. The seek that taking it causes is not a jump of its own.
 *
 * Pure and clocked by the caller, so phone, television and desktop share it and the tests need no
 * waiting. Not thread safe: the players call it from their main thread.
 */
class SeekUndo(
    private val minDistanceMs: Long = MIN_DISTANCE_MS,
    private val burstMs: Long = BURST_MS,
    private val offerMs: Long = OFFER_MS,
) {

    /** Go back to [backToMs]; shown until [untilMs] on the caller's clock. */
    data class Offer(val backToMs: Long, val untilMs: Long)

    private var offer: Offer? = null
    private var burstOrigin = -1L
    private var lastSeekAt = Long.MIN_VALUE
    private var returningTo = -1L

    /** The offer standing at [nowMs], or null; one past its time is dropped. */
    fun current(nowMs: Long): Offer? {
        val standing = offer ?: return null
        if (nowMs >= standing.untilMs) {
            offer = null
            return null
        }
        return standing
    }

    /**
     * A seek from [fromMs] to [toMs] happened at [nowMs]. Returns the offer standing after it,
     * which is new, the same with a later end, or null.
     */
    fun seeked(fromMs: Long, toMs: Long, nowMs: Long): Offer? {
        if (fromMs < 0 || toMs < 0) return current(nowMs)
        // The seek taking the offer asked for: no journey of its own.
        if (returningTo >= 0) {
            val ours = abs(toMs - returningTo) <= LANDING_SLACK_MS
            returningTo = -1L
            if (ours) {
                lastSeekAt = Long.MIN_VALUE
                return null
            }
        }
        val inBurst = lastSeekAt != Long.MIN_VALUE && nowMs - lastSeekAt <= burstMs
        lastSeekAt = nowMs
        val standing = current(nowMs)
        val origin = when {
            standing != null -> standing.backToMs
            inBurst && burstOrigin >= 0 -> burstOrigin
            else -> fromMs
        }
        burstOrigin = origin
        if (standing != null && abs(toMs - origin) <= LANDING_SLACK_MS) {
            // Back by hand: nothing left to offer.
            offer = null
            return null
        }
        offer = when {
            standing != null -> standing.copy(untilMs = nowMs + offerMs)
            abs(toMs - origin) >= minDistanceMs -> Offer(origin, nowMs + offerMs)
            else -> null
        }
        return offer
    }

    /**
     * Takes the offer: the place to seek back to, or null when none stands at [nowMs]. The seek
     * the caller then makes is recognised by [seeked] and offers nothing.
     */
    fun take(nowMs: Long): Long? {
        val standing = current(nowMs) ?: return null
        offer = null
        burstOrigin = -1L
        returningTo = standing.backToMs
        return standing.backToMs
    }

    /** Forgets everything: another video, or a seek that is not the viewer's (a loop's wrap). */
    fun clear() {
        offer = null
        burstOrigin = -1L
        lastSeekAt = Long.MIN_VALUE
        returningTo = -1L
    }

    companion object {
        /** A jump shorter than this is a nudge: the place is still in sight. */
        const val MIN_DISTANCE_MS = 60_000L

        /** Seeks this close together are one journey with one starting place. */
        const val BURST_MS = 2_000L

        /** How long the offer stays up after the last seek. */
        const val OFFER_MS = 6_000L

        /** A seek landing this close to a place has landed on it: keyframes are not exact. */
        const val LANDING_SLACK_MS = 2_000L
    }
}
