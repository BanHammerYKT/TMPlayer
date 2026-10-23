package com.tmplayer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember

/**
 * A list stays where it is while the item the remote moves to is already wholly on screen, and
 * otherwise scrolls as the platform would.
 *
 * A television's own rule brings every focused item to one fixed height, even one already in
 * full view. Where a list also keeps its top in sight ([keepTopInSight], a chat's first row going
 * back to the start), the two pulled against each other on every press: left and right along a
 * chat's first row under its Audio entry made the whole grid jump down and back, Settings did the
 * same, and About's first button was pushed up far enough to cut the logo above it in half.
 *
 * Put it once round a remote-driven screen. A list with a rule of its own (Home's band under the
 * top bar, a row held at its start) provides that inside and wins, being nearer. [enabled] is
 * false where a pointer or a finger scrolls, whose platform rule is left alone.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun StillWhenVisible(enabled: Boolean, content: @Composable () -> Unit) {
    if (!enabled) return content()
    val platform = LocalBringIntoViewSpec.current
    val spec = remember(platform) { stillWhenVisible(platform) }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** The rule itself, apart from composition so a test can ask it directly. */
@OptIn(ExperimentalFoundationApi::class)
fun stillWhenVisible(platform: BringIntoViewSpec): BringIntoViewSpec = object : BringIntoViewSpec {
    // On a television the list moves at once: a slide to the next row of a chat's grid drew a
    // dozen frames or more on a 1 GB stick, where each frame takes 20 to 40 ms, so the grid swam
    // after every press. Elsewhere the platform's own motion.
    @Suppress("DEPRECATION")
    @Deprecated("Matches the interface.")
    override val scrollAnimationSpec: androidx.compose.animation.core.AnimationSpec<Float> =
        if (com.tmplayer.ui.theme.Motion.television) androidx.compose.animation.core.snap() else platform.scrollAnimationSpec

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float =
        if (offset >= 0f && offset + size <= containerSize) 0f
        else platform.calculateScrollDistance(offset, size, containerSize)
}
