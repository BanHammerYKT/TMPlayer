package com.tmplayer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.tmplayer.ui.theme.Tone

/**
 * The app's mark, drawn rather than loaded, so every platform has the same one and it can move:
 * the tile, the screen with the play triangle in it, and the progress bar along the foot with its
 * playhead, as in the launcher icon (app/src/main/res/drawable/ic_logo.xml, from art/player.svg).
 *
 * [progress] places the playhead along the bar, 0 at its start and 1 at its end; read in the draw
 * pass only, so an animation of it redraws the mark and never recomposes it. The played part of
 * the bar is the accent, so the mark follows the chosen colour.
 */
@Composable
fun LogoMark(size: Dp, modifier: Modifier = Modifier, progress: () -> Float = { RESTING }) {
    val accent = Tone.accent
    Canvas(modifier.size(size).semantics { contentDescription = BRAND_NAME }) {
        val u = this.size.width / VIEWPORT
        fun at(x: Float, y: Float) = Offset(x * u, y * u)

        // Rounded as [BrandLockup] clips the drawn mark, and the screen rounded inside it, so the
        // dark screen's corners do not stand square inside a round tile.
        drawRoundRect(TILE, Offset.Zero, this.size, CornerRadius(this.size.width * TILE_CORNER))
        drawRoundRect(SCREEN, at(64f, 64f), Size(896f * u, 682.67f * u), CornerRadius(SCREEN_CORNER * u))

        val play = Path().apply {
            moveTo(426.67f * u, 277.33f * u)
            lineTo(618.67f * u, 405.33f * u)
            lineTo(426.67f * u, 533.33f * u)
            close()
        }
        drawPath(play, INK, style = Fill)
        drawPath(play, INK, style = Stroke(width = 42.67f * u, join = StrokeJoin.Round))

        val bar = 42.67f * u
        val start = at(BAR_START, BAR_Y)
        val end = at(BAR_END, BAR_Y)
        drawLine(TRACK, start, end, strokeWidth = bar, cap = StrokeCap.Round)
        val headX = BAR_START + (BAR_END - BAR_START) * progress().coerceIn(0f, 1f)
        drawLine(accent, start, at(headX, BAR_Y), strokeWidth = bar, cap = StrokeCap.Round)
        drawCircle(INK, radius = 85.33f * u, center = at(headX, BAR_Y))
    }
}

/** Where the playhead rests when nothing moves it: where the launcher icon has it. */
const val RESTING = (640f - 128f) / (896f - 128f)

private const val VIEWPORT = 1024f

/** The tile's corner as a share of its side: the lockup's (`MARK_CORNER`) and [AppMark]'s. */
const val TILE_CORNER = 0.22f

/** The screen's corner in viewport units, as in art/player.svg. */
private const val SCREEN_CORNER = 120f
private const val BAR_START = 128f
private const val BAR_END = 896f
private const val BAR_Y = 874.67f

private val TILE = Color(0xFF1E1E28)
private val SCREEN = Color(0xFF0E0E12)
private val TRACK = Color(0xFF3A3A46)
private val INK = Color(0xFFEDEDF2)
