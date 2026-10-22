package com.tmplayer.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.tmplayer.data.StorageChart
import com.tmplayer.data.StorageChart.Part
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.Tone
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * What the device holds, as a small ring: the phone, the television and the desktop draw the same
 * one beside "TMPlayer uses" at the top of the Downloads page and in Settings' Storage (the pie
 * the user asked for on 2026-10-18, kept small since 2026-10-22).
 *
 * The ring used to stand large with a legend of six lines, each a button that cleared its part.
 * That legend said again what Free up space's review lists, group by group, with the same
 * colours, and its buttons were a second way to do what the review does, so it went: the ring
 * keeps the proportions at a glance and the review says the figures.
 *
 * TMPlayer's own parts take the accent family, strongest first, and the device's other apps and
 * its free space are quiet greys, so what TMPlayer can give back stands out. It sweeps in once
 * when it first has figures; with reduced motion it is simply there.
 */
@Composable
internal fun StorageRing(
    figures: StorageChart.Figures,
    measured: Boolean,
    size: Dp,
) {
    val s = LocalStrings.current
    val reduced = rememberLessMotion()
    val slices = remember(figures) { StorageChart.slices(figures) }
    // Swept in once, the first time there is something to draw, and never again on a refresh:
    // figures moving under a ring that redraws itself from nothing would read as a reload.
    val sweep = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(slices.isNotEmpty()) {
        if (slices.isEmpty()) return@LaunchedEffect
        if (reduced) sweep.snapTo(1f) else sweep.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val colours = Part.entries.associateWith { storagePartColor(it) }
    val track = Tone.surfaceHigh
    val description = s.downloadsChartCentreDescription(
        size = s.formatter.bytes(figures.app),
        total = s.formatter.bytes(figures.whole),
    )
    Canvas(Modifier.size(size).semantics { contentDescription = description }) {
        val outer = this.size.minDimension / 2f
        val ringInner = outer * HOLE
        if (slices.isEmpty() || !measured) {
            drawSegment(0f, 360f, ringInner, outer, 0f, 0f, track)
            return@Canvas
        }
        val p = sweep.value
        val gap = GAP.toPx()
        val corner = CORNER.toPx()
        // The part not swept yet is the empty track, so the ring is never a blank space, not even
        // on the first frame.
        if (p < 1f) drawSegment(360f * p, 360f * (1f - p), ringInner, outer, if (p > 0f) gap else 0f, corner, track)
        for (slice in slices) {
            drawSegment(
                startDeg = slice.startDeg * p,
                sweepDeg = slice.sweepDeg * p,
                rIn = ringInner,
                rOut = outer,
                gapPx = if (slices.size > 1) gap else 0f,
                cornerPx = corner,
                colour = colours.getValue(slice.part),
            )
        }
    }
}

/** The colour of [part], from the theme: the accent family for TMPlayer's parts, greys for the rest. */
@Composable
fun storagePartColor(part: Part): Color {
    val cs = MaterialTheme.colorScheme
    val ground = Tone.surface
    val dark = LocalDarkTheme.current
    // Two accent families in steps of lightness, and two greys that step down to the card: the
    // used rest of the device a mid grey, the free space barely above the card, an empty track.
    return when (part) {
        Part.Downloads -> cs.primary
        Part.Unfinished -> lerp(cs.primary, ground, 0.45f)
        Part.Recent -> cs.tertiary
        Part.Unmatched -> lerp(cs.tertiary, ground, 0.38f)
        Part.Pictures -> if (dark) cs.secondary else lerp(cs.secondary, ground, 0.3f)
        Part.OtherApps -> if (dark) lerp(cs.outline, ground, 0.55f) else cs.outlineVariant
        Part.Free -> cs.surfaceContainerHighest
    }
}

/** The name of [part], as the review lists it and a screen reader says it. */
@Composable
fun storagePartName(part: Part): String {
    val s = LocalStrings.current
    return when (part) {
        Part.Downloads -> s.downloadsPartDownloads
        Part.Unfinished -> s.downloadsPartUnfinished
        Part.Recent -> s.downloadsPartRecent
        Part.Unmatched -> s.downloadsPartUnmatched
        Part.Pictures -> s.downloadsPartPictures
        Part.OtherApps -> s.downloadsPartOtherApps
        Part.Free -> s.downloadsPartFree
    }
}

/**
 * One slice: an annular sector with softly rounded corners and a gap either side.
 *
 * Built inset by [cornerPx] and then stroked [cornerPx] wide with round joins in its own colour,
 * which brings it back out to its true edges with every corner rounded, on Android and Skia
 * alike. A slice too narrow for its corners gives them up rather than vanishing.
 */
private fun DrawScope.drawSegment(
    startDeg: Float,
    sweepDeg: Float,
    rIn: Float,
    rOut: Float,
    gapPx: Float,
    cornerPx: Float,
    colour: Color,
) {
    if (sweepDeg <= 0f || rOut <= rIn) return
    val centre = Offset(size.width / 2f, size.height / 2f)
    if (sweepDeg >= 359.99f && gapPx == 0f) {
        val mid = (rIn + rOut) / 2f
        drawCircle(colour, radius = mid, center = centre, style = Stroke(width = rOut - rIn))
        return
    }
    val sweepRad = sweepDeg * PI.toFloat() / 180f
    var c = min(cornerPx, (rOut - rIn) * 0.3f)
    fun span(r: Float, corner: Float): Float = sweepRad - 2f * (gapPx / 2f + corner) / r
    if (span(rIn + c, c) <= 0f) c = 0f
    val ri = rIn + c
    val ro = rOut - c
    val innerSpan = span(ri, c).coerceAtLeast(0f)
    val outerSpan = span(ro, c)
    if (outerSpan <= 0f) return
    val startRad = startDeg * PI.toFloat() / 180f
    val o0 = startRad + (sweepRad - outerSpan) / 2f
    val i1 = startRad + sweepRad - (sweepRad - innerSpan) / 2f
    fun at(r: Float, a: Float) = Offset(centre.x + r * sin(a), centre.y - r * cos(a))
    // Compose measures arcs from three o'clock; the chart measures from twelve.
    fun composeDeg(a: Float) = a * 180f / PI.toFloat() - 90f
    val path = Path().apply {
        val first = at(ro, o0)
        moveTo(first.x, first.y)
        arcTo(Rect(centre, ro), composeDeg(o0), outerSpan * 180f / PI.toFloat(), false)
        val back = at(ri, i1)
        lineTo(back.x, back.y)
        arcTo(Rect(centre, ri), composeDeg(i1), -innerSpan * 180f / PI.toFloat(), false)
        close()
    }
    drawPath(path, colour)
    if (c > 0f) drawPath(path, colour, style = Stroke(width = 2f * c, join = StrokeJoin.Round))
}

/** How much of the ring's width is hole: a small ring keeps a thicker band, so slices stay visible. */
private const val HOLE = 0.58f

/** The space between two slices. */
private val GAP = androidx.compose.ui.unit.Dp(1.5f)

/** How round a slice's corners are. */
private val CORNER = androidx.compose.ui.unit.Dp(2f)
