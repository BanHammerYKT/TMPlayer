package com.tmplayer.ui.browse

import com.tmplayer.data.FeedLane
import com.tmplayer.data.HomeRow
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaPage
import com.tmplayer.data.ShelfEntry
import com.tmplayer.ui.components.nearEnd
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A Home row's See all page reading on through its chats: one request at a time, growing at its foot, Try again after a failure. */
@OptIn(ExperimentalCoroutinesApi::class)
class RowFeedTest {

    private val dispatcher = StandardTestDispatcher()

    @Before fun setUp() = Dispatchers.setMain(dispatcher)

    @After fun tearDown() = Dispatchers.resetMain()

    /** A plain name with no number in it, so no two videos read as episodes of one show. */
    private fun name(chatId: Long, messageId: Long): String {
        fun letter(n: Long) = 'a' + (n % 26).toInt()
        return "Holiday " + letter(chatId) + letter(messageId) + letter(messageId / 26) + letter(messageId / 676) + ".mp4"
    }

    private fun video(chatId: Long, messageId: Long, date: Int) = MediaItem(
        chatId = chatId, messageId = messageId, fileId = messageId.toInt(), title = name(chatId, messageId),
        sizeBytes = 600L * 1024 * 1024, durationSec = 60, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null,
        date = date, fileName = name(chatId, messageId),
    )

    /** Two chats of videos, newest first, served [PAGE] at a time. */
    private val chats = mapOf(
        1L to (0 until 30).map { video(1, 1000L - it, 5000 - it * 10) },
        2L to (0 until 12).map { video(2, 2000L - it, 4995 - it * 25) },
    )

    private var requests = 0
    private var running = 0
    private var mostAtOnce = 0
    private var failNext = false
    private var gate: CompletableDeferred<Unit>? = null

    private suspend fun read(chatId: Long, cursors: MediaCursors): MediaPage {
        requests++
        running++
        mostAtOnce = maxOf(mostAtOnce, running)
        try {
            gate?.await()
            if (failNext) {
                failNext = false
                throw java.io.IOException("offline")
            }
            val all = chats.getValue(chatId)
            val from = cursors.video.toInt()
            val items = all.drop(from).take(PAGE)
            val next = from + items.size
            return MediaPage(items, MediaCursors(video = next.toLong()), endReached = next >= all.size)
        } finally {
            running--
        }
    }

    private fun TestScope.feed(keep: suspend (List<MediaItem>) -> List<MediaItem> = { it }): RowFeed {
        // Home holds each chat's newest few, as its rows read them.
        val held = chats.mapValues { (_, items) -> items.take(3) }
        val lanes = held.map { (chatId, items) -> FeedLane(chatId, MediaCursors(video = 3), oldest = items.minOf { it.date }) }
        val row = HomeRow.Across(HomeRow.Across.Kind.Videos, listOf(ShelfEntry.File(held.getValue(2).first())), chatIds = listOf(1, 2))
        return RowFeed(this, lanes, held.values.flatten(), row, ::read, keep = keep, work = dispatcher)
    }

    private fun ids(feed: RowFeed) = feed.state.value.entries.map { (it as ShelfEntry.File).item.id }

    @Test
    fun `the page starts with what Home holds and grows only at its foot`() = runTest(dispatcher) {
        val feed = feed()
        advanceUntilIdle()
        val first = ids(feed)
        // The row's own tile first, then what Home holds that is in its place already.
        assertEquals("2:2000", first.first())
        assertTrue(first.size > 1)
        var shown = first
        while (!feed.state.value.done) {
            feed.loadMore()
            advanceUntilIdle()
            val now = ids(feed)
            assertEquals(shown, now.take(shown.size))
            shown = now
            assertTrue("runs away", requests < 50)
        }
        // Every video once, the row's tile first.
        assertEquals(42, shown.size)
        assertEquals(shown.size, shown.toSet().size)
        assertEquals(1, mostAtOnce)
        assertFalse(feed.state.value.loading)
    }

    @Test
    fun `one request at a time, however often the end is reached`() = runTest(dispatcher) {
        val feed = feed()
        advanceUntilIdle()
        gate = CompletableDeferred()
        feed.loadMore()
        feed.loadMore()
        feed.loadMore()
        // Not until idle: virtual time would run past the page's time limit.
        runCurrent()
        assertEquals(1, requests)
        assertTrue(feed.state.value.loading)
        gate!!.complete(Unit)
        advanceUntilIdle()
        assertFalse(feed.state.value.loading)
    }

    @Test
    fun `a failed page says so and Try again carries on`() = runTest(dispatcher) {
        val feed = feed()
        advanceUntilIdle()
        val before = ids(feed)
        failNext = true
        feed.loadMore()
        advanceUntilIdle()
        assertNotNull(feed.state.value.failure)
        assertFalse(feed.state.value.loading)
        assertEquals(before, ids(feed))
        feed.loadMore()
        advanceUntilIdle()
        assertNull(feed.state.value.failure)
        assertTrue(ids(feed).size > before.size)
    }

    @Test
    fun `a read that finds nothing new rests until the viewer scrolls`() = runTest(dispatcher) {
        // Every page rejected by the size limits: past what Home held, nothing new can be shown.
        val feed = feed(keep = { emptyList() })
        advanceUntilIdle()
        // What Home held still comes out as the reads move past it, so a few rounds find that.
        repeat(10) {
            if (!feed.state.value.resting && !feed.state.value.done) {
                feed.loadMore()
                advanceUntilIdle()
            }
        }
        val state = feed.state.value
        assertFalse(state.loading)
        assertTrue(state.resting || state.done)
        assertFalse("asks again by itself", state.wants(scrolling = false))
        if (!state.done) assertTrue(state.wants(scrolling = true))
    }

    @Test
    fun `a read that finds something does not rest`() = runTest(dispatcher) {
        val feed = feed()
        advanceUntilIdle()
        feed.loadMore()
        advanceUntilIdle()
        assertFalse(feed.state.value.resting)
        assertTrue(feed.state.value.wants(scrolling = false))
    }

    @Test
    fun `the next page is asked for a few items before the end`() {
        assertFalse(nearEnd(lastVisible = null, total = 40, ahead = 8))
        assertFalse(nearEnd(lastVisible = 0, total = 0, ahead = 8))
        assertFalse(nearEnd(lastVisible = 30, total = 40, ahead = 8))
        assertTrue(nearEnd(lastVisible = 31, total = 40, ahead = 8))
        assertTrue(nearEnd(lastVisible = 39, total = 40, ahead = 0))
        assertFalse(nearEnd(lastVisible = 38, total = 40, ahead = 0))
    }

    private companion object {
        const val PAGE = 7
    }
}
