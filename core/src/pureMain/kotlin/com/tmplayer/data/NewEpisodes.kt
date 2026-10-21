package com.tmplayer.data

import com.tmplayer.platform.mergeWith
import com.tmplayer.platform.sortedByKey

/**
 * How far the viewer has got in one show or one numbered course: the furthest episode or lesson
 * they started or finished, and when they last watched any of it.
 *
 * Read off the resume records and the Watched list alone, so it costs nothing to work out: no
 * chat is asked anything until [NewEpisodes.find] has a page of the chat to look through.
 */
data class ShowReached(
    val chatId: Long,
    /** The chat and the show or course it names, stable while the viewer works through it. */
    val key: String,
    /** The show's name as its files write it, or the course's; may be empty for a bare "Lecture 5". */
    val title: String,
    /** What to search the chat for to find the rest of it (see [EpisodeNeighbours.showOf]). */
    val searchFor: String,
    /** The furthest episode the viewer reached, as the record describes it. */
    val item: MediaItem,
    val season: Int,
    val episode: Int,
    /** Set for a numbered course rather than a show: its episodes are then lessons. */
    val lesson: LessonSteps.Lesson? = null,
    /** When the viewer last watched any of it, in milliseconds; 0 when nothing says. */
    val at: Long,
)

/** One episode posted since the viewer last watched its show, past the furthest one they reached. */
data class NewEpisode(
    /** The copy to play: of several, the one most like the copy the viewer watched last. */
    val item: MediaItem,
    /** The show's name, or the course's, for the tile. */
    val show: String,
    /** "S02E05", or "Lecture 5" for a course. */
    val code: String,
)

/**
 * The "New episodes" feed: episodes of the shows the viewer is watching that have turned up in
 * their chats since they last watched, and the "New" badge on a show's tile.
 *
 * An episode is new when all three hold:
 * - it comes after the furthest one the viewer reached, by season and episode as the Series view
 *   numbers them (anime counted straight through stays season one), or by lesson number in the
 *   same course and the same word ("Lecture 12" after "Lecture 11", never after "Part 11");
 * - no copy of it is watched or started;
 * - it was posted after the viewer last watched the show. Without this, somebody three lectures
 *   into a forty-lecture course would be told thirty-seven old lectures were new: what is merely
 *   next is Continue watching's business, and this row is for what arrived while they were away.
 *   Where no time is known nothing is called new, since a badge that cries wolf is worse than none.
 *
 * Pure, so the phone, the television and the desktop agree, and the rules are tested in one place.
 */
object NewEpisodes {

    /** Shows looked into at Home's load: each costs one small search of its chat. */
    const val SHOW_LIMIT = 10

    /** New episodes listed per show, so one show catching up a season does not fill the row alone. */
    const val PER_SHOW = 4

    /**
     * Records read for the shows in progress, newest first. The Watched list keeps thousands, and
     * a show the viewer is still following was watched recently; reading every name in it on a
     * 1 GB television to find ten shows would be most of a second for nothing.
     */
    const val RECENT_TOUCHES = 300

    /** When each video was last watched, by [SettingsStore.progressKey]: the later of a resume and a finish. */
    fun touchTimes(resume: Collection<ResumeRecord>, watched: Collection<WatchedRecord>): Map<String, Long> {
        val times = HashMap<String, Long>(resume.size + watched.size)
        resume.forEach { times.mergeWith(ProgressKeys.of(it.chatId, it.messageId), it.updatedAt, ::maxOf) }
        watched.forEach { times.mergeWith(it.key, it.watchedAt, ::maxOf) }
        return times
    }

    /**
     * The shows and courses the viewer is part way through, most recently watched first, at most
     * [limit]. A film names no episode and is left out; so is a show whose every record names it
     * in a way nothing can read a number from.
     */
    fun inProgress(
        resume: List<ResumeRecord>,
        watched: Collection<WatchedRecord>,
        limit: Int = SHOW_LIMIT,
    ): List<ShowReached> {
        val touches = (resume.map { it.toMediaItem() to it.updatedAt } + watched.map { it.toMediaItem() to it.watchedAt })
            .sortedByDescending { it.second }
            .take(RECENT_TOUCHES)
        val byShow = LinkedHashMap<String, MutableList<Pair<ShowReached, Long>>>()
        for ((item, at) in touches) {
            val reached = placeOf(item, at) ?: continue
            byShow.getOrPut(reached.key) { mutableListOf() } += reached to at
        }
        return byShow.values.map { places ->
            val furthest = places.maxWith(compareBy<Pair<ShowReached, Long>>({ it.first.season }, { it.first.episode }, { it.second })).first
            furthest.copy(at = places.maxOf { it.second })
        }.sortedByDescending { it.at }.take(limit)
    }

