package com.tmplayer.ui.components

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PlaybackWarmup
import kotlinx.coroutines.delay

/**
 * Tells [PlaybackWarmup] that [item] is about to be played: after [PlaybackWarmup.DWELL_MS] of
 * focus (a television's remote, a keyboard) or of the pointer resting on it (the desktop), and at
 * once when a finger or a button comes down on it, before the press is even a click. Nothing is
 * consumed, so the card's own click, long press and focus handling are untouched. Null does nothing,
 * and so does a device whose warmer ignores these hints ([PlaybackWarmup.onIntent]).
 */
fun Modifier.warmUpOnIntent(item: MediaItem?): Modifier = if (item == null || !PlaybackWarmup.onIntent) this else composed {
    val current by rememberUpdatedState(item)
    var dwelling by remember { mutableStateOf(false) }
    LaunchedEffect(dwelling) {
        if (!dwelling) return@LaunchedEffect
        delay(PlaybackWarmup.DWELL_MS)
        PlaybackWarmup.warm(current)
    }
    this
        .onFocusChanged { dwelling = it.hasFocus }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    when (event.type) {
                        PointerEventType.Press -> PlaybackWarmup.warm(current)
                        PointerEventType.Enter -> dwelling = true
                        PointerEventType.Exit -> dwelling = false
                    }
                }
            }
        }
}
