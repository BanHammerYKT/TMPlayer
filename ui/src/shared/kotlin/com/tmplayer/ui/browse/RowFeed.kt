package com.tmplayer.ui.browse

import com.tmplayer.data.Failures
import com.tmplayer.data.FeedLane
import com.tmplayer.data.FeedMerge
import com.tmplayer.data.HomeRow
import com.tmplayer.data.HomeRows
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaPage
import com.tmplayer.data.ShelfEntry
import com.tmplayer.ui.components.SlowAnswer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.CoroutineContext

/**
 * A Home row's "See all" page read on past what Home fetched: its chats a page at a time, one
 * request at a time, merged newest first so the page only ever grows at its foot ([FeedMerge]),
 * and shaped as the row is ([HomeRows.seeAll]). The page starts with what Home holds, so it draws
 * at once, and asks for more with [loadMore] as its end comes near ([LoadMoreAhead]).
 *
 * Made by [HomeViewModel.seeAll], which keeps it while Home lives.
 */
class RowFeed internal constructor(
    private val scope: CoroutineScope,
    lanes: List<FeedLane>,
    seed: List<MediaItem>,
    row: HomeRow,
    private val read: suspend (chatId: Long, cursors: MediaCursors) -> MediaPage,
    /** The size limits, applied to every page read. */
    private val keep: suspend (List<MediaItem>) -> List<MediaItem>,
    /** Where the shelf is arranged: off the main thread. */
    private val work: CoroutineContext,
) {
    /**
     * @param entries the page's tiles, the row's own first.
     * @param loading true while a page is being read: the foot shows placeholder tiles.
     * @param failure why the last read failed; the foot offers Try again ([loadMore]).
     * @param done true once every chat has been read to its end.
     */
    data class State(
        val entries: List<ShelfEntry> = emptyList(),
        val loading: Boolean = false,
        val failure: String? = null,
        val done: Boolean = false,
    )

    private var row: HomeRow = row
    private var lanes: List<FeedLane> = lanes
    private val items = ArrayList<MediaItem>(seed)

    private val _state = MutableStateFlow(
        State(entries = HomeRows.seeAll(row, emptyList()).orEmpty(), done = FeedMerge.horizon(lanes) == null),
    )
    val state: StateFlow<State> = _state.asStateFlow()

    /** The read running, one at a time. */
    private var job: Job? = null

    /** Publishes one after another, so an older shape never lands over a newer one. */
    private val publishing = Mutex()

    init {
        // What Home already holds past the row's own tiles, arranged off the main thread: it
        // lands at the foot of the tiles drawn above, so nothing moves.
        scope.launch { publish() }
    }

    /**
     * Reads on until something new can be shown, a chat at a time, at most [STEPS] pages in one
     * go; nothing while a read is running or every chat is read. A failure stops it with
     * [State.failure], and calling this again is the Try again.
     */
    fun loadMore() {
        if (job?.isActive == true || _state.value.done) return
        _state.update { it.copy(loading = true, failure = null) }
        job = scope.launch {
            try {
                val before = _state.value.entries.size
                var steps = 0
                while (steps < STEPS) {
                    val lane = nextLane() ?: break
                    val page = withTimeoutOrNull(PAGE_TIMEOUT_MS) { read(lane.chatId, lane.cursors) }
                        ?: throw java.io.IOException(SlowAnswer.MESSAGE)
                    val kept = keep(page.items)
                    publishing.withLock {
                        lanes = FeedMerge.advance(lanes, lane.chatId, page)
                        items += kept
                    }
                    steps++
                    publish()
                    if (_state.value.entries.size > before) break
                }
                _state.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                _state.update { it.copy(loading = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, failure = Failures.humanise(e)) }
            }
        }
    }

    /** Home's row drawn again (a refresh): its tiles stay first, as they now are. */
    fun reshape(next: HomeRow) {
        if (next == row) return
        row = next
        scope.launch { publish() }
    }

    internal fun close() {
        job?.cancel()
    }

    private suspend fun publish() = publishing.withLock {
        val settled = FeedMerge.settled(items, lanes)
        val shape = row
        val shaped = withContext(work) { HomeRows.seeAll(shape, settled).orEmpty() }
        val done = FeedMerge.horizon(lanes) == null
        _state.update { it.copy(entries = shaped, done = done) }
    }

    /** The lane read next, under the same lock as the pages it adds. */
    private suspend fun nextLane(): FeedLane? = publishing.withLock { FeedMerge.next(lanes) }

    internal companion object {
        /** Pages read in one go while nothing new turns up: a chat whose videos the size limits all reject. */
        const val STEPS = 6

        const val PAGE_TIMEOUT_MS = 25_000L
    }
}
