package com.tmplayer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.tmplayer.data.Thumbnails

/*
 * Lists that compose ahead of the screen. By default a lazy list prefetches a fraction of the
 * next item and nothing while it is still; with a cache window it keeps a screen's worth of
 * tiles composed past the end it is scrolling towards (and half a screen behind), so their
 * thumbnails are already asked for and a fast flick or a held D-pad lands on tiles that are drawn
 * rather than blank. Under Lighter pictures (low memory) the window is halved.
 */

/**
 * How far ahead and behind, in screens: one ahead, half behind; half of that under Lighter
 * pictures. [column] is a list the remote moves down through a row at a time (Home's rows, a
 * grid of tiles).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun rememberAheadWindow(column: Boolean = false): LazyLayoutCacheWindow {
    val light = Thumbnails.light
    val tv = com.tmplayer.ui.theme.Motion.television
    return remember(light, tv, column) {
        // A television composes a quarter of a screen ahead along a row and nothing behind: a
        // screen ahead composed three more rows of sixteen tiles each as Home opened, and their
        // pictures and lookups queued in front of the ones on screen. Down a column it keeps the
        // next row (a little over half a screen) composed while the remote rests, and the one
        // just left: composing a row of seven tiles in the frame the remote moved cost the 1 GB
        // stick 80 to 130 ms, the one hitch left on Home (2026-10-23).
        if (tv && column) LazyLayoutCacheWindow(aheadFraction = 0.6f, behindFraction = 0.3f)
        else if (tv) LazyLayoutCacheWindow(aheadFraction = 0.25f, behindFraction = 0f)
        else if (light) LazyLayoutCacheWindow(aheadFraction = 0.5f, behindFraction = 0.25f)
        else LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 0.5f)
    }
}

/** [rememberLazyListState] that composes a screen ahead: for Home's rows and strips and long lists of tiles. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberAheadListState(
    initialFirstVisibleItemIndex: Int = 0,
    initialFirstVisibleItemScrollOffset: Int = 0,
    column: Boolean = false,
): LazyListState =
    rememberLazyListState(rememberAheadWindow(column), initialFirstVisibleItemIndex, initialFirstVisibleItemScrollOffset)

/** [rememberLazyGridState] that composes a screen ahead: for a chat's grid of videos. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberAheadGridState(initialFirstVisibleItemIndex: Int = 0, initialFirstVisibleItemScrollOffset: Int = 0): LazyGridState =
    rememberLazyGridState(rememberAheadWindow(column = true), initialFirstVisibleItemIndex, initialFirstVisibleItemScrollOffset)

/**
 * Keeps a row that rests at its start there when cards arrive in front of the first one (a newer
 * video, or the row filled in from the cache and then from the chats). A lazy list holds on to the
 * card it shows first, so without this a row loaded in two goes opens a card or more along, with
 * the newest one hidden off its start. A row the viewer has scrolled is left where they put it.
 */
@Composable
fun KeepAtStart(state: LazyListState, moved: () -> Boolean = { false }) {
    val movedNow = androidx.compose.runtime.rememberUpdatedState(moved)
    androidx.compose.runtime.LaunchedEffect(state) {
        var atStart = state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0
        androidx.compose.runtime.snapshotFlow {
            Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, state.isScrollInProgress)
        }.collect { (index, offset, scrolling) ->
            when {
                // [moved]: a row that jumps to the remote's tile at once (a television's) scrolls
                // within one frame, which this never sees as scrolling, and would send it back.
                scrolling || movedNow.value() -> atStart = false
                index == 0 && offset == 0 -> atStart = true
                atStart -> state.scrollToItem(0)
            }
        }
    }
}