    /**
     * What is new in [show] among [pool], the chat's videos as far as they have been fetched: a
     * search for the show, Home's row of the chat, the recent videos. Oldest first, at most
     * [PER_SHOW].
     *
     * @param touched whether the viewer has watched or started a video.
     */
    fun find(show: ShowReached, pool: List<MediaItem>, touched: (MediaItem) -> Boolean): List<NewEpisode> {
        // The fetched copy of the reached video before the record's own: it has the real date and name.
        val items = (pool.filter { it.chatId == show.chatId } + show.item).distinctBy { it.id }
        val found = show.lesson?.let { lessons(show, it, items, touched) } ?: episodes(show, items, touched)
        return found.take(PER_SHOW)
    }

    /**
     * Every show's new episodes in one list, the most recently watched show first and each show's
     * episodes in order. [pools] holds each show's videos by [ShowReached.key].
     */
    fun collect(
        shows: List<ShowReached>,
        pools: Map<String, List<MediaItem>>,
        touched: (MediaItem) -> Boolean,
    ): List<NewEpisode> {
        val listed = HashSet<String>()
        return shows.flatMap { show -> find(show, pools[show.key].orEmpty(), touched) }
            .filter { listed.add(it.item.id) }
    }

    /**
     * How many episodes of [series] are new, for the badge on its tile: none for a show not yet
     * started, and none where no watch time is known.
     *
     * @param touchedAt when the viewer last watched a video, or null when nothing says.
     */
    fun inSeries(series: Series, touched: (MediaItem) -> Boolean, touchedAt: (MediaItem) -> Long?): Int {
        val since = series.episodes.asSequence().flatMap { it.copies }.filter(touched).mapNotNull(touchedAt).maxOrNull()
            ?: return 0
        return newIn(series, touched, since).size
    }

    /** The episodes of [series] past the furthest touched one, untouched, and posted after [since]. */
    private fun newIn(series: Series, touched: (MediaItem) -> Boolean, since: Long): List<SeriesEpisode> {
        if (since <= 0) return emptyList()
        val episodes = series.episodes
        val furthest = episodes.indexOfLast { episode -> episode.copies.any(touched) }
        if (furthest < 0) return emptyList()
        return episodes.drop(furthest + 1).filter { episode ->
            episode.copies.none(touched) && episode.copies.any { postedAfter(it, since) }
        }
    }

    private fun episodes(show: ShowReached, items: List<MediaItem>, touched: (MediaItem) -> Boolean): List<NewEpisode> {
        // Grouped the way the Series view groups them, so the numbering here is the numbering
        // there: a fansub "- 04" joins "S2 - 03" in season two, a bare "E5" joins its show.
        val series = SeriesShelf.arrange(items).asSequence()
            .filterIsInstance<ShelfEntry.Show>()
            .map { it.series }
            .firstOrNull { series -> series.episodes.any { episode -> episode.copies.any { it.id == show.item.id } } }
            ?: return emptyList()
        val reached = { item: MediaItem -> item.id == show.item.id || touched(item) }
        return newIn(series, reached, show.at).map { episode ->
            val copy = EpisodeCopies.closest(show.item, episode.copies) ?: episode.item
            NewEpisode(copy, series.title, episode.code)
        }
    }

    private fun lessons(
        show: ShowReached,
        lesson: LessonSteps.Lesson,
        items: List<MediaItem>,
        touched: (MediaItem) -> Boolean,
    ): List<NewEpisode> {
        if (show.at <= 0) return emptyList()
        val course = SeriesShelf.keyOf(lesson.course)
        val run = items.mapNotNull { item -> LessonSteps.lessonOf(item)?.let { item to it } }
            .filter { (_, other) -> other.word == lesson.word && SeriesShelf.keyOf(other.course) == course }
        val furthest = maxOf(lesson.number, run.filter { touched(it.first) }.maxOfOrNull { it.second.number } ?: 0)
        return run.filter { it.second.number > furthest }
            .groupBy { it.second.number }
            .filterValues { copies -> copies.none { touched(it.first) } && copies.any { postedAfter(it.first, show.at) } }
            .sortedByKey()
            .map { (_, copies) ->
                val (item, found) = copies.maxBy { it.first.messageId }
                NewEpisode(item, found.course, found.code)
            }
    }

    /** Where one watched video sits: its show and episode, or its course and lesson, or null for a film. */
    private fun placeOf(item: MediaItem, at: Long): ShowReached? {
        EpisodeNeighbours.tagOf(item)?.let { tag ->
            val key = SeriesShelf.keyOf(tag.show)
            if (key.isNotEmpty()) {
                return ShowReached(item.chatId, "${item.chatId}|s|$key", tag.show, tag.show, item, tag.season, tag.episode, at = at)
            }
        }
        val lesson = LessonSteps.lessonOf(item) ?: return null
        val key = "${item.chatId}|l|${SeriesShelf.keyOf(lesson.course)}|${lesson.word}"
        return ShowReached(item.chatId, key, lesson.course, lesson.searchFor, item, 1, lesson.number, lesson, at)
    }

    /** Telegram dates are seconds; the watch times are milliseconds. */
    private fun postedAfter(item: MediaItem, since: Long) = item.date * 1000L > since
}
