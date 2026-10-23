package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalWindowInfo
import com.tmplayer.ui.components.isTouch

/**
 * The tile a video's page was opened from, for putting the remote back on it once the page
 * closes. The page is a window of its own over the list, and while it was up the list under it
 * could take its focus elsewhere (a chat's grid asking for its first tile when a page of messages
 * arrived, History's list when its first card changed), so Back landed on the first tile or the
 * top bar's picture rather than where the viewer had been (Mi TV, 2026-10-23). Home keeps its
 * place the same way after the player ([HomeFocusMemory]).
 *
 * Plain state, read only from effects: a tile's focus moving is no reason to recompose the list.
 */
@Stable
internal class TileReturn {
    private val requesters = HashMap<Any, FocusRequester>()

    /** The tile the page showing was opened from, by its list key. */
    private var opened: Any? = null

    /** The tile the remote is on, by key, or null when it is outside the list. */
    private var focused: Any? = null

    /** Set while a page is up, so its closing is told from a list that never had one. */
    private var waiting = false

    /** The page about to open was opened from the tile [key]. */
    fun open(key: Any) {
        opened = key
    }

    internal fun attach(key: Any, requester: FocusRequester) {
        requesters[key] = requester
    }

    internal fun detach(key: Any, requester: FocusRequester) {
        if (requesters[key] === requester) requesters.remove(key)
        if (focused == key) focused = null
    }

    internal fun focusChanged(key: Any, has: Boolean) {
        if (has) focused = key else if (focused == key) focused = null
    }

    internal fun pageShown() {
        if (opened != null) waiting = true
    }

    /** The tile to go back to, once, when a page was up and has closed. */
    internal fun takeReturn(): Any? {
        if (!waiting) return null
        waiting = false
        return opened.also { opened = null }
    }

    internal fun isOn(key: Any) = focused == key

    internal fun request(key: Any): Boolean {
        val requester = requesters[key] ?: return false
        return runCatching { requester.requestFocus() }.isSuccess
    }
}

@Composable
internal fun rememberTileReturn(): TileReturn = remember { TileReturn() }

/**
 * Marks this tile as one [ret] can put the remote back on, by its list [key]. Nothing on a touch
 * screen, where nothing holds a focus to put back.
 */
@Composable
internal fun Modifier.returnTile(ret: TileReturn, key: Any): Modifier {
    if (isTouch()) return this
    val requester = remember { FocusRequester() }
    DisposableEffect(ret, key) {
        ret.attach(key, requester)
        onDispose { ret.detach(key, requester) }
    }
    return this
        .focusRequester(requester)
        .onFocusChanged { ret.focusChanged(key, it.hasFocus) }
}

/**
 * Puts the remote back on the tile the page was opened from once [pageOpen] goes false and the
 * list's window has the focus again. Held for a few frames, as Home's return after the player is,
 * so a claim the list makes as it comes back (its own first focus) is put right. [scrollTo] brings
 * a tile that has scrolled out of the list back into it (false when the tile is gone, and the
 * remote is left where the list put it).
 */
@Composable
internal fun ReturnToTile(ret: TileReturn, pageOpen: Boolean, scrollTo: suspend (Any) -> Boolean) {
    if (isTouch()) return
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(ret, pageOpen, windowFocused) {
        if (pageOpen) {
            ret.pageShown()
            return@LaunchedEffect
        }
        if (!windowFocused) return@LaunchedEffect
        val key = ret.takeReturn() ?: return@LaunchedEffect
        var settled = 0
        for (frame in 0 until TILE_RETURN_FRAMES) {
            withFrameNanos { }
            if (ret.isOn(key)) {
                if (++settled >= TILE_RETURN_SETTLED_FRAMES) break
                continue
            }
            settled = 0
            if (!ret.request(key)) {
                // Scrolled out of the list (it went back to its top while the page was up).
                if (!scrollTo(key)) break
            }
        }
    }
}

/** How many frames after the page closes the tile is held against other claims. */
private const val TILE_RETURN_FRAMES = 12

/** Frames in a row the remote has to stay on the tile for it to count as back. */
private const val TILE_RETURN_SETTLED_FRAMES = 3
