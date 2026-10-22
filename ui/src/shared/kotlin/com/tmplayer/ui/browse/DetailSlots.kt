package com.tmplayer.ui.browse

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import com.tmplayer.online.MetaExtras
import com.tmplayer.ui.components.SkeletonBox
import com.tmplayer.ui.components.rememberShimmer
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.online.MetaScoresRow
import com.tmplayer.ui.online.MetaState
import com.tmplayer.ui.online.metaFactsLine
import com.tmplayer.ui.online.metaPeopleLine
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/*
 * How a title page (a video's, a show's) comes together without anything moving once it is drawn.
 *
 * Much of a title page arrives after its first frame: the provider's match and with it the title
 * logo, the overview and the year, then the extras (genres, runtime, the scores from several
 * sites, the trailer, the cast), then a season's episode list. Inserted as they came, they pushed
 * Play down or up under a finger or the remote's focus. So:
 *
 *  - The page waits [FIRST_PAINT_HOLD_MS] at most before its first frame, for what the caches hold
 *    ([rememberFirstPaint]): a page opened a second time is drawn whole, at once.
 *  - What is still on its way after that has its place held from the first frame, at the size it
 *    will have: the title area at the logo's height, two lines for the genres and the scores with
 *    soft placeholders in them, the overview's lines, the trailer's button (unseen until it comes).
 *    Each place is decided once, on the first frame, and kept: a place whose content turns out
 *    not to exist stays empty for that visit rather than closing up under Play. The next visit
 *    knows from the cache and draws no place for it.
 *  - What arrives fades in where its place is.
 */

/**
 * Whether a title page draws its first frame yet: once [ready] (everything the caches can give is
 * in hand), or after [FIRST_PAINT_HOLD_MS] whatever has come, so a slow lookup never keeps the page
 * from opening. Keyed by [key]; once open it stays open.
 */
@Composable
internal fun rememberFirstPaint(key: Any, ready: Boolean): Boolean {
    var open by remember(key) { mutableStateOf(ready) }
    val latest by rememberUpdatedState(ready)
    LaunchedEffect(key) {
        if (open) return@LaunchedEffect
        withTimeoutOrNull(FIRST_PAINT_HOLD_MS) { snapshotFlow { latest }.first { it } }
        open = true
    }
    return open || ready
}

/**
 * Never measures shorter than it has before at the same width: a place that has held a logo, a
 * second line of the name or a longer overview keeps that height when what is in it changes, so
 * nothing under it moves up. [bottom] sets the content on the foot of the place (a title on its
 * backdrop), else at its top. A new width (a phone turned) starts afresh.
 */
@Composable
internal fun Modifier.neverShrink(bottom: Boolean = false): Modifier {
    val held = remember { HeldHeight() }
    return this.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        if (constraints.maxWidth != held.width) {
            held.width = constraints.maxWidth
            held.tallest = 0
        }
        held.tallest = maxOf(held.tallest, placeable.height)
        val height = held.tallest.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(placeable.width, height) {
            placeable.placeRelative(0, if (bottom) height - placeable.height else 0)
        }
    }
}

/** [neverShrink]'s memory: the tallest height seen, at the width it was seen at. Not state: it only steers layout. */
private class HeldHeight {
    var width = -1
    var tallest = 0
}

/**
 * The title area of a title page: at least [height] tall when a logo may come into it (the
 * logo's own box), its content on its foot, and never shorter than it has been, so the name
 * giving way to the logo (or a second line of the name) moves nothing else.
 */
@Composable
internal fun TitleSlot(height: Dp, hold: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .then(if (hold) Modifier.heightIn(min = height) else Modifier)
            .neverShrink(bottom = true),
        contentAlignment = Alignment.BottomStart,
    ) { content() }
}

/**
 * The match's facts under a title page's own facts line, as two lines of fixed height: the year
 * (where the name had none), genres, runtime and rating in the first, the scores with each site's
 * mark in the second. With [hold] both lines keep their height from the first frame; while the
 * extras are on their way each shows a soft placeholder, and what comes fades in where it stood.
 * Without [hold] (everything was known on the first frame) only the lines with something in them
 * are drawn. The director or creators are said elsewhere ([MetaPeopleLine]), under the actions,
 * so a page with none never closes a gap over Play.
 */
