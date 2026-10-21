package com.tmplayer.ui.browse

import com.tmplayer.data.HomeRow
import com.tmplayer.i18n.Messages

/**
 * A Home row's heading, the same on the phone, the television and the desktop. A chat's row
 * where Home fills itself says which half of the chat it holds ("Movies in Films Club", "Shows
 * in Films Club"), since a chat's movies and shows never share a row.
 */
fun homeRowTitle(row: HomeRow, s: Messages): String = when (row) {
    is HomeRow.Continue -> s.homeRowContinue
    is HomeRow.WatchLater -> s.homeRowWatchLater
    is HomeRow.NewEpisodes -> s.homeRowNewEpisodes
    is HomeRow.Chat -> when (row.kind) {
        null -> row.title
        HomeRow.Across.Kind.Videos -> s.homeRowChatMovies(row.title)
        HomeRow.Across.Kind.Shows -> s.homeRowChatShows(row.title)
    }
    is HomeRow.Recent -> if (row.starredOnly) s.homeRowRecent else s.homeRowRecentAll
    is HomeRow.Across -> when (row.kind) {
        HomeRow.Across.Kind.Videos -> s.homeRowMovies
        HomeRow.Across.Kind.Shows -> s.homeRowShows
    }
}

/** Whether [row] has its tiles yet; a row that has not is drawn as a placeholder strip of the same height. */
val HomeRow.isLoaded: Boolean
    get() = when (this) {
        is HomeRow.Continue, is HomeRow.WatchLater, is HomeRow.NewEpisodes, is HomeRow.Across -> true
        is HomeRow.Chat -> loaded
        is HomeRow.Recent -> loaded
    }
