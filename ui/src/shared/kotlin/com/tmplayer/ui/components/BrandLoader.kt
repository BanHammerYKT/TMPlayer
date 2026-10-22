package com.tmplayer.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.StartOffsetType
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.CompositionLocalProvider
import com.tmplayer.data.DeviceForm
import com.tmplayer.i18n.L
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.LocalPureBlack
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TmSchemes
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.delay
import kotlin.math.tan

/**
 * The wait while TMPlayer connects, the same on every platform and kept quiet (user, 2026-10-22:
 * "a splash screen if needed and nothing else, or just a simple rotating loader"): the window's
 * own ground, which the system's splash hands over to with no flash, then after [SPINNER_MS] a
 * small spinner in the accent, and only once the wait has gone on for [WORDS_MS] the words under
 * it, [label] and a second line that changes as the wait goes on ([lines]), so a slow start over
 * a poor connection does not read as a hang. A warm start is over before anything shows.
 *
 * The ground is the app's own near-black or near-white, never the wallpaper's tint, because it is
 * the colour the window was painted before the first frame (`window_background`).
 *
 * [sweepAt] is kept for tests that draw a frame at once: any value shows the spinner and the
 * words without waiting. [below] is room under the lines for a Retry once the wait has gone on
 * too long.
 */
@Composable
fun BrandLoader(
    label: String,
    modifier: Modifier = Modifier,
    lines: List<String> = connectingLines(),
    sweepAt: Float? = null,
    below: @Composable ColumnScope.() -> Unit = {},
) {
    val form = deviceForm()
    val size = LoaderSizes.of(form)

    // Cycles once through and then stays on the last line. Looping back to the first after
    // suggesting the viewer check their connection would be the app forgetting what it just said.
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(lines.size) {
        for (index in lines.indices) {
            delay(if (index == 0) FIRST_STEP_MS else STEP_MS)
            step = index
        }
    }

    val immediate = sweepAt != null
    var spinner by remember { mutableStateOf(immediate) }
    var words by remember { mutableStateOf(immediate) }
    if (!immediate) {
        LaunchedEffect(Unit) {
            delay(SPINNER_MS)
            spinner = true
            delay(WORDS_MS - SPINNER_MS)
            words = true
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(loaderGround())
            .padding(horizontal = size.side),
        contentAlignment = Alignment.Center,
    ) {
        if (!spinner) return@Box
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            val spinnerModifier = Modifier.size(size.spinner).semantics { contentDescription = label }
            if (sweepAt != null) {
                // A test's still frame: an endless spin never lets a test's clock settle.
                androidx.compose.material3.CircularProgressIndicator(
                    progress = { sweepAt.coerceIn(0f, 1f) },
                    color = Tone.accent,
                    trackColor = Color.Transparent,
                    strokeWidth = size.stroke,
                    modifier = spinnerModifier,
                )
            } else {
                androidx.compose.material3.CircularProgressIndicator(
                    color = Tone.accent,
                    trackColor = Color.Transparent,
                    strokeWidth = size.stroke,
                    modifier = spinnerModifier,
                )
            }
            if (!words) return@Column
            Spacer(Modifier.height(size.gap))
            Crossfade(label, animationSpec = tween(LINE_FADE_MS, easing = Motion.Standard), label = "loaderLabel") {
                Text(
                    it,
                    style = TextStyle(fontSize = size.label, lineHeight = size.label * 1.35f, fontWeight = FontWeight.Medium),
                    color = Tone.muted,
                    textAlign = TextAlign.Center,
                )
            }
            if (lines.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Crossfade(
                    lines[step.coerceIn(0, lines.lastIndex)],
                    animationSpec = tween(LINE_FADE_MS, easing = Motion.Standard),
                    label = "loaderLine",
                ) {
                    Text(
                        it,
                        style = TextStyle(fontSize = size.line, lineHeight = size.line * 1.45f),
                        color = Tone.muted.copy(alpha = LINE_ALPHA),
                        textAlign = TextAlign.Center,
                        // A sentence that needs two lines breaks near the middle rather than
                        // running the full width of a television and leaving three words under it.
                        modifier = Modifier.widthIn(max = 420.dp),
                    )
                }
            }
            below()
        }
    }
}

