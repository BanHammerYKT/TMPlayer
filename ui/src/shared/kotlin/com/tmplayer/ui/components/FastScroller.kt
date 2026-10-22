package com.tmplayer.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.unit.Density
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.floatingBorder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * The "hold to scroll" handle of a long vertical grid, laid over it on the end edge of the
 * [BoxScope] the grid fills. See the [LazyListState] overload for how it behaves.
 */
@Composable
fun BoxScope.FastScroller(
    state: LazyGridState,
    modifier: Modifier = Modifier,
    label: ((key: Any) -> String?)? = null,
    top: Dp = 8.dp,
    bottom: Dp = 8.dp,
    whenShort: (@Composable BoxScope.() -> Unit)? = null,
) {
    val target = remember(state) { GridTarget(state) }
    FastScroller(target, state, modifier, label, top, bottom, whenShort)
}

/**
 * The "hold to scroll" handle of a long vertical list: a half pill against the end edge (the left
 * one in a right to left language) with an up and a down chevron in it. Holding it and dragging
 * runs through the whole list at once, the handle staying under the finger or the pointer, and the
 * list goes to the place along it that the handle stands at on its track.
 *
 * - **When.** Only on a list longer than [LONG_SCREENS] screenfuls, where a fling would take a
 *   while; it slides in from the edge when the list moves (or a pointer comes to the edge on the
 *   desktop) and goes again [IDLE_MS] after it stops, unless it is held.
 * - **Where you are.** While it is dragged, a bubble beside it says what the screen has reached,
 *   when [label] can say: given the key of an item on screen it answers a date, a letter, a season,
 *   or null. The first visible item with an answer is the one shown. No [label], no bubble.
 * - **Taps.** A tap without a drag does nothing but keep the handle up. Jumping to the top on
 *   its upper half and to the end on its lower half was weighed and left out: a hand that grabs
 *   and lets go without moving would be thrown to one end of the list. [ScrollToTop] already
 *   takes the top, and is held back while this is dragged.
 * - **Pointer.** A mouse drags it as a finger does, under a hand cursor. On the desktop it takes
 *   the place of the window's scrollbar on a long list (one control on the edge, not two), and a
 *   short list keeps whatever [whenShort] draws, the scrollbar there.
 * - **Screen readers.** Left out of the tree: the list scrolls itself for them, a page at a time,
 *   and a second way through the same list would only be one more stop.
 * - **Television.** Nothing at all: a remote has no drag, and the D-pad pages through instead.
 *
 * [top] and [bottom] keep the track off the edges of the list's box; a floating bar at the foot
 * of the screen and the navigation bar are cleared on top of [bottom], as [ScrollToTop] does.
 */
@Composable
fun BoxScope.FastScroller(
    state: LazyListState,
    modifier: Modifier = Modifier,
    label: ((key: Any) -> String?)? = null,
    top: Dp = 8.dp,
    bottom: Dp = 8.dp,
    whenShort: (@Composable BoxScope.() -> Unit)? = null,
) {
    val target = remember(state) { ListTarget(state) }
    FastScroller(target, state, modifier, label, top, bottom, whenShort)
}

/**
 * The lists whose handle is held at this moment, by their state: [ScrollToTop] stays away while
 * the list under it is being dragged through, since a drag up would otherwise call it up.
 */
internal object FastScrollHeld {
    private val held = mutableStateMapOf<Any, Unit>()

    fun isHeld(state: Any): Boolean = state in held

    fun set(state: Any, on: Boolean) {
        if (on) held[state] = Unit else held.remove(state)
    }
}

