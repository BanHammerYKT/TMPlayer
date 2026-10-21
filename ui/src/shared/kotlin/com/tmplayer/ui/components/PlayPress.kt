package com.tmplayer.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * A press of Play that has not reached the player yet.
 *
 * Between the press and the player there is real work (is the file on the device, is there room,
 * a fresh file id from Telegram), which on a slow stick or a line that is still reopening takes
 * long enough for the press to look missed. While [title] is set, the Play pill of a video page
 * turns its triangle into a spinner and the shell shows a small "Starting" pill, so every press
 * answers at once, wherever it came from. The player's own loader takes over once it is open.
 */
object PlayPress {
    /** The video being started, or null when nothing is. */
    var title by mutableStateOf<String?>(null)
        private set

    /** True once the player was asked to open for the current press. */
    var opened: Boolean = false
        private set

    private var press = 0

    /** A press of Play; returns its number, for [end]. */
    fun begin(title: String): Int {
        this.title = title
        opened = false
        return ++press
    }

    /** The player is being opened: the press stays shown until the shell leaves the screen. */
    fun open() {
        opened = true
    }

    /** The press is over: the player is on screen, or nothing is going to open. */
    fun end(which: Int = press) {
        if (which != press) return
        title = null
        opened = false
    }

    val starting: Boolean get() = title != null
}
