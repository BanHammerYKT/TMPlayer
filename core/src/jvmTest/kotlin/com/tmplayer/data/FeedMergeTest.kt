package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Several chats read a page at a time into one list that only grows at its foot, and a Home row's See all page. */
class FeedMergeTest {

    private fun video(chatId: Long, messageId: Long, date: Int, fileName: String = "Holiday.mp4") = MediaItem(
        chatId = chatId, messageId = messageId, fileId = messageId.toInt(), title = fileName, sizeBytes = 1, durationSec = 60,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = date, fileName = fileName,
    )

    /** A chat's videos, newest first, served [size] at a time as Telegram would. */
    private class Chat(val id: Long, val videos: List<MediaItem>) {
        fun page(cursors: MediaCursors, size: Int): MediaPage {
            val from = cursors.video.toInt()
            val items = videos.drop(from).take(size)
            val next = from + items.size
            return MediaPage(items, MediaCursors(video = next.toLong()), endReached = next >= videos.size)
        }
    }

    @Test
    fun `a video is shown once every open chat has been read back past it`() {
        val lanes = listOf(FeedLane(1, oldest = 50), FeedLane(2, oldest = 80), FeedLane(3, oldest = 10, done = true))
        assertEquals(80, FeedMerge.horizon(lanes))
        val items = listOf(video(1, 1, 90), video(2, 1, 85), video(1, 2, 80), video(1, 3, 60), video(3, 1, 20))
        // Only what is newer than the horizon: chat 2 may still hold something from 80 down to 60.
        assertEquals(listOf("1:1", "2:1"), FeedMerge.settled(items, lanes).map { it.id })
        // The chat holding the horizon back is read next.
        assertEquals(2L, FeedMerge.next(lanes)?.chatId)
        // A chat not read at all holds everything back, and is read first.
        val unread = lanes + FeedLane(4)
        assertEquals(Int.MAX_VALUE, FeedMerge.horizon(unread))
        assertTrue(FeedMerge.settled(items, unread).isEmpty())
        assertEquals(4L, FeedMerge.next(unread)?.chatId)
        // Every chat read to its end: everything is in its place.
        val ended = lanes.map { it.copy(done = true) }
        assertNull(FeedMerge.horizon(ended))
        assertEquals(5, FeedMerge.settled(items, ended).size)
        assertNull(FeedMerge.next(ended))
    }

    @Test
    fun `reading on only ever adds at the foot, and ends with every video newest first`() {
        val a = Chat(1, (0 until 23).map { video(1, 100L - it, 1000 - it * 7) })
        val b = Chat(2, (0 until 11).map { video(2, 200L - it, 990 - it * 19) })
        val c = Chat(3, (0 until 4).map { video(3, 300L - it, 1003 - it * 50) })
        val chats = listOf(a, b, c).associateBy { it.id }
        var lanes = chats.keys.map { FeedLane(it) }
        val read = ArrayList<MediaItem>()
        var shown = emptyList<MediaItem>()
        var requests = 0
        while (true) {
            val lane = FeedMerge.next(lanes) ?: break
            val page = chats.getValue(lane.chatId).page(lane.cursors, size = 5)
            requests++
            lanes = FeedMerge.advance(lanes, lane.chatId, page)
            read += page.items
            val now = FeedMerge.settled(read, lanes)
            // What was on screen stays where it was: the new list starts with the old one.
            assertEquals(shown.map { it.id }, now.take(shown.size).map { it.id })
            shown = now
            assertTrue("runs away", requests < 100)
        }
        val all = chats.values.flatMap { it.videos }.sortedWith(FeedMerge.newestFirst)
        assertEquals(all.map { it.id }, shown.map { it.id })
    }

    @Test
    fun `an empty page that is not the end moves the cursors, not the date`() {
        val lane = FeedLane(1, oldest = 40)
        val moved = FeedMerge.advance(listOf(lane), 1, MediaPage(emptyList(), MediaCursors(video = 40), endReached = false)).single()
        assertEquals(40, moved.oldest)
        assertEquals(40L, moved.cursors.video)
        assertTrue(!moved.done)
    }

    @Test
    fun `lanes start where Home's read stopped, or after what Home holds`() {
        val reads = mapOf(1L to FeedLane(1, MediaCursors(video = 20), oldest = 30))
        val held = mapOf(1L to listOf(video(1, 1, 35)), 2L to listOf(video(2, 1, 70), video(2, 2, 60)))
        val lanes = FeedMerge.lanes(listOf(1, 2, 3, 1), reads, held)
        assertEquals(listOf(1L, 2L, 3L), lanes.map { it.chatId })
        assertEquals(MediaCursors(video = 20), lanes[0].cursors)
        assertEquals(30, lanes[0].oldest)
        // Read from the start again, but what Home holds already counts as read.
        assertEquals(MediaCursors(), lanes[1].cursors)
        assertEquals(60, lanes[1].oldest)
        assertNull(lanes[2].oldest)
    }

    @Test
    fun `See all keeps the row's tiles first and adds the rest of its kind, newest first`() {
        val film = video(1, 1, 100, "Night Train (1999) 1080p.mkv").copy(durationSec = 100 * 60)
        val older = video(1, 2, 50, "Harbour Lights (2004) 720p.mkv").copy(durationSec = 100 * 60)
        val newer = video(2, 3, 120, "Paper Moon (1973) 1080p.mkv").copy(durationSec = 100 * 60)
        val episode = video(2, 4, 110, "Quiet Hills S01E02 1080p.mkv")
        val row = HomeRow.Across(HomeRow.Across.Kind.Videos, listOf(ShelfEntry.File(film)), chatIds = listOf(1, 2))
        val page = HomeRows.seeAll(row, listOf(older, newer, episode, film))!!
        // The row's own tile first; then the newest first; the show's episode is not a video.
        assertEquals(listOf("1:1", "2:3", "1:2"), page.map { (it as ShelfEntry.File).item.id })
        // A row that reads no chats has no such page.
        assertNull(HomeRows.seeAll(HomeRow.NewEpisodes(emptyList()), listOf(film)))
    }

    @Test
    fun `Home's merged rows carry the chats they draw from`() {
        val chats = listOf(
            ChatSummary(1, "One", null, 0, ChatKind.Channel),
            ChatSummary(2, "Two", null, 0, ChatKind.Channel),
        )
        val film = video(1, 1, 100, "Night Train (1999) 1080p.mkv").copy(durationSec = 100 * 60)
        val rows = HomeRows.build(emptyList(), chats, mapOf(1L to listOf(film), 2L to emptyList()), recent = emptyList(), autoFill = true)
        val across = rows.filterIsInstance<HomeRow.Across>().single()
        assertEquals(listOf(1L, 2L), across.chatIds)
    }
}
