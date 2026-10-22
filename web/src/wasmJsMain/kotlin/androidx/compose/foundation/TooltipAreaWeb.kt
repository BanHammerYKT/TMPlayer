// Compose Multiplatform's desktop TooltipArea, which Compose for Web does not have yet: the
// player's buttons name themselves with it (PlayerOverlay.kt's Tip), so the browser build has one
// that behaves the same way. A hover held for [delayMillis] opens [tooltip] at the pointer, moved
// by the placement's offset, in a popup that takes no input; leaving or pressing closes it.
package androidx.compose.foundation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

interface TooltipPlacement {
    class CursorPoint(val offset: DpOffset = DpOffset.Zero) : TooltipPlacement
}

@Composable
fun TooltipArea(
    tooltip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    delayMillis: Int = 500,
    tooltipPlacement: TooltipPlacement = TooltipPlacement.CursorPoint(),
    content: @Composable () -> Unit,
) {
    var inside by remember { mutableStateOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    var cursor by remember { mutableStateOf(Offset.Zero) }
    LaunchedEffect(inside, pressed) {
        if (!inside) pressed = false
        if (!inside || pressed) {
            shown = false
            return@LaunchedEffect
        }
        delay(delayMillis.toLong())
        shown = true
    }
    Box(
        modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    when (event.type) {
                        PointerEventType.Enter -> {
                            inside = true
                            cursor = change.position
                        }
                        PointerEventType.Exit -> inside = false
                        PointerEventType.Move -> if (!shown) cursor = change.position
                        PointerEventType.Press -> pressed = true
                        else -> Unit
                    }
                }
            }
        },
    ) {
        content()
        if (shown) {
            val offset = (tooltipPlacement as? TooltipPlacement.CursorPoint)?.offset ?: DpOffset.Zero
            val density = LocalDensity.current
            val shift = with(density) { IntOffset(offset.x.roundToPx(), offset.y.roundToPx()) }
            val at = IntOffset(cursor.x.roundToInt(), cursor.y.roundToInt())
            val placer = remember(at, shift) { AtCursor(at, shift) }
            Popup(popupPositionProvider = placer, properties = PopupProperties(focusable = false, clippingEnabled = false)) {
                tooltip()
            }
        }
    }
}

/** At the pointer's place in the area, moved by the placement's offset, kept inside the window. */
private class AtCursor(private val at: IntOffset, private val shift: IntOffset) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val x = anchorBounds.left + at.x + shift.x
        val y = anchorBounds.top + at.y + shift.y
        return IntOffset(
            x.coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0)),
            y.coerceIn(0, (windowSize.height - popupContentSize.height).coerceAtLeast(0)),
        )
    }
}
