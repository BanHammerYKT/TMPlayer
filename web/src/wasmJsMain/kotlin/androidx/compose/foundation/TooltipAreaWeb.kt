// Compose Multiplatform's desktop TooltipArea, which Compose for Web does not have yet: the
// player's buttons name themselves by their content description, so the browser build draws the
// control alone, as ui's IconTip.wasm.kt does.
package androidx.compose.foundation

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset

interface TooltipPlacement {
    class CursorPoint(val offset: DpOffset = DpOffset.Zero) : TooltipPlacement
}

@Suppress("UNUSED_PARAMETER")
@Composable
fun TooltipArea(
    tooltip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    delayMillis: Int = 500,
    tooltipPlacement: TooltipPlacement = TooltipPlacement.CursorPoint(),
    content: @Composable () -> Unit,
) {
    Box(modifier) { content() }
}
