package com.tmplayer.data

import com.tmplayer.i18n.TmLocale
import com.tmplayer.platform.TextCollator
import com.tmplayer.platform.compareDigitRuns
import com.tmplayer.platform.textCollator
import com.tmplayer.i18n.L

/**
 * The orders a chat's videos can be listed in, from its Sort menu, remembered per chat
 * ([SettingsStore.videoSort]).
 *
 * Telegram hands a chat over newest first, a page at a time, so [Newest] is the only order that is
 * right before the whole chat has been read. Every other order sorts what has been loaded so far,
 * and the grid says so ([VideoSorting.sortsLoadedOnly]) while older pages remain: reading a chat of
 * thousands of posts to the end for one sort costs many requests, and Telegram slows an app down
 * that asks for too much at once. The pages still load as the viewer scrolls, and each one joins
 * the order as it arrives.
 */
enum class VideoSort {
    Newest,
    Oldest,
    Name,
    Year,
    Size,
    Unwatched,
    ;

    companion object {
        val DEFAULT = Newest

        /** A stored name back to a sort; anything unknown is [DEFAULT]. */
        fun decode(name: String?): VideoSort = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Sorts a chat's arranged entries ([SeriesShelf.arrange]) by a [VideoSort]. Pure, so the order of
 * every tile is tested here rather than in three grids.
 *
 * - **Stable.** Ties keep the chat's own order, so two films of one year stay as they were posted.
 * - **Shows.** A show stands for its episodes: its newest episode for Newest, its first for
 *   Oldest, its own title for Name, its total size for Size, and it counts as watched only when
 *   every episode is.
 * - **Unknowns last**, either way round: a file with no year, or no size, is listed after the
 *   ones that state it.
 * - **Names** compare in the app's language, ignoring case, accents and a leading article, with
 *   numbers in number order ("Part 2" before "Part 10"). The title is the file's own (or the one
 *   the viewer gave it), never a provider's.
 */
object VideoSorting {

    /** How far the viewer is with one video: [Untouched] first under [VideoSort.Unwatched]. */
    enum class Progress { Untouched, InProgress, Watched }

    /** Whether the grid should say it sorted only what is loaded: an order other than Newest, with pages to come. */
    fun sortsLoadedOnly(sort: VideoSort, endReached: Boolean): Boolean = sort != VideoSort.Newest && !endReached

    /**
     * [entries] in [sort]'s order.
     *
     * @param progress where the viewer is with one file; a copy counts for its whole video.
     * @param locale the app's language, for the order of names.
     */
    fun sort(
        entries: List<ShelfEntry>,
        sort: VideoSort,
        progress: (MediaItem) -> Progress = { Progress.Untouched },
        locale: TmLocale = L.messages.locale,
    ): List<ShelfEntry> {
        if (sort == VideoSort.Newest || entries.size < 2) return entries
        val indexed = entries.withIndex().toList()
        val collator = textCollator(locale, primary = true)
        // Each entry's sort value is read once: a title is a parse, and a comparison sort asks often.
        val names = if (sort == VideoSort.Name || sort == VideoSort.Year) indexed.map { nameOf(it.value) } else emptyList()
        val byName = Comparator<IndexedValue<ShelfEntry>> { a, b -> compareNames(names[a.index], names[b.index], collator) }
        val order: Comparator<IndexedValue<ShelfEntry>> = when (sort) {
            VideoSort.Newest -> compareBy { it.index }
            VideoSort.Oldest -> compareBy { oldest(it.value) }
            VideoSort.Name -> byName
            VideoSort.Year -> {
                val years = indexed.map { yearOf(it.value) }
                compareBy<IndexedValue<ShelfEntry>> { years[it.index] == null }
                    .thenByDescending { years[it.index] ?: 0 }
                    .then(byName)
            }
            VideoSort.Size -> {
                val sizes = indexed.map { sizeOf(it.value) }
                compareBy<IndexedValue<ShelfEntry>> { sizes[it.index] <= 0 }.thenByDescending { sizes[it.index] }
            }
            VideoSort.Unwatched -> compareBy { progressOf(it.value, progress) }
        }
        // sortedWith is stable, and the index as the last word makes it so whatever the order says.
        return indexed.sortedWith(order.thenBy { it.index }).map { it.value }
    }

    private fun items(entry: ShelfEntry): List<MediaItem> = when (entry) {
        is ShelfEntry.Show -> entry.series.episodes.flatMap { it.copies }
        is ShelfEntry.File -> entry.item.versions.ifEmpty { listOf(entry.item) }
    }

    private fun oldest(entry: ShelfEntry): Int = items(entry).minOf { it.date }

    /** The title an entry is listed under: the show's, or the file's own without its year. */
    private fun nameOf(entry: ShelfEntry): String = when (entry) {
        is ShelfEntry.Show -> entry.series.title
        is ShelfEntry.File -> SeriesShelf.posterTitle(entry.item)
    }

    private fun yearOf(entry: ShelfEntry): Int? = when (entry) {
        is ShelfEntry.Show -> entry.series.episodes.mapNotNull { SeriesShelf.episodeOf(it.item).year }.minOrNull()
        is ShelfEntry.File -> SeriesShelf.episodeOf(entry.item).year
    }

    /** A show's whole size; a film's largest copy, the one a size order is about; with its pieces added. */
    private fun sizeOf(entry: ShelfEntry): Long = when (entry) {
        is ShelfEntry.Show -> entry.series.episodes.sumOf { it.item.sizeBytes.coerceAtLeast(0) }
        is ShelfEntry.File -> entry.item.versions.ifEmpty { listOf(entry.item) }
            .maxOf { copy -> copy.parts.ifEmpty { listOf(copy) }.sumOf { it.sizeBytes.coerceAtLeast(0) } }
    }

    private fun progressOf(entry: ShelfEntry, progress: (MediaItem) -> Progress): Progress = when (entry) {
        is ShelfEntry.Show -> {
            val each = entry.series.episodes.map { episode -> episode.copies.maxOf { progress(it) } }
            when {
                each.all { it == Progress.Watched } -> Progress.Watched
                each.all { it == Progress.Untouched } -> Progress.Untouched
                else -> Progress.InProgress
            }
        }
        is ShelfEntry.File -> items(entry).maxOf { progress(it) }
    }

    /**
     * Two titles in the app's language: a leading article set aside, then word by word, a run of
     * digits by its value. Exposed for the tests.
     */
    internal fun compareNames(a: String, b: String, collator: TextCollator): Int {
        val x = chunks(withoutArticle(a))
        val y = chunks(withoutArticle(b))
        for (i in 0 until minOf(x.size, y.size)) {
            val p = x[i]
            val q = y[i]
            val c = when {
                p.isNotEmpty() && q.isNotEmpty() && p.all { it in '0'..'9' } && q.all { it in '0'..'9' } -> compareDigitRuns(p, q)
                else -> collator.compare(p, q)
            }
            if (c != 0) return c
        }
        return x.size.compareTo(y.size)
    }

    private fun withoutArticle(title: String): String = title.trim().replace(ARTICLE, "")

    /** "Part 10b" as "Part ", "10", "b": letters and digits in runs. */
    private fun chunks(text: String): List<String> = DIGITS_OR_NOT.findAll(text).map { it.value }.toList()

    private val ARTICLE = Regex("""^(?i)(the|a|an)\s+""")
    private val DIGITS_OR_NOT = Regex("""\d+|\D+""")
}
