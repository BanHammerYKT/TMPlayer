package com.tmplayer.data

/**
 * A video the viewer is about to play, told early: a card held in focus for a moment on a
 * television or under the mouse on the desktop, a finger coming down on a phone. Whoever can start
 * fetching its first bytes and its index installs a [Warmer] (the playback side's stream warm-up,
 * REELBOX-PARITY "warm on card focus or press"); until one is installed this does nothing, so the
 * cards can be wired now and the warm-up land on its own.
 *
 * The same video told again within [REPEAT_MS] is told once: focus moving back and forth along a
 * row should not start the same fetch over and over.
 */
object PlaybackWarmup {

    fun interface Warmer {
        fun warm(item: MediaItem)
    }

    @Volatile
    var warmer: Warmer? = null

    /**
     * False where focus or a pointer resting on a card warms nothing (a television, see the
     * app's warmer): the cards then carry no warm-up modifier at all, rather than a focus watcher
     * and a timer per tile whose hint is thrown away.
     */
    @Volatile
    var onIntent: Boolean = true

    @Volatile
    private var last: Pair<String, Long>? = null

    /** Tells the [warmer], if there is one, that [item] is likely to be played next. */
    fun warm(item: MediaItem) {
        val target = warmer ?: return
        val now = System.currentTimeMillis()
        val before = last
        if (before != null && before.first == item.id && now - before.second < REPEAT_MS) return
        last = item.id to now
        runCatching { target.warm(item) }
    }

    /** How long a hint for one video stands before the same one is passed on again. */
    const val REPEAT_MS = 10_000L

    /** How long a card holds focus or the pointer before its video is warmed (a press warms at once). */
    const val DWELL_MS = 250L
}
