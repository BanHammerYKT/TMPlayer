package com.tmplayer.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PlaybackWarmup
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Tells [PlaybackWarmup] that [item] is about to be played: after [PlaybackWarmup.DWELL_MS] of
 * focus (a television's remote, a keyboard) or of the pointer resting on it (the desktop), and at
 * once when a finger or a button comes down on it, before the press is even a click. Nothing is
 * consumed, so the card's own click, long press and focus handling are untouched. Null does nothing,
 * and so does a device whose warmer ignores these hints ([PlaybackWarmup.onIntent]).
 *
 * One modifier node per tile, with no composition of its own: as a `composed` modifier every tile
 * of a grid kept remember slots, an effect and a pointer loop of its own, made again whenever the
 * tile composed.
 */
fun Modifier.warmUpOnIntent(item: MediaItem?): Modifier =
    if (item == null || !PlaybackWarmup.onIntent) this else this then WarmUpElement(item)

private class WarmUpElement(private val item: MediaItem) : ModifierNodeElement<WarmUpNode>() {
    override fun create() = WarmUpNode(item)

    override fun update(node: WarmUpNode) {
        node.item = item
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "warmUpOnIntent"
        properties["item"] = item
    }

    override fun equals(other: Any?): Boolean = other is WarmUpElement && other.item == item

    override fun hashCode(): Int = item.hashCode()
}

private class WarmUpNode(var item: MediaItem) : Modifier.Node(), PointerInputModifierNode, FocusEventModifierNode {
    private var dwelling = false
    private var dwell: Job? = null

    override fun onFocusEvent(focusState: FocusState) = dwell(focusState.hasFocus)

    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Initial) return
        when (pointerEvent.type) {
            PointerEventType.Press -> PlaybackWarmup.warm(item)
            PointerEventType.Enter -> dwell(true)
            PointerEventType.Exit -> dwell(false)
        }
    }

    override fun onCancelPointerInput() = Unit

    /** Starts the wait on the first of focus or hover, and drops it when either goes. */
    private fun dwell(on: Boolean) {
        if (on == dwelling) return
        dwelling = on
        dwell?.cancel()
        dwell = null
        if (on && isAttached) {
            dwell = coroutineScope.launch {
                delay(PlaybackWarmup.DWELL_MS)
                PlaybackWarmup.warm(item)
            }
        }
    }

    override fun onDetach() {
        // The scope's jobs end with it; the flag goes too, so a node reused by a lazy list starts clear.
        dwelling = false
        dwell = null
    }
}
