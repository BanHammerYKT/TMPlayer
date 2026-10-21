package com.tmplayer.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The page cache's TanStack rules, and how a chat's kept listing is brought up to date. */
class QueryCacheTest {

    private var now = 1_000L
    private val key = QueryKey.ChatMedia(7)

    private fun video(id: Long) = MediaItem(
        chatId = 7, messageId = id, fileId = id.toInt(), title = "v$id", sizeBytes = 1, durationSec = 1,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0, fileName = "v$id.mp4",
        onDevice = false,
    )

    @Test
    fun keptAnswersGoStaleWithAgeOrInvalidation() = runTest {
        val cache = QueryCache(backgroundScope, clock = { now })
        cache.put(key, "first")
        assertEquals(false, cache.peek<String>(key, staleTimeMs = 500)?.stale)
        now += 600
        assertEquals(true, cache.peek<String>(key, staleTimeMs = 500)?.stale)
        cache.put(key, "second")
        cache.invalidate { it == key }
        val kept = cache.peek<String>(key, staleTimeMs = 60_000)
        assertEquals("second", kept?.data, "an invalidated answer is still there to draw")
        assertEquals(true, kept?.stale)
    }

    @Test
    fun twoCallersOfOneKeyShareOneRequest() = runTest {
        val cache = QueryCache(backgroundScope, clock = { now })
        var asked = 0
        val gate = CompletableDeferred<Unit>()
        val a = async { cache.fetch(key) { asked++; gate.await(); "answer" } }
        val b = async { cache.fetch(key) { asked++; "other" } }
        testScheduler.runCurrent()
        assertTrue(cache.isFetching(key))
        gate.complete(Unit)
        assertEquals("answer", a.await())
        assertEquals("answer", b.await())
        assertEquals(1, asked)
        assertEquals("answer", cache.data<String>(key))
        assertFalse(cache.isFetching(key))
    }

    @Test
    fun aFailedRequestKeepsWhatWasThere() = runTest {
        val cache = QueryCache(backgroundScope, clock = { now })
        cache.put(key, "kept")
        val failed = runCatching { cache.fetch<String>(key) { throw IllegalStateException("offline") } }
        assertTrue(failed.isFailure)
        assertEquals("kept", cache.data<String>(key))
    }

    @Test
    fun theLeastRecentlyUsedGoesFirstAndTheUnusedAfterGcTime() = runTest {
        val cache = QueryCache(backgroundScope, maxEntries = 2, gcTimeMs = 10_000, clock = { now })
        val a = QueryKey.ChatMedia(1)
        val b = QueryKey.ChatMedia(2)
        val c = QueryKey.ChatMedia(3)
        cache.put(a, 1)
        now += 1
        cache.put(b, 2)
        now += 1
        cache.peek<Int>(a)
        now += 1
        cache.put(c, 3)
        assertNotNull(cache.data<Int>(a), "used lately, so kept")
        assertNull(cache.data<Int>(b), "the least recently used went")
        now += 20_000
        cache.put(QueryKey.ChatMedia(4), 4)
        assertEquals(1, cache.size, "unused past gcTime, gone at the next write")
    }

    @Test
    fun patchesKeepTheAnswersAge() = runTest {
        val cache = QueryCache(backgroundScope, clock = { now })
        cache.put(key, listOf(1, 2), at = 500)
        now = 2_000
        cache.update<List<Int>>(key) { it.orEmpty() + 3 }
        assertEquals(listOf(1, 2, 3), cache.data<List<Int>>(key))
        assertEquals(500, cache.entry(key)?.updatedAt)
        cache.update<List<Int>>(key) { null }
        assertNull(cache.data<List<Int>>(key))
    }

    @Test
    fun prefetchesAreDroppedPastTheLimitAndSkippedWhenFresh() = runTest {
        val cache = QueryCache(backgroundScope, prefetchLimit = 1, clock = { now })
        val gate = CompletableDeferred<Unit>()
        assertTrue(cache.prefetch(QueryKey.ChatMedia(1), 1_000) { gate.await(); 1 })
        assertFalse(cache.prefetch(QueryKey.ChatMedia(2), 1_000) { 2 }, "one running already")
        gate.complete(Unit)
        repeat(3) { testScheduler.runCurrent() }
        assertEquals(1, cache.data<Int>(QueryKey.ChatMedia(1)))
        assertFalse(cache.prefetch(QueryKey.ChatMedia(1), 1_000) { 9 }, "fresh, nothing to fetch")
    }

    @Test
    fun aRefreshedFirstPageKeepsThePagesBelowIt() {
        // Kept: 100 down to 61, then a second page down to 21.
        val old = ChatGrid(
            items = (100L downTo 21L).map(::video),
            cursors = MediaCursors(video = 21, document = 21, animation = 21),
            endReached = false,
            scrollIndex = 30,
        )
        // Asked again: 102 and 101 posted, 99 deleted; the first page reaches 61.
        val fresh = MediaPage(
            items = (listOf(102L, 101L, 100L) + (98L downTo 61L)).map(::video),
            cursors = MediaCursors(video = 61, document = 61, animation = 61),
            endReached = false,
        )
        val merged = ChatGrid.refreshed(old, fresh)
        val ids = merged.items.map { it.messageId }
        assertEquals(102L, ids.first())
        assertFalse(99L in ids, "deleted within the first page")
        assertEquals(21L, ids.last(), "the second page is still listed")
        assertEquals(21L, merged.cursors.video, "and scrolling carries on below it")
        assertEquals(30, merged.scrollIndex)
    }

    @Test
    fun aFirstPageThatDoesNotMeetTheKeptOneStandsAlone() {
        val old = ChatGrid(items = (50L downTo 41L).map(::video), cursors = MediaCursors(video = 41), endReached = false)
        // So much was posted that the first page ends above the kept listing's newest.
        val fresh = MediaPage(items = (200L downTo 161L).map(::video), cursors = MediaCursors(video = 161), endReached = false)
        val merged = ChatGrid.refreshed(old, fresh)
        assertEquals((200L downTo 161L).toList(), merged.items.map { it.messageId })
        assertEquals(161L, merged.cursors.video)
    }

    @Test
    fun aWholeChatInOnePageReplacesTheKeptOne() {
        val old = ChatGrid(items = (10L downTo 1L).map(::video), cursors = MediaCursors(), endReached = true)
        val done = MediaCursors(videoDone = true, documentDone = true, animationDone = true)
        val fresh = MediaPage(items = listOf(11L, 10L, 8L).map(::video), cursors = done, endReached = true)
        val merged = ChatGrid.refreshed(old, fresh)
        assertEquals(listOf(11L, 10L, 8L), merged.items.map { it.messageId })
        assertTrue(merged.endReached)
    }
}
