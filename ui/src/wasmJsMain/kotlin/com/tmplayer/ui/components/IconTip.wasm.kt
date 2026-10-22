package com.tmplayer.ui.components

import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlinx.coroutines.delay

/**
 * The name of an icon-only control, on a hover: [TipBubble] over it (under it with
 * [TipSide.Below], for a control near the top of the window), with the key that does the same
 * beside the name where there is one, as the desktop's TooltipArea shows it.
 *
 * Compose for Web has no TooltipArea, so this is one: a hover held for [TIP_DELAY_MS] opens the
 * bubble in a popup that takes no input, and leaving or pressing the control closes it.
 *
 * [modifier] is the control's place in its parent: the tip's box is what the parent lays out.
 */
@Composable
fun IconTip(
    label: String,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    side: TipSide = TipSide.Above,
    content: @Composable () -> Unit,
) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    var shown by remember { mutableStateOf(false) }
    // A press is the control doing its job: the tip makes way until the pointer comes back.
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(hovered, pressed) {
        if (!hovered) pressed = false
        if (!hovered || pressed) {
            shown = false
            return@LaunchedEffect
        }
        delay(TIP_DELAY_MS)
        shown = true
    }
    Box(
        modifier
            .hoverable(hover)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.pressed }) pressed = true
                    }
                }
            },
    ) {
        content()
        if (shown) {
            val gap = with(LocalDensity.current) { TIP_GAP.roundToPx() }
            val placer = remember(side, gap) { TipPlacer(side, gap) }
            Popup(popupPositionProvider = placer, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
                TipBubble(label, shortcut)
            }
        }
    }
}

/** Puts the bubble on [side] of the control, centred on it and kept inside the window. */
private class TipPlacer(private val side: TipSide, private val gap: Int) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val centredX = anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2
        val centredY = anchorBounds.top + (anchorBounds.height - popupContentSize.height) / 2
        val (x, y) = when (side) {
            TipSide.Above -> {
                val above = anchorBounds.top - popupContentSize.height - gap
                // No room above, at the top of the window: under it instead.
                centredX to (if (above >= 0) above else anchorBounds.bottom + gap)
            }
            TipSide.Below -> {
                val below = anchorBounds.bottom + gap
                centredX to (if (below + popupContentSize.height <= windowSize.height) below else anchorBounds.top - popupContentSize.height - gap)
            }
            TipSide.End ->
                (if (layoutDirection == LayoutDirection.Rtl) anchorBounds.left - popupContentSize.width - gap else anchorBounds.right + gap) to centredY
        }
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}

/** As long as the desktop's TooltipArea waits. */
private const val TIP_DELAY_MS = 500L

private val TIP_GAP = 6.dp
