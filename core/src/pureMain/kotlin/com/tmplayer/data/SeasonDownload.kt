package com.tmplayer.data

/**
 * Which videos "Download season" and "Download unwatched" put on the queue, from a show's page.
 *
 * One copy per episode, and the copy is the one the episode list would play: the one partway
 * through, else the one most like the copy watched last (see [SeriesShelf.pick]), so a viewer
 * watching the 350 MB copies gets 350 MB copies and not the 1.8 GB ones beside them. An episode
 * with any copy already downloaded or on the queue is skipped whole, since a second size of an
 * episode already kept is not what anybody asked for. A copy from a chat that restricts saving
 * content gives way to the nearest copy that may be saved, and the episode is skipped when none
 * may.
 *
 * Only the choice lives here. Whether the choice fits on the disk is the platform's existing
 * multi-download, which says how many fitted in the words a ticked selection gets.
 */
object SeasonDownload {

    enum class Which {
        /** Every episode of the season. */
        Season,

        /** Only the episodes not on the Watched list. */
        Unwatched,
    }

    /**
     * The copies to queue for [season] of [series], in watching order.
     *
     * @param finished whether a copy is on the Watched list.
     * @param position how far into a copy the viewer got, 0 to 1.
     * @param held whether a copy is downloaded already or on the queue.
     */
    fun copies(
        series: Series,
        season: SeriesSeason,
        which: Which,
        finished: (MediaItem) -> Boolean,
        position: (MediaItem) -> Float,
        held: (MediaItem) -> Boolean = { false },
    ): List<MediaItem> {
        val progress = SeriesShelf.progress(series, finished, position)
        val next = progress.next
        return season.episodes.mapNotNull { episode ->
            if (which == Which.Unwatched && episode.copies.any(finished)) return@mapNotNull null
            if (episode.copies.any(held)) return@mapNotNull null
            // The list plays the next episode's chosen copy and picks the rest the same way.
            val chosen = if (next != null && episode.sameAs(next)) next.item else SeriesShelf.pick(episode, finished, position, progress.like)
            if (chosen.canBeSaved) chosen else EpisodeCopies.closest(chosen, episode.copies.filter { it.canBeSaved })
        }
    }

    /**
     * Whether "Download unwatched" says anything "Download season" does not: some of the season
     * is watched and some is not. Otherwise the page offers the season alone.
     */
    fun unwatchedDiffers(season: SeriesSeason, finished: (MediaItem) -> Boolean): Boolean {
        val done = season.episodes.count { episode -> episode.copies.any(finished) }
        return done in 1 until season.episodes.size
    }

    /** Whether anything in [season] may be saved at all: false in a chat that restricts saving. */
    fun anySavable(season: SeriesSeason): Boolean =
        season.episodes.any { episode -> episode.copies.any { it.canBeSaved } }

    /** What a season's download button stands for now; see [state]. */
    enum class State {
        /** Something is left to fetch: the button queues it. */
        Fetch,

        /** Nothing left to fetch, and some of it is still on the queue: the button says so. */
        Coming,

        /** Every episode it covers is downloaded already: the button says so and does nothing. */
        Done,

        /** Nothing it covers can be fetched (saving restricted) and nothing is kept either. */
        Nothing,
    }

    /**
     * Where "Download season" or "Download unwatched" for [season] stands, from the same choice
     * [copies] makes, so the button and the press agree: it offers to fetch exactly when a press
     * would queue something.
     *
     * @param downloaded whether a copy is in the Downloads folder.
     * @param queued whether a copy is on the download queue, at any stage.
     */
    fun state(
        series: Series,
        season: SeriesSeason,
        which: Which,
        finished: (MediaItem) -> Boolean,
        position: (MediaItem) -> Float,
        downloaded: (MediaItem) -> Boolean,
        queued: (MediaItem) -> Boolean,
    ): State {
        val left = copies(series, season, which, finished, position) { downloaded(it) || queued(it) }
        if (left.isNotEmpty()) return State.Fetch
        val covered = season.episodes.filter { episode -> which == Which.Season || !episode.copies.any(finished) }
        return when {
            covered.any { episode -> episode.copies.any(queued) } -> State.Coming
            covered.any { episode -> episode.copies.any(downloaded) } -> State.Done
            else -> State.Nothing
        }
    }
}
