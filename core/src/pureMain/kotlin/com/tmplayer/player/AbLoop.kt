package com.tmplayer.player

/**
 * A-B repeat: the first press marks the start, the second the end and starts the loop, the third
 * clears it. The desktop's R key and the Android player's menu line both go through [press].
 *
 * Only a whole loop is played. A start alone would loop from the end of the file back to it,
 * which is not what a viewer who has marked one point is waiting for.
 */
data class AbLoop(val startMs: Long, val endMs: Long? = null) {

    val complete: Boolean get() = endMs != null

    /**
     * Where playback at [positionMs] should jump to, or null to carry on. Only a whole loop jumps,
     * and only once the end is reached: a viewer who seeks before the start is left there, as mpv
     * leaves them, and the loop picks them up again when they play into it.
     */
    fun wrap(positionMs: Long): Long? {
        val end = endMs ?: return null
        return if (positionMs >= end && positionMs < end + RUN_PAST_MS) startMs else null
    }

    companion object {
        /** Shorter than this is a stutter, not a loop; the second press is waited out instead. */
        const val MIN_LENGTH_MS = 500L

        /**
         * How far past the end a position still counts as having played into it. Further than this
         * is a seek past the loop, which the viewer meant, so it is not pulled back.
         */
        const val RUN_PAST_MS = 2_000L

        /**
         * What the repeat key makes of [current] at [positionMs]. The two marks go in time order,
         * whichever was pressed first, and a second press too close to the first changes nothing.
         */
        fun press(current: AbLoop?, positionMs: Long): AbLoop? = when {
            current == null -> AbLoop(positionMs)
            current.endMs != null -> null
            kotlin.math.abs(positionMs - current.startMs) < MIN_LENGTH_MS -> current
            else -> AbLoop(minOf(current.startMs, positionMs), maxOf(current.startMs, positionMs))
        }
    }
}

/**
 * One frame forward or back on a paused picture, for a player that has no frame step of its own.
 *
 * mpv steps by decoded frames; Media3 can only seek, so the Android player seeks by one frame's
 * length with an exact seek. A file that does not say its frame rate is taken as 25 frames a
 * second: 40 ms is about one frame at the film and television rates (24 to 30), so the picture
 * still moves by one.
 */
object FrameStep {
    const val FALLBACK_FPS = 25f

    /** Below this or above [MAX_FPS] a container's figure is a mistake, not a frame rate. */
    private const val MIN_FPS = 1f
    private const val MAX_FPS = 240f

    /** One frame's length in milliseconds, at least one. */
    fun frameMs(fps: Float): Long {
        val rate = fps.takeIf { it.isFinite() && it in MIN_FPS..MAX_FPS } ?: FALLBACK_FPS
        return (1000f / rate).toLong().coerceAtLeast(1L)
    }

    /** Where a step from [positionMs] lands, kept inside the video when its length is known. */
    fun target(positionMs: Long, durationMs: Long, fps: Float, forward: Boolean): Long {
        val step = frameMs(fps)
        val moved = if (forward) positionMs + step else positionMs - step
        val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
        return moved.coerceIn(0L, upper)
    }
}
