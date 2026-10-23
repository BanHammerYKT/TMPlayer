package com.tmplayer.ui.components

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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.runtime.CompositionLocalProvider
import com.tmplayer.data.DeviceForm
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
 * own ground, which the system's splash hands over to with no flash, then after [SPINNER_MS] one
 * small white spinner in the middle, and nothing else. It used to grow words under it after a few
 * seconds, which pushed the spinner up and read as a second loader taking over from the first
 * (user, 2026-10-23: "just show one loader, super subtle, white"). [label] is what a screen reader
 * says for it. A warm start is over before anything shows.
 *
 * The ground is the app's own near-black or near-white, never the wallpaper's tint, because it is
 * the colour the window was painted before the first frame (`window_background`).
 *
 * [sweepAt] is kept for tests that draw a frame at once: any value shows the spinner without
 * waiting. [below] is room under the spinner for the help once the wait has gone on too long; it
 * starts under the spinner and never moves it.
 */
@Composable
fun BrandLoader(
    label: String,
    modifier: Modifier = Modifier,
    sweepAt: Float? = null,
    below: @Composable ColumnScope.() -> Unit = {},
) {
    val form = deviceForm()
    val size = LoaderSizes.of(form)

    val immediate = sweepAt != null
    var spinner by remember { mutableStateOf(immediate) }
    if (!immediate) {
        LaunchedEffect(Unit) {
            delay(SPINNER_MS)
            spinner = true
        }
    }

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(loaderGround())
            .padding(horizontal = size.side),
        contentAlignment = Alignment.Center,
    ) {
        if (!spinner) return@BoxWithConstraints
        val spinnerModifier = Modifier.size(size.spinner).semantics { contentDescription = label }
        val ink = Tone.text.copy(alpha = SPINNER_ALPHA)
        if (sweepAt != null) {
            // A test's still frame: an endless spin never lets a test's clock settle.
            androidx.compose.material3.CircularProgressIndicator(
                progress = { sweepAt.coerceIn(0f, 1f) },
                color = ink,
                trackColor = Color.Transparent,
                strokeWidth = size.stroke,
                modifier = spinnerModifier,
            )
        } else {
            androidx.compose.material3.CircularProgressIndicator(
                color = ink,
                trackColor = Color.Transparent,
                strokeWidth = size.stroke,
                modifier = spinnerModifier,
            )
        }
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = maxHeight / 2 + size.spinner / 2 + size.gap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
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
        below = below,
    )
}

/** The loader's ground: the window's own colour for this theme, whatever the wallpaper says. */
@Composable
private fun loaderGround(): Color = TmSchemes.of(LocalDarkTheme.current, LocalPureBlack.current).background

/** The loader's figures for each kind of device: the spinner a little larger on a television, seen from across the room. */
private class LoaderSizes(
    val spinner: androidx.compose.ui.unit.Dp,
    val stroke: androidx.compose.ui.unit.Dp,
    val gap: androidx.compose.ui.unit.Dp,
    val side: androidx.compose.ui.unit.Dp,
) {
    companion object {
        private val Phone = LoaderSizes(24.dp, 2.dp, 24.dp, 16.dp)
        private val Tv = LoaderSizes(32.dp, 2.5.dp, 30.dp, 72.dp)
        private val Desktop = LoaderSizes(28.dp, 2.dp, 30.dp, 48.dp)

        fun of(form: DeviceForm): LoaderSizes = when (form) {
            DeviceForm.Phone -> Phone
            DeviceForm.Tv -> Tv
            DeviceForm.Desktop -> Desktop
        }
    }
}

private const val EXIT_MS = 420

/** White, but quiet: the spinner is the only thing on the screen and should not shout. */
private const val SPINNER_ALPHA = 0.6f

/** How long the ground stays bare: a warm start is over before the spinner would show. */
private const val SPINNER_MS = 500L
