package com.tmplayer.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.delay
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.rememberReducedMotion

/**
 * The small answers a touch screen gives back: a button that sinks under the finger, an icon that
 * turns into the next one rather than blinking, a star that pops as it fills. Only a scale and an
 * alpha move, so nothing reflows, and with the system's animations off (or the app's Reduce
 * motion) nothing scales: colours still cross-fade, since a fade is not motion.
 */
object Micro {
    /** How far an icon-only button sinks under a finger. */
    const val PressScale = 0.92f

    /** How far a wide pill (Play, Resume) sinks: less, since a wide thing moves further at its ends. */
    const val PillPressScale = 0.97f

    /** How far the star grows as it fills, before it settles back. */
    const val PopScale = 1.25f

    /** How far the star shrinks as it empties. */
    const val UnpopScale = 0.85f

    /** The spring a pressed button comes back up on: a touch of bounce, quickly settled. */
    fun <T> release() = spring<T>(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)

    /** The scale for a press, [pressed] or not, under [reduced] or not. */
    fun pressTarget(pressed: Boolean, reduced: Boolean, depth: Float = PressScale): Float = if (pressed && !reduced) depth else 1f

    /** Between one block of a page rising in and the next, so they arrive in reading order. */
    const val StaggerMs = 40

    /** The most blocks that wait their turn: anything later arrives with the last of them. */
    const val StaggerMax = 6

    /** How far a block rises as it fades in. */
    val Rise = 12.dp

    /** How long [index] waits before rising in. */
    fun staggerDelay(index: Int): Long = (index.coerceIn(0, StaggerMax) * StaggerMs).toLong()

    /**
     * The viewer's "Vibrate on tap" (TouchPrefs.haptics), set by the app from the stored choice.
     * The system's own touch feedback switch is honoured on top of it by the platform.
     */
    @Volatile
    var haptics: Boolean = true
}

/**
 * A light tick for a toggle under the finger (the star, Watched, Watch later), on when [Micro.haptics]
 * is: the answer a switch gives the hand as well as the eye. Call the result with true for
 * something turned on and false for something turned off. Nothing on a television or a desktop.
 */
@Composable
fun rememberToggleTick(): (Boolean) -> Unit {
    val play = rememberHaptics()
    return remember(play) { { on: Boolean -> play(if (on) Haptic.ToggleOn else Haptic.ToggleOff) } }
}

/**
 * A light tick for a step the hand crosses: a pull past the point where letting go refreshes, a
 * choice taken in a list. Off with [Micro.haptics], and off away from a touch screen.
 */
@Composable
fun rememberStepTick(): () -> Unit {
    val play = rememberHaptics()
    return remember(play) { { play(Haptic.Tick) } }
}

/**
 * A block of a page arriving: it fades in as it rises [Micro.Rise] into place, [index] steps of
 * [Micro.StaggerMs] after the first, so a page opening reads top to bottom (title, facts, Play,
 * the buttons) rather than landing at once. Once per composition, so a block that stays does not
 * rise again; nothing at all with less motion asked for, or where [enabled] is false (a television,
 * where focus is already on its way to the first button).
 */
@Composable
fun Modifier.riseIn(index: Int = 0, enabled: Boolean = true): Modifier {
    val still = rememberLessMotion() || !enabled
    val progress = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(still) {
        if (still) {
            progress.snapTo(1f)
            return@LaunchedEffect
        }
        delay(Micro.staggerDelay(index))
        progress.animateTo(1f, tween(Motion.Normal, easing = Motion.Standard))
    }
    if (still) return this
    val rise = with(LocalDensity.current) { Micro.Rise.toPx() }
    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * rise
    }
}

/** True when the system or the app asks for less motion. */
@Composable
fun rememberLessMotion(): Boolean = rememberReducedMotion() || Motion.reduced

/** Sinks this to [depth] while [interactions] holds a press, and springs it back up. */
@Composable
fun Modifier.pressScale(interactions: InteractionSource, depth: Float = Micro.PressScale): Modifier {
    val reduced = rememberLessMotion()
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = Micro.pressTarget(pressed, reduced, depth),
        animationSpec = if (pressed) tween(Motion.Fast / 2) else Micro.release(),
        label = "press",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/**
 * An icon that changes in place: the old one shrinks and fades as the new one grows in, for a
 * toggle such as rows or tiles. Its colour cross-fades too.
 */
@Composable
fun SwapIcon(icon: ImageVector, tint: Color, size: Dp = 24.dp, modifier: Modifier = Modifier) {
    val reduced = rememberLessMotion()
    val ink by animateColorAsState(tint, Motion.standard(Motion.Fast), label = "swapIconTint")
    AnimatedContent(
        targetState = icon,
        modifier = modifier,
        transitionSpec = {
            if (reduced) {
                fadeIn(Motion.standard(Motion.Fast)) togetherWith fadeOut(Motion.standard(Motion.Fast))
            } else {
                (fadeIn(Motion.standard(Motion.Fast)) + scaleIn(Motion.standard(Motion.Normal), initialScale = 0.6f)) togetherWith
                    (fadeOut(Motion.standard(Motion.Fast)) + scaleOut(Motion.standard(Motion.Fast), targetScale = 0.6f))
            }
        },
        label = "swapIcon",
    ) { shown ->
        Icon(shown, contentDescription = null, tint = ink, modifier = Modifier.size(size))
    }
}

/**
 * The favourite star: filling, it pops up to [Micro.PopScale] and settles on a spring as its
 * colour turns to [on]; emptying, it dips to [Micro.UnpopScale] and back as it fades to [off].
 * The first draw does not pop: only a change does.
 */
@Composable
fun StarIcon(
    starred: Boolean,
    filled: ImageVector,
    outline: ImageVector,
    on: Color,
    off: Color,
    size: Dp = 24.dp,
    modifier: Modifier = Modifier,
) {
    val reduced = rememberLessMotion()
    val scale = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(starred) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        if (reduced) return@LaunchedEffect
        scale.snapTo(1f)
        scale.animateTo(if (starred) Micro.PopScale else Micro.UnpopScale, tween(Motion.Fast / 2 + 30))
        scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
    }
    val ink by animateColorAsState(if (starred) on else off, Motion.standard(Motion.Normal), label = "starTint")
    Icon(
        if (starred) filled else outline,
        contentDescription = null,
        tint = ink,
        modifier = modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
            },
    )
}

/**
 * The small word under an icon in a floating bar: [MaterialTheme]'s smallest label scaled down
 * to 10 sp, set tight and a little heavier so it still reads at that size.
 */
@Composable
fun captionStyle(): TextStyle = MaterialTheme.typography.labelSmall.copy(
    fontSize = 10.sp,
    lineHeight = 12.sp,
    fontWeight = FontWeight.Medium,
    letterSpacing = 0.1.sp,
)
