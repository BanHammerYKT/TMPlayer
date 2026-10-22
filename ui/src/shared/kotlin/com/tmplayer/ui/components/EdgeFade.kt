package com.tmplayer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection

/**
 * Fades a horizontal row out over its side margins ([start] and [end], the row's own padding), so
 * cards scrolled past the margin melt away instead of being cut off hard against the sidebar or the
 * window's edge. Each side fades only while there is something on that side to scroll back to: a
 * row at rest starts on its first card, whole. The start side comes in with the first few pixels
 * of scroll, so it never pops.
 */
fun Modifier.edgeFade(state: LazyListState, start: Dp, end: Dp, ground: Color? = null): Modifier =
    if (ground != null) groundFade(state, start, end, ground) else this
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            drawEdges(state, start, end) { brush, topLeft, size -> drawRect(brush, topLeft, size, blendMode = BlendMode.DstIn) }
        }

/**
 * [edgeFade] for a row that stands on one plain colour, [ground]: the colour is drawn over the
 * margins rather than the row cut out of an offscreen layer, which a television redrew whole on
 * every frame of every scroll.
 */
private fun Modifier.groundFade(state: LazyListState, start: Dp, end: Dp, ground: Color): Modifier = drawWithContent {
    drawContent()
    drawEdges(state, start, end, mask = false, ground = ground) { brush, topLeft, size -> drawRect(brush, topLeft, size) }
}

private fun androidx.compose.ui.graphics.drawscope.ContentDrawScope.drawEdges(
    state: LazyListState,
    start: Dp,
    end: Dp,
    mask: Boolean = true,
    ground: Color = Color.Black,
    draw: (Brush, Offset, Size) -> Unit,
) {
    val startPx = start.toPx()
    val endPx = end.toPx()
    val scrolled = if (state.firstVisibleItemIndex > 0) 1f else (state.firstVisibleItemScrollOffset / startPx.coerceAtLeast(1f)).coerceIn(0f, 1f)
    val back = if (state.canScrollBackward) scrolled else 0f
    val on = if (state.canScrollForward) 1f else 0f
    val rtl = layoutDirection == LayoutDirection.Rtl
    val left = if (rtl) on else back
    val right = if (rtl) back else on
    val leftPx = if (rtl) endPx else startPx
    val rightPx = if (rtl) startPx else endPx
    // As a mask, black keeps the row and clear removes it; drawn over, the ground covers it.
    val keep = if (mask) Color.Black else ground.copy(alpha = 0f)
    fun gone(amount: Float) = if (mask) Color.Black.copy(alpha = 1f - amount) else ground.copy(alpha = amount)
    if (left > 0f && leftPx > 0f) {
        draw(
            Brush.horizontalGradient(0f to gone(left), 1f to keep, startX = 0f, endX = leftPx),
            Offset.Zero,
            Size(leftPx, size.height),
        )
    }
    if (right > 0f && rightPx > 0f) {
        draw(
            Brush.horizontalGradient(0f to keep, 1f to gone(right), startX = size.width - rightPx, endX = size.width),
            Offset(size.width - rightPx, 0f),
            Size(rightPx, size.height),
        )
    }
}

/**
 * Keeps a card that takes focus (the remote's or the keyboard's) inside a row's side margins rather
 * than at its very edge, where [edgeFade] would fade it: the row scrolls just far enough to stand
 * the card between [start] and [end] of the row's own edges.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WithinMargins(start: Dp, end: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val startPx = with(density) { start.toPx() }
    val endPx = with(density) { end.toPx() }
    val spec = remember(startPx, endPx) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val lead = startPx
                val trail = containerSize - endPx
                return when {
                    // Wider than the room between the margins: line its leading edge up.
                    size > trail - lead -> offset - lead
                    offset < lead -> offset - lead
                    offset + size > trail -> offset + size - trail
                    else -> 0f
                }
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
