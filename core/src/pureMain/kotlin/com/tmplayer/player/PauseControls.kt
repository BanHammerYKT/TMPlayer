package com.tmplayer.player

/**
 * What the controls do around a pause, the same on the phone, the television and the desktop.
 *
 * A pause, however it came (a tap or click on the picture, Space or K, the remote's OK, a media
 * key, the headphones), brings the whole controls up at once and keeps them up for as long as the
 * video stays paused: the centre button turning into play is the answer to the pause, and there is
 * no separate glyph over the picture. A resume turns the button back into pause, then the controls
 * go after the usual time; when they were down before the pause, the viewer was watching the bare
 * picture and only paused for a moment, so they go sooner.
 */
object PauseControls {

    /** How long the controls stay after a resume when they were down before the pause. */
    const val QUICK_HIDE_MS = 1_500L

    /**
     * How long the controls stay after a resume: [usualMs] (the "Hide the controls after"
     * setting, zero for never), or [QUICK_HIDE_MS] when they were down before the pause and that
     * is sooner.
     */
    fun hideAfterResumeMs(usualMs: Long, downBeforePause: Boolean): Long = when {
        usualMs <= 0L -> usualMs
        downBeforePause -> minOf(usualMs, QUICK_HIDE_MS)
        else -> usualMs
    }
}