@Composable
private fun BoxScope.FastScroller(
    target: FastScrollTarget,
    owner: Any,
    modifier: Modifier,
    label: ((Any) -> String?)?,
    top: Dp,
    bottom: Dp,
    whenShort: (@Composable BoxScope.() -> Unit)?,
) {
    val long by remember(target) { derivedStateOf { target.metrics()?.let { it.lines / it.visibleLines.coerceAtLeast(1f) > LONG_SCREENS } == true } }
    if (!isTouch()) {
        whenShort?.invoke(this)
        return
    }
    if (!long) {
        whenShort?.invoke(this)
    }
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val lessMotion = rememberLessMotion()
    val haptics = rememberHaptics()
    val labelOf by rememberUpdatedState(label)

    var held by remember { mutableStateOf(false) }
    var hovered by remember { mutableStateOf(false) }
    var shown by remember { mutableStateOf(false) }
    // The handle's top while it is dragged, in pixels down the track; null when it follows the list.
    var dragTop by remember { mutableStateOf<Float?>(null) }
    var track by remember { mutableFloatStateOf(0f) }
    val handle = with(density) { HANDLE_HEIGHT.toPx() }
    val range = (track - handle).coerceAtLeast(1f)
    val rangeNow by rememberUpdatedState(range)
    // Where the handle was last placed down the track. A pointer's position comes in relative to
    // the handle as last laid out, and the handle moves under it, so the track position is this
    // plus that, rather than a sum of deltas (which would lose what the handle moved meanwhile).
    val placedTop = remember { floatArrayOf(0f) }

    LaunchedEffect(target) {
        snapshotFlow { target.scrolling || held || hovered }.collectLatest { active ->
            if (active) {
                shown = true
            } else {
                delay(IDLE_MS)
                shown = false
            }
        }
    }
    // The drag drives the list: the latest place only, so a quick drag never queues up jumps.
    LaunchedEffect(target) {
        snapshotFlow { dragTop?.let { it / rangeNow } }.filterNotNull().collect { target.scrollTo(it.coerceIn(0f, 1f)) }
    }
    LaunchedEffect(owner) {
        snapshotFlow { held }.collect { FastScrollHeld.set(owner, it) }
    }
    androidx.compose.runtime.DisposableEffect(owner) { onDispose { FastScrollHeld.set(owner, false) } }

    // How far the list's bottom edge sits above the window's, to clear what floats over the foot.
    var above by remember { mutableFloatStateOf(0f) }
    val navigationBar = WindowInsets.navigationBars.getBottom(density)
    val chrome = with(density) { LocalBottomChrome.current.toPx() }
    val lift = with(density) { (maxOf(navigationBar.toFloat(), chrome) - above).coerceAtLeast(0f).toDp() }

    Box(
        modifier
            .matchParentSize()
            .onGloballyPositioned { above = it.findRootCoordinates().size.height - it.boundsInRoot().bottom }
            .padding(top = top, bottom = bottom + lift)
            .onSizeChanged { track = it.height.toFloat() },
    ) {
        // A pointer at the edge brings the handle out on the desktop, without taking anything
        // from the list under it: the strip shares every event with its siblings.
        if (long) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .fillMaxHeight()
                    .width(EDGE_STRIP)
                    .then(EdgeHoverElement { hovered = it }),
            )
        }
        AnimatedVisibility(
            visible = long && (shown || held),
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset {
                    val at = (dragTop ?: ((target.metrics()?.progress ?: 0f) * range)).coerceIn(0f, range).roundToInt()
                    placedTop[0] = at.toFloat()
                    IntOffset(0, at)
                },
            enter = if (lessMotion) {
                fadeIn(Motion.standard(Motion.Fast))
            } else {
                fadeIn(Motion.standard(Motion.Fast)) + slideInHorizontally(Motion.standard(Motion.Normal)) { if (rtl) -it / 2 else it / 2 }
            },
            exit = if (lessMotion) {
                fadeOut(Motion.standard(Motion.Normal))
            } else {
                fadeOut(Motion.standard(Motion.Normal)) + slideOutHorizontally(Motion.standard(Motion.Normal)) { if (rtl) -it / 2 else it / 2 }
            },
        ) {
            Row(
                Modifier.clearAndSetSemantics { testTag = FAST_SCROLLER_TAG },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(BUBBLE_GAP),
            ) {
                val bubble = if (held && dragTop != null) {
                    val find = labelOf
                    if (find == null) null else target.visibleKeys().firstNotNullOfOrNull(find)
                } else {
                    null
                }
                // The last thing said stays in the bubble while it fades, rather than blanking.
                var said by remember { mutableStateOf("") }
                if (bubble != null) said = bubble
                AnimatedVisibility(
                    visible = bubble != null,
                    enter = fadeIn(Motion.standard(Motion.Fast)) + if (lessMotion) fadeIn() else scaleIn(Motion.standard(Motion.Fast), initialScale = 0.85f),
                    exit = fadeOut(Motion.standard(Motion.Fast)) + if (lessMotion) fadeOut() else scaleOut(Motion.standard(Motion.Fast), targetScale = 0.85f),
                ) {
                    ScrollBubble(said)
                }
                Box(
                    Modifier
                        .size(TOUCH_WIDTH, HANDLE_HEIGHT)
                        .pointerHoverIcon(PointerIcon.Hand)
                        .pointerInput(target) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                down.consume()
                                held = true
                                haptics(Haptic.GestureStart)
                                val span = rangeNow
                                // Where on the handle it was taken, kept under the finger.
                                val grip = down.position.y
                                try {
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                        if (change.changedToUpIgnoreConsumed() || !change.pressed) break
                                        val next = (placedTop[0] + change.position.y - grip).coerceIn(0f, span)
                                        // Not a drag until it moves: a still press leaves the list where it is.
                                        if (dragTop != null || next != placedTop[0]) dragTop = next
                                        change.consume()
                                    }
                                } finally {
                                    held = false
                                    dragTop = null
                                }
                            }
                        },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    ScrollHandle(pressed = held, rtl = rtl, lessMotion = lessMotion)
                }
            }
        }
    }
}