@Composable
internal fun DetailMetaLines(
    extras: MetaState<MetaExtras>,
    year: Int?,
    hold: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    yearPending: Boolean = false,
    runtime: Boolean = true,
    seasons: Boolean = true,
) {
    val s = LocalStrings.current
    val value = extras.value
    val facts = listOfNotNull(year?.toString(), value?.let { metaFactsLine(it, s, runtime = runtime, seasons = seasons) }).joinToString("  ·  ").ifBlank { null }
    val scored = value != null && (value.scores.isNotEmpty() || value.rating != null)
    val waiting = !extras.settled || yearPending
    if (!hold && facts == null && !scored) return
    val lineHeight = lineHeightOf(style)
    // While the answer is on its way the lines keep their order, a placeholder where each will
    // be; once it is in, what there is comes first and an empty line stays at the foot.
    val lines: List<MetaLine> = if (waiting) {
        listOf(if (facts != null) MetaLine.Facts else MetaLine.Waiting(0.62f), if (scored) MetaLine.Scores else MetaLine.Waiting(0.4f))
    } else {
        listOfNotNull(MetaLine.Facts.takeIf { facts != null }, MetaLine.Scores.takeIf { scored })
    }
    val count = if (hold) 2 else lines.size
    val shimmer = if (lines.any { it is MetaLine.Waiting }) rememberShimmer() else null
    Column(modifier, verticalArrangement = Arrangement.spacedBy(META_LINE_GAP)) {
        repeat(count) { at ->
            val line = lines.getOrNull(at) ?: MetaLine.Empty
            Box(Modifier.fillMaxWidth().height(lineHeight), contentAlignment = Alignment.CenterStart) {
                Crossfade(targetState = line, animationSpec = com.tmplayer.ui.theme.Motion.standard(), label = "metaLine") { shown ->
                    Box(Modifier.fillMaxWidth().wrapContentHeight(unbounded = true), contentAlignment = Alignment.CenterStart) {
                        when (shown) {
                            MetaLine.Facts -> facts?.let { Text(it, style = style, color = Tone.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            MetaLine.Scores -> value?.let { MetaScoresRow(it, maxLines = 1) }
                            is MetaLine.Waiting -> shimmer?.let {
                                SkeletonBox(it, Modifier.fillMaxWidth(shown.share).height(lineHeight * 0.6f))
                            }
                            MetaLine.Empty -> Unit
                        }
                    }
                }
            }
        }
    }
}

/** What one of [DetailMetaLines]' lines shows. */
private sealed interface MetaLine {
    data object Facts : MetaLine
    data object Scores : MetaLine
    data object Empty : MetaLine
    data class Waiting(val share: Float) : MetaLine
}

/** "Directed by …" or "Created by …", quiet, where a title page keeps its credits; nothing without. */
@Composable
internal fun MetaPeopleLine(extras: MetaExtras?, modifier: Modifier = Modifier) {
    extras ?: return
    val line = metaPeopleLine(extras, LocalStrings.current) ?: return
    Text(line, style = MaterialTheme.typography.bodyMedium, color = Tone.muted, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

/**
 * The overview (or the caption) of a title page in its place. [text] when there is one; while one
 * may still come ([waiting]) the same [lines] of soft placeholders. With [hold] the place is the
 * full [lines] and the "More" under them from the first frame, whatever is in it, so the overview
 * replacing the caption, or arriving at all, moves nothing.
 */
@Composable
internal fun SynopsisSlot(
    text: String?,
    waiting: Boolean,
    hold: Boolean,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyMedium,
    lines: Int? = null,
) {
    if (text == null && !hold) return
    val shimmer = if (text == null && waiting) rememberShimmer() else null
    Box(modifier.neverShrink()) {
        Crossfade(targetState = text, animationSpec = com.tmplayer.ui.theme.Motion.standard(), label = "synopsis") { shown ->
            if (shown != null) {
                Synopsis(shown, style = style, lines = lines, holdLines = hold)
            } else {
                Box(Modifier.fillMaxWidth()) {
                    // The place a synopsis takes, laid out and not drawn, so the placeholders fill
                    // exactly the room the words will.
                    Box(Modifier.alpha(0f).clearAndSetSemantics {}) { Synopsis(" ", style = style, lines = lines, holdLines = true) }
                    if (shimmer != null) {
                        val lineHeight = lineHeightOf(style)
                        val cutAt = lines ?: if (com.tmplayer.ui.components.isTouch()) 3 else 4
                        Column(Modifier.matchParentSize(), verticalArrangement = Arrangement.Top) {
                            repeat(cutAt) { at ->
                                Box(Modifier.fillMaxWidth().height(lineHeight), contentAlignment = Alignment.CenterStart) {
                                    SkeletonBox(shimmer, Modifier.fillMaxWidth(if (at == cutAt - 1) 0.55f else 0.94f).height(lineHeight * 0.55f))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Whether a title logo may still take the title's place: lookups on, and either the match on its
 * way or a match whose logo is not known to be missing. Read on the first frame, for [TitleSlot].
 */
@Composable
internal fun logoMayCome(meta: MetaState<com.tmplayer.online.MetaInfo>): Boolean {
    val online = com.tmplayer.online.OnlineMetadata.current ?: return false
    if (!online.settings.collectAsState().value.enabled) return false
    val info = meta.value ?: return !meta.settled
    val answer = online.peekLogo(info) ?: return true
    return answer.url != null
}

/** One line of [style], in dp at the screen's font scale. */
@Composable
internal fun lineHeightOf(style: TextStyle): Dp {
    val density = LocalDensity.current
    val sp = if (style.lineHeight.isSpecified) style.lineHeight else style.fontSize * 1.43f
    return with(density) { sp.toDp() }
}

/** The empty places for a picture shaped like a poster, with a soft placeholder while it comes. */
@Composable
internal fun PosterPlaceholder(modifier: Modifier = Modifier) {
    SkeletonBox(rememberShimmer(), modifier.fillMaxSize(), androidx.compose.foundation.shape.RoundedCornerShape(com.tmplayer.ui.theme.Corner.Medium))
}

/**
 * How long a title page waits at most for the caches before its first frame: long enough for an
 * answer kept on the disk, too short to read as a wait.
 */
internal const val FIRST_PAINT_HOLD_MS = 200L

private val META_LINE_GAP = 4.dp
