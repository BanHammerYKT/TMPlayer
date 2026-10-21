package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import com.tmplayer.data.VideoFilter
import com.tmplayer.data.VideoSort
import com.tmplayer.i18n.Messages
import com.tmplayer.ui.components.ChoiceGroup
import com.tmplayer.ui.components.GroupedChoice
import com.tmplayer.ui.components.GroupedChoicePopover
import com.tmplayer.ui.components.PopoverAnchor
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons

/** What [sort] is called in a chat's Sort menu, on every device. */
fun videoSortLabel(sort: VideoSort, s: Messages): String = when (sort) {
    VideoSort.Newest -> s.gridSortNewest
    VideoSort.Oldest -> s.gridSortOldest
    VideoSort.Name -> s.gridSortName
    VideoSort.Year -> s.gridSortYear
    VideoSort.Size -> s.gridSortSize
    VideoSort.Longest -> s.gridSortLongest
    VideoSort.Unwatched -> s.gridSortUnwatched
}

/** The glyph beside [sort] in the menu. */
fun videoSortIcon(sort: VideoSort): ImageVector = when (sort) {
    VideoSort.Newest -> TmIcons.Clock
    VideoSort.Oldest -> TmIcons.History
    VideoSort.Name -> TmIcons.TextSize
    VideoSort.Year -> TmIcons.Trailer
    VideoSort.Size -> TmIcons.Download
    VideoSort.Longest -> TmIcons.Frames
    VideoSort.Unwatched -> TmIcons.Play
}

/** What [filter] is called in the Show group, and in the "Showing ..." line over the grid. */
fun videoFilterLabel(filter: VideoFilter, s: Messages): String = when (filter) {
    VideoFilter.All -> s.gridFilterAll
    VideoFilter.Unwatched -> s.gridFilterUnwatched
    VideoFilter.InProgress -> s.gridFilterInProgress
    VideoFilter.Watched -> s.gridFilterWatched
    VideoFilter.OnDevice -> s.gridFilterOnDevice
    VideoFilter.Shows -> s.gridFilterShows
    VideoFilter.Singles -> s.gridFilterSingles
    VideoFilter.HighDefinition -> s.gridFilterHd
}

/** The glyph beside [filter] in the menu. */
fun videoFilterIcon(filter: VideoFilter): ImageVector = when (filter) {
    VideoFilter.All -> TmIcons.Grid
    VideoFilter.Unwatched -> TmIcons.PlayCircle
    VideoFilter.InProgress -> TmIcons.Pause
    VideoFilter.Watched -> TmIcons.CheckCircle
    VideoFilter.OnDevice -> TmIcons.Folder
    VideoFilter.Shows -> TmIcons.Episodes
    VideoFilter.Singles -> TmIcons.PlaySolid
    VideoFilter.HighDefinition -> TmIcons.AspectRatio
}

/**
 * What a chat's Sort menu holds, the same on a phone, a television and a computer: the order
 * ([VideoSort]), which videos show ([VideoFilter]) and, while size limits are set in Settings,
 * whether this chat keeps to them for now ([sizeLimits]). Each pick applies at once.
 */
class VideoMenuState(
    val sort: VideoSort,
    val filter: VideoFilter,
    /** Null while no size limit is set, which leaves the group out. Otherwise: listed past them for now. */
    val allSizes: Boolean?,
    val onSort: (VideoSort) -> Unit,
    val onFilter: (VideoFilter) -> Unit,
    /** True lifts the limits for this chat while it is open, false keeps to them again. */
    val onAllSizes: (Boolean) -> Unit,
)

/** The menu's three groups, as [GroupedChoicePopover] draws them and the television's sheet lists them. */
fun videoMenuGroups(state: VideoMenuState, s: Messages): List<ChoiceGroup> = buildList {
    add(ChoiceGroup(s.gridSortLabel, VideoSort.entries.map { sort -> GroupedChoice(videoSortLabel(sort, s), videoSortIcon(sort), sort == state.sort) { state.onSort(sort) } }))
    add(ChoiceGroup(s.gridFilterLabel, VideoFilter.entries.map { filter -> GroupedChoice(videoFilterLabel(filter, s), videoFilterIcon(filter), filter == state.filter) { state.onFilter(filter) } }))
    val allSizes = state.allSizes
    if (allSizes != null) {
        add(
            ChoiceGroup(
                s.gridSizeLabel,
                listOf(
                    GroupedChoice(s.gridSizeWithin, TmIcons.FilterHorizontal, !allSizes) { state.onAllSizes(false) },
                    GroupedChoice(s.gridSizeAll, TmIcons.Checklist, allSizes) { state.onAllSizes(true) },
                ),
            ),
        )
    }
}

/**
 * A chat's Sort and filter menu on a phone and a computer: one card grown out of the Sort button
 * ([GroupedChoicePopover]) with the order, the Show group and the size limits. A pick applies and
 * closes the card, so the grid is seen to change under it.
 */
@Composable
fun VideoMenuPopover(open: Boolean, anchor: PopoverAnchor, state: VideoMenuState, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    GroupedChoicePopover(open = open, anchor = anchor, groups = videoMenuGroups(state, s), onDismiss = onDismiss)
}
