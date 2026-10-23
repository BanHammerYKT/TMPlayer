package com.tmplayer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.onFocusedBoundsChanged
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import com.tmplayer.ui.theme.Motion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Keeps a list's top in sight whenever the row the remote stands on fits on the list's first
 * screen. A heading, a blurb or a picture at the top of a list takes no focus, and moving focus
 * back up to the first row only scrolls far enough to show that row: the heading above it stayed
 * cut in half (Settings on a television, going down and coming back to the first page). Here the
 * list goes all the way back to its start instead, which shows that row just the same.
 *
 * On a television the list jumps back rather than gliding, as every other remote-driven scroll
 * there does ([stillWhenVisible]).
 *
 * Put it on every remote-driven lazy list whose first item is not focusable. [enabled] is false
 * where a pointer or a finger scrolls the list, which never moves focus this way.
 */
fun Modifier.keepTopInSight(state: LazyListState, enabled: Boolean = true): Modifier =
    if (!enabled) this else keepTopInSight(TopLink(state))

/** [keepTopInSight] for a grid. */
fun Modifier.keepTopInSight(state: LazyGridState, enabled: Boolean = true): Modifier =
    if (!enabled) this else keepTopInSight(TopLink(state))

/**
 * The list's own node keeps the list's place and the scroll it started; the focused-bounds
 * observer reaches it through [link]. Both elements carry the same link, and both compare equal
 * while the list's state is the same, so a recomposition replaces neither and the link the
 * observer holds is still the one the node answers to.
 */
@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.keepTopInSight(link: TopLink): Modifier =
    this then KeepTopElement(link) then Modifier.onFocusedBoundsChanged(link)

/** Calls through to the node on [state]'s list; equal for the same state. */
private class TopLink(val state: Any) : (LayoutCoordinates?) -> Unit {
    var node: KeepTopNode? = null

    override fun invoke(focused: LayoutCoordinates?) {
        node?.onFocusedBounds(focused)
    }

    override fun equals(other: Any?): Boolean = other is TopLink && other.state === state

    override fun hashCode(): Int = state.hashCode()
}

private class KeepTopElement(private val link: TopLink) : ModifierNodeElement<KeepTopNode>() {
    override fun create() = KeepTopNode(link)

    override fun update(node: KeepTopNode) = node.relink(link)

    override fun InspectorInfo.inspectableProperties() {
        name = "keepTopInSight"
        properties["state"] = link.state
    }

    override fun equals(other: Any?): Boolean = other is KeepTopElement && other.link == link

    override fun hashCode(): Int = link.hashCode()
}

private class KeepTopNode(private var link: TopLink) : Modifier.Node(), GlobalPositionAwareModifierNode {
    private var list: LayoutCoordinates? = null
    private var job: Job? = null

    fun relink(next: TopLink) {
        if (link.node === this) link.node = null
        link = next
        if (isAttached) next.node = this
    }

    override fun onAttach() {
        link.node = this
    }

    override fun onDetach() {
        if (link.node === this) link.node = null
        list = null
        job = null
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        list = coordinates
    }

    fun onFocusedBounds(focused: LayoutCoordinates?) {
        if (!isAttached) return
        val list = list ?: return
        if (focused == null || !list.isAttached || !focused.isAttached) return
        // Already at the top, or scrolled past the first item: nothing to bring back.
        val offset = firstOffset()
        if (firstIndex() != 0 || offset == 0) return
        if (job?.isActive == true) return
        // Where the focused row's foot would be with the list at its top.
        val top = list.localPositionOf(focused, Offset.Zero).y
        val foot = top + focused.size.height + offset
        if (foot <= list.size.height) job = coroutineScope.launch { scrollToTop() }
    }

    private fun firstIndex(): Int = when (val state = link.state) {
        is LazyListState -> state.firstVisibleItemIndex
        is LazyGridState -> state.firstVisibleItemIndex
        else -> -1
    }

    private fun firstOffset(): Int = when (val state = link.state) {
        is LazyListState -> state.firstVisibleItemScrollOffset
        is LazyGridState -> state.firstVisibleItemScrollOffset
        else -> 0
    }

    private suspend fun scrollToTop() {
        // A television moves its lists at once: a glide is a dozen frames or more on a 1 GB stick.
        val jump = Motion.television
        when (val state = link.state) {
            is LazyListState -> if (jump) state.scrollToItem(0) else state.animateScrollToItem(0)
            is LazyGridState -> if (jump) state.scrollToItem(0) else state.animateScrollToItem(0)
        }
    }
}