/**
 * [BrandLoader] over the window while [label] is not null, fading out over whatever is under it
 * once it is: the sign in pane or the home screen is already drawn underneath, so the loader
 * thinning to nothing over it is the cross-fade, and the one leaving never jumps. Put it last in a
 * `Box` that fills the window, or give it a `zIndex`, so it is drawn above its siblings.
 */
@Composable
fun BrandLoaderHandOff(
    label: String?,
    modifier: Modifier = Modifier,
    /** The second line's words, or null for [connectingLines]. Empty when [below] says it instead. */
    lines: List<String>? = null,
    below: @Composable ColumnScope.() -> Unit = {},
) {
    val showing = label != null
    // The last label shown, so the fading loader keeps its words rather than going blank. A plain
    // holder: it is only read in the composition that wrote it.
    val held = remember { arrayOf(label) }
    if (label != null) held[0] = label
    val alpha = remember { Animatable(if (showing) 1f else 0f) }
    LaunchedEffect(showing) {
        if (showing) alpha.snapTo(1f) else alpha.animateTo(0f, tween(EXIT_MS, easing = Motion.Standard))
    }
    val shown = held[0] ?: return
    if (!showing && alpha.value == 0f) return
    BrandLoader(
        label = shown,
        modifier = modifier.graphicsLayer { this.alpha = alpha.value },
        lines = lines ?: connectingLines(),
        below = below,
    )
}

/**
 * What the second line says, in the order the wait earns it.
 *
 * The last two are the only advice worth giving: everything this app does needs the internet, and
 * a first launch after an install has a database to build before it can talk to anybody.
 */
@Composable
fun connectingLines(): List<String> = listOf(
    L.stateConnectingSession,
    L.stateConnectingFirstLaunch,
    L.stateConnectingStillTrying,
)

/** The loader's ground: the window's own colour for this theme, whatever the wallpaper says. */
@Composable
private fun loaderGround(): Color = TmSchemes.of(LocalDarkTheme.current, LocalPureBlack.current).background

/** The loader's figures for each kind of device: the spinner larger on a television, seen from across the room. */
private class LoaderSizes(
    val spinner: androidx.compose.ui.unit.Dp,
    val stroke: androidx.compose.ui.unit.Dp,
    val label: TextUnit,
    val line: TextUnit,
    val gap: androidx.compose.ui.unit.Dp,
    val side: androidx.compose.ui.unit.Dp,
) {
    companion object {
        private val Phone = LoaderSizes(32.dp, 3.dp, 15.sp, 13.sp, 24.dp, 16.dp)
        private val Tv = LoaderSizes(44.dp, 4.dp, 18.sp, 15.sp, 30.dp, 72.dp)
        private val Desktop = LoaderSizes(36.dp, 3.dp, 17.sp, 14.sp, 30.dp, 48.dp)

        fun of(form: DeviceForm): LoaderSizes = when (form) {
            DeviceForm.Phone -> Phone
            DeviceForm.Tv -> Tv
            DeviceForm.Desktop -> Desktop
        }
    }
}

private const val LINE_FADE_MS = 500
private const val LINE_ALPHA = 0.72f
private const val EXIT_MS = 420

/** Long enough that a connection that lands immediately shows the first line and nothing else. */
private const val FIRST_STEP_MS = 1_200L
private const val STEP_MS = 5_000L

/** How long the ground stays bare: a warm start is over before the spinner would show. */
private const val SPINNER_MS = 500L

/** When the words join the spinner: long enough to be worth saying what is happening. */
private const val WORDS_MS = 4_000L
