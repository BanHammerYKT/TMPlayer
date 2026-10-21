// The player's own types that the desktop keeps in PlayerScreen.kt, which the browser build does
// not compile (it has its own screen over the same overlay, WebPlayerScreen.kt). Copied word for
// word from desktop/src/main/kotlin/com/tmplayer/desktop/player/PlayerScreen.kt; keep them alike.
package com.tmplayer.desktop.player

import androidx.compose.ui.geometry.Offset
import com.tmplayer.data.MediaItem

/** What fills the screen besides the video. */
internal sealed interface Phase {
    data class Loading(val message: String) : Phase
    data object Playing : Phase
    /** [noRoom]: refused for space on the drive, which Free up space answers better than Try again. */
    data class Failed(val message: String, val noRoom: Boolean = false) : Phase
    data class Countdown(val next: MediaItem, val seconds: Int) : Phase
    data object Finished : Phase

    /**
     * "Still watching?", with playback stopped and the downloads held. [next] is the episode
     * autoplay was about to start, which Keep watching starts instead; [ended] is a video that had
     * already played to the end, where there is nothing to carry on with but the finished sheet.
     */
    data class StillWatching(val why: String, val next: MediaItem?, val ended: Boolean) : Phase
}

/** One flash of feedback over the picture; [id] restarts the animation for a repeat. */
internal data class Flash(val kind: Kind, val text: String, val id: Long) {
    // No play or pause glyph: a pause brings the controls up, and their centre button's own
    // change is the answer to it (see PauseControls).
    enum class Kind { SeekBack, SeekForward, Text, Volume }
}

internal enum class MenuPage { Main, Audio, Subtitles, Speed, Shape, Sleep, Options, SubtitleStyle, Bookmarks, Copies }

/** Where an open menu hangs: at the cursor for a right click, or under a button. */
internal data class MenuAt(val page: MenuPage, val anchor: Anchor, val at: Offset = Offset.Zero) {
    enum class Anchor { Cursor, Overflow, Subtitles, Audio, Speed }

    /**
     * The page Back returns to, or null on the page a button's own menu opens at: that menu has no
     * Back line, a click outside closes it.
     */
    fun parentPage(): MenuPage? = when (page) {
        MenuPage.Main -> null
        MenuPage.Audio -> if (anchor == Anchor.Audio) null else MenuPage.Main
        MenuPage.Subtitles -> if (anchor == Anchor.Subtitles) null else MenuPage.Main
        MenuPage.Speed -> if (anchor == Anchor.Speed) null else MenuPage.Main
        MenuPage.Sleep -> MenuPage.Options
        MenuPage.Options, MenuPage.Shape, MenuPage.Bookmarks, MenuPage.Copies -> MenuPage.Main
        MenuPage.SubtitleStyle -> if (anchor == Anchor.Subtitles) MenuPage.Subtitles else MenuPage.Main
    }
}

/** The episodes either side of the one playing, as PlayerMedia.kt names them on the desktop. */
typealias Episodes = com.tmplayer.data.EpisodeSteps

/**
 * The player's theme: always dark, as PlayerScreen.kt's PlayerTheme, keeping the page's text font
 * (the browser has no system font to fall back on).
 */
@androidx.compose.runtime.Composable
internal fun PlayerTheme(content: @androidx.compose.runtime.Composable () -> Unit) {
    val type = androidx.compose.material3.MaterialTheme.typography
    com.tmplayer.ui.theme.TmMaterialTheme(dark = true) {
        androidx.compose.material3.MaterialTheme(
            colorScheme = androidx.compose.material3.MaterialTheme.colorScheme,
            shapes = androidx.compose.material3.MaterialTheme.shapes,
            typography = type,
            content = content,
        )
    }
}
