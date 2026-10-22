package com.tmplayer.data

/**
 * A show's page made whole: the show as a screen folded it holds only the episodes that screen
 * had read (Home's newest few dozen videos, a grid's first pages), so a show with forty episodes
 * a little further back in its chat opened with three. Its chat is searched for the show's name
 * ([EpisodeNeighbours.showOf]) and what turns up is folded in here.
 */
object SeriesFill {

    /** Search pages read for one show at most: [PAGE] results each, a long show's worth. */
    const val MAX_PAGES = 8

    /** Messages asked for per search. */
    const val PAGE = 40

    /** What to search [series]'s chat for, or null for a show its files give no name to search by. */
    fun queryOf(series: Series): String? = EpisodeNeighbours.showOf(series.cover)?.takeIf { it.isNotBlank() }

    /**
     * [series] with the episodes and copies in [found] that belong to it (those the shelf folds
     * under the same show), or [series] as it is when they fold into no show of that key. Never
     * fewer episodes than it had: a search that misses some leaves what was there.
     */
    fun merge(series: Series, found: List<MediaItem>): Series {
        if (found.isEmpty()) return series
        val chatId = series.cover.chatId
        val own = series.episodes.flatMap { it.copies }
        val pool = (own + found.filter { it.chatId == chatId })
            .distinctBy { it.id }
            .sortedWith(FeedMerge.newestFirst)
        val whole = SeriesShelf.arrange(pool)
            .firstNotNullOfOrNull { entry -> (entry as? ShelfEntry.Show)?.series?.takeIf { it.key == series.key } }
            ?: return series
        return if (whole.episodeCount >= series.episodeCount && whole.episodes.sumOf { it.copies.size } >= own.size) whole else series
    }
}