/** The half pill itself: the floating fill and hairline at rest, the accent while it is held. */
@Composable
private fun ScrollHandle(pressed: Boolean, rtl: Boolean, lessMotion: Boolean) {
    val fill by animateColorAsState(if (pressed) Tone.accent else FloatingTone.menu, Motion.standard(Motion.Fast), label = "fastScrollFill")
    val ink by animateColorAsState(if (pressed) Tone.onAccent else Tone.text, Motion.standard(Motion.Fast), label = "fastScrollInk")
    val grow by animateFloatAsState(
        if (pressed && !lessMotion) PRESS_GROW else 1f,
        if (pressed) tween(Motion.Fast / 2) else Micro.release(),
        label = "fastScrollGrow",
    )
    val shape = EdgeTabShape
    Column(
        Modifier
            // Grows out of the edge it stands on, not about its middle, so it never lifts off it.
            .graphicsLayer {
                scaleX = grow
                scaleY = grow
                transformOrigin = TransformOrigin(if (rtl) 0f else 1f, 0.5f)
            }
            .size(HANDLE_WIDTH, HANDLE_HEIGHT)
            .background(fill, shape)
            .border(floatingBorder(), shape)
            // The chevrons sit in the round part's middle, a little off the straight edge.
            .padding(start = 4.dp),
        verticalArrangement = Arrangement.spacedBy((-7).dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(TmIcons.ChevronUp, contentDescription = null, tint = ink, modifier = Modifier.size(CHEVRON))
        Icon(TmIcons.ChevronDown, contentDescription = null, tint = ink, modifier = Modifier.size(CHEVRON))
    }
}

/**
 * A tab against the end edge: a half circle the handle's height on its start side and straight
 * on the edge it stands on. A rounded corner shape cannot draw it, since a corner is held to half
 * the narrower side, which on a 28 dp wide handle would leave a squared off pill.
 */
private object EdgeTabShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val r = minOf(size.height / 2f, size.width)
        val h = size.height
        val w = size.width
        val path = Path().apply {
            if (layoutDirection == LayoutDirection.Ltr) {
                moveTo(w, 0f)
                lineTo(r, 0f)
                arcTo(Rect(0f, h / 2f - r, 2f * r, h / 2f + r), 270f, -180f, false)
                lineTo(w, h)
            } else {
                moveTo(0f, 0f)
                lineTo(w - r, 0f)
                arcTo(Rect(w - 2f * r, h / 2f - r, w, h / 2f + r), 270f, 180f, false)
                lineTo(0f, h)
            }
            close()
        }
        return Outline.Generic(path)
    }
}

/** Where the list has got to, beside the handle, in the floating look. */
@Composable
private fun ScrollBubble(text: String) {
    val shape = RoundedCornerShape(Corner.Medium)
    Box(
        Modifier
            .heightIn(min = BUBBLE_HEIGHT)
            .widthIn(min = BUBBLE_HEIGHT, max = BUBBLE_MAX_WIDTH)
            .background(FloatingTone.dialog, shape)
            .border(floatingBorder(), shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleMedium,
            color = Tone.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Where a list is, in lines (a list's items, a grid's rows): how many there are in all, how many
 * fit on screen, how far down the first one on screen is, and one line's height in pixels with
 * the gap after it. Counts past what is laid out are estimates from what is.
 */
internal class ScrollMetrics(
    val lines: Float,
    val visibleLines: Float,
    val firstLine: Float,
    val lineSize: Float,
    val atStart: Boolean,
    val atEnd: Boolean,
) {
    /** How far down the list is, 0 at the top and 1 at the end. */
    val progress: Float
        get() = when {
            atStart -> 0f
            atEnd -> 1f
            else -> FastScrollMath.progress(firstLine, lines, visibleLines)
        }
}

/** The arithmetic of the track, apart from Compose so it is tested on its own. */
internal object FastScrollMath {
    /** How far down [firstLine] is, of the [lines] a list can scroll through with [visibleLines] on screen. */
    fun progress(firstLine: Float, lines: Float, visibleLines: Float): Float {
        val span = lines - visibleLines
        return if (span <= 0f) 0f else (firstLine / span).coerceIn(0f, 1f)
    }

    /** The line, fractional, that [fraction] of the track stands for: the inverse of [progress]. */
    fun line(fraction: Float, lines: Float, visibleLines: Float): Float =
        fraction.coerceIn(0f, 1f) * (lines - visibleLines).coerceAtLeast(0f)
}

/** A lazy list or grid, as the fast scroller reads and moves it. */
private interface FastScrollTarget {
    val scrolling: Boolean
    fun metrics(): ScrollMetrics?
    fun visibleKeys(): List<Any>
    suspend fun scrollTo(fraction: Float)
}

private class ListTarget(private val state: LazyListState) : FastScrollTarget {
    override val scrolling: Boolean get() = state.isScrollInProgress

    override fun metrics(): ScrollMetrics? {
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        if (visible.isEmpty() || info.totalItemsCount == 0) return null
        val gap = info.mainAxisItemSpacing
        val lineSize = (visible.sumOf { it.size + gap }.toFloat() / visible.size).coerceAtLeast(1f)
        val viewport = (info.viewportEndOffset - info.viewportStartOffset - info.beforeContentPadding - info.afterContentPadding).coerceAtLeast(1)
        val first = visible.first()
        val within = state.firstVisibleItemScrollOffset / (first.size + gap).coerceAtLeast(1).toFloat()
        return ScrollMetrics(
            lines = info.totalItemsCount.toFloat(),
            visibleLines = viewport / lineSize,
            firstLine = state.firstVisibleItemIndex + within,
            lineSize = lineSize,
            atStart = !state.canScrollBackward,
            atEnd = !state.canScrollForward,
        )
    }

    override fun visibleKeys(): List<Any> = state.layoutInfo.visibleItemsInfo.map { it.key }

    override suspend fun scrollTo(fraction: Float) {
        val metrics = metrics() ?: return
        val total = state.layoutInfo.totalItemsCount
        when {
            fraction <= 0f -> state.scrollToItem(0)
            fraction >= 1f -> state.scrollToItem(total - 1)
            else -> {
                val line = FastScrollMath.line(fraction, metrics.lines, metrics.visibleLines)
                val index = floor(line).toInt().coerceIn(0, total - 1)
                state.scrollToItem(index, ((line - index) * metrics.lineSize).roundToInt())
            }
        }
    }
}

private class GridTarget(private val state: LazyGridState) : FastScrollTarget {
    override val scrolling: Boolean get() = state.isScrollInProgress

    /** The visible items by row, and how many a full row holds (a heading spans one alone). */
    private class Rows(val firstIndexOf: Map<Int, Int>, val heightOf: Map<Int, Int>, val perLine: Int)

    private fun rows(): Rows? {
        val visible = state.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return null
        val firstIndexOf = HashMap<Int, Int>()
        val heightOf = HashMap<Int, Int>()
        val countOf = HashMap<Int, Int>()
        for (item in visible) {
            val row = item.row
            firstIndexOf[row] = minOf(firstIndexOf[row] ?: Int.MAX_VALUE, item.index)
            heightOf[row] = maxOf(heightOf[row] ?: 0, item.size.height)
            countOf[row] = (countOf[row] ?: 0) + 1
        }
        return Rows(firstIndexOf, heightOf, countOf.values.max().coerceAtLeast(1))
    }

    override fun metrics(): ScrollMetrics? {
        val info = state.layoutInfo
        val visible = info.visibleItemsInfo
        val rows = rows() ?: return null
        if (info.totalItemsCount == 0) return null
        val gap = info.mainAxisItemSpacing
        val lineSize = (rows.heightOf.values.sumOf { it + gap }.toFloat() / rows.heightOf.size).coerceAtLeast(1f)
        val viewport = (info.viewportEndOffset - info.viewportStartOffset - info.beforeContentPadding - info.afterContentPadding).coerceAtLeast(1)
        val first = visible.first()
        val last = visible.maxBy { it.index }
        val after = ceil((info.totalItemsCount - 1 - last.index).coerceAtLeast(0) / rows.perLine.toFloat())
        val firstHeight = (rows.heightOf[first.row] ?: first.size.height) + gap
        return ScrollMetrics(
            lines = last.row + 1 + after,
            visibleLines = viewport / lineSize,
            firstLine = first.row + state.firstVisibleItemScrollOffset / firstHeight.coerceAtLeast(1).toFloat(),
            lineSize = lineSize,
            atStart = !state.canScrollBackward,
            atEnd = !state.canScrollForward,
        )
    }

    override fun visibleKeys(): List<Any> = state.layoutInfo.visibleItemsInfo.map { it.key }

    override suspend fun scrollTo(fraction: Float) {
        val metrics = metrics() ?: return
        val rows = rows() ?: return
        val total = state.layoutInfo.totalItemsCount
        when {
            fraction <= 0f -> state.scrollToItem(0)
            fraction >= 1f -> state.scrollToItem(total - 1)
            else -> {
                val line = FastScrollMath.line(fraction, metrics.lines, metrics.visibleLines)
                val row = floor(line).toInt()
                // A row on screen is found exactly; one further off is counted from the first row
                // on screen, which is exact for a run of full rows and close past a heading.
                val index = rows.firstIndexOf[row] ?: run {
                    val anchorRow = rows.firstIndexOf.keys.min()
                    rows.firstIndexOf.getValue(anchorRow) + (row - anchorRow) * rows.perLine
                }
                state.scrollToItem(index.coerceIn(0, total - 1), ((line - row) * metrics.lineSize).roundToInt())
            }
        }
    }
}

/** Says when a mouse is over the strip at the edge, and passes every event on to what is under it. */
private class EdgeHoverElement(val onHover: (Boolean) -> Unit) : ModifierNodeElement<EdgeHoverNode>() {
    override fun create() = EdgeHoverNode(onHover)
    override fun update(node: EdgeHoverNode) {
        node.onHover = onHover
    }
    override fun hashCode(): Int = onHover.hashCode()
    override fun equals(other: Any?): Boolean = other is EdgeHoverElement && other.onHover === onHover
}

private class EdgeHoverNode(var onHover: (Boolean) -> Unit) : Modifier.Node(), PointerInputModifierNode {
    override fun onPointerEvent(pointerEvent: PointerEvent, pass: PointerEventPass, bounds: IntSize) {
        if (pass != PointerEventPass.Main) return
        if (pointerEvent.changes.any { it.type != PointerType.Mouse }) return
        when (pointerEvent.type) {
            PointerEventType.Enter, PointerEventType.Move -> onHover(true)
            PointerEventType.Exit -> onHover(false)
            else -> Unit
        }
    }

    override fun onCancelPointerInput() = onHover(false)

    override fun sharePointerInputWithSiblings(): Boolean = true
}

/** The test tag on the handle, for the UI tests; screen readers are not shown it. */
const val FAST_SCROLLER_TAG = "fast-scroller"

/** A list has to be more than this many screenfuls long for the handle to be worth it. */
private const val LONG_SCREENS = 3f

/** How long the handle waits on a still list before it goes. */
private const val IDLE_MS = 1_500L

/** How much the handle grows under the finger, from its edge. */
private const val PRESS_GROW = 1.12f

private val HANDLE_HEIGHT = 48.dp
private val HANDLE_WIDTH = 28.dp

/** The part a finger can take: a full 48 dp, of which the drawn half pill is the end 28. */
private val TOUCH_WIDTH = 48.dp
private val CHEVRON = 20.dp
private val EDGE_STRIP = 32.dp
private val BUBBLE_GAP = 8.dp
private val BUBBLE_HEIGHT = 40.dp
private val BUBBLE_MAX_WIDTH = 220.dp
