package com.tmplayer.data

import com.tmplayer.data.VideoSorting.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Collator
import java.util.Locale

class VideoSortTest {

    private var nextId = 1L

    private fun video(fileName: String, date: Int, size: Long = 0, duration: Int = 0) = MediaItem(
        chatId = 3,
        messageId = nextId++,
        fileId = nextId.toInt(),
        title = fileName,
        sizeBytes = size,
        durationSec = duration,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = date,
        fileName = fileName,
    )

    // Newest first, as a chat hands them over.
    private val harbour10 = video("The Harbour Part 10 (2015).mkv", date = 90, size = 900)
    private val showNew = video("Harbour.Notes.S01E02.mkv", date = 80, size = 300)
    private val coast = video("Coast Run (2014).mkv", date = 70, size = 0)
    private val anchor = video("anchor point.mp4", date = 60, size = 2000)
    private val harbour2 = video("Harbour Part 2 (2015).mkv", date = 50, size = 900)
    private val showOld = video("Harbour.Notes.S01E01.mkv", date = 10, size = 300)

    private val entries = SeriesShelf.arrange(listOf(harbour10, showNew, coast, anchor, harbour2, showOld), emptyMap())

    private fun names(sort: VideoSort, progress: (MediaItem) -> Progress = { Progress.Untouched }) =
        VideoSorting.sort(entries, sort, progress, Locale.ENGLISH).map { entry ->
            when (entry) {
                is ShelfEntry.Show -> "show"
                is ShelfEntry.File -> entry.item.fileName
            }
        }

    @Test
    fun `newest is the chat's own order`() {
        assertEquals(listOf("The Harbour Part 10 (2015).mkv", "show", "Coast Run (2014).mkv", "anchor point.mp4", "Harbour Part 2 (2015).mkv"), names(VideoSort.Newest))
    }

    @Test
    fun `oldest puts a show where its first episode was posted`() {
        assertEquals("show", names(VideoSort.Oldest).first())
        assertEquals("The Harbour Part 10 (2015).mkv", names(VideoSort.Oldest).last())
    }

    @Test
    fun `names ignore case and a leading article and count numbers as numbers`() {
        assertEquals(
            listOf("anchor point.mp4", "Coast Run (2014).mkv", "show", "Harbour Part 2 (2015).mkv", "The Harbour Part 10 (2015).mkv"),
            names(VideoSort.Name),
        )
    }

    @Test
    fun `year puts the newest first, ties by name, unknown last`() {
        val sorted = names(VideoSort.Year)
        assertEquals(listOf("Harbour Part 2 (2015).mkv", "The Harbour Part 10 (2015).mkv", "Coast Run (2014).mkv"), sorted.take(3))
        assertTrue(sorted.indexOf("anchor point.mp4") > 2)
    }

    @Test
    fun `size puts the largest first and unknown sizes last, a show by its total`() {
        assertEquals(listOf("anchor point.mp4", "The Harbour Part 10 (2015).mkv", "Harbour Part 2 (2015).mkv", "show", "Coast Run (2014).mkv"), names(VideoSort.Size))
    }

    @Test
    fun `longest puts the longest first, a show by its episodes added up, unknown lengths last`() {
        val long = video("Long Walk (2010).mkv", date = 9, duration = 7200)
        val short = video("Short Walk (2011).mkv", date = 8, duration = 1800)
        val unknown = video("Mystery (2012).mkv", date = 7, duration = 0)
        val epA = video("Walk.Show.S01E01.mkv", date = 6, duration = 3000)
        val epB = video("Walk.Show.S01E02.mkv", date = 5, duration = 3000)
        val shelf = SeriesShelf.arrange(listOf(unknown, short, epA, long, epB), emptyMap())
        val sorted = VideoSorting.sort(shelf, VideoSort.Longest, locale = Locale.ENGLISH).map {
            when (it) {
                is ShelfEntry.Show -> "show"
                is ShelfEntry.File -> it.item.fileName
            }
        }
        assertEquals(listOf("Long Walk (2010).mkv", "show", "Short Walk (2011).mkv", "Mystery (2012).mkv"), sorted)
    }

    @Test
    fun `unwatched first, then in progress, then watched, ties in chat order`() {
        val progress = mapOf(harbour10.id to Progress.Watched, coast.id to Progress.InProgress, showOld.id to Progress.Watched)
        val sorted = names(VideoSort.Unwatched) { progress[it.id] ?: Progress.Untouched }
        assertEquals(listOf("anchor point.mp4", "Harbour Part 2 (2015).mkv", "show", "Coast Run (2014).mkv", "The Harbour Part 10 (2015).mkv"), sorted)
    }

    @Test
    fun `only newest is whole before the chat is read to the end`() {
        assertFalse(VideoSorting.sortsLoadedOnly(VideoSort.Newest, endReached = false))
        assertTrue(VideoSorting.sortsLoadedOnly(VideoSort.Oldest, endReached = false))
        assertFalse(VideoSorting.sortsLoadedOnly(VideoSort.Name, endReached = true))
    }

    @Test
    fun `a stored sort reads back, an unknown one is newest`() {
        for (sort in VideoSort.entries) assertEquals(sort, VideoSort.decode(sort.name))
        assertEquals(VideoSort.Newest, VideoSort.decode("Sideways"))
        assertEquals(VideoSort.Newest, VideoSort.decode(null))
    }

    @Test
    fun `natural order of names`() {
        val collator = Collator.getInstance(Locale.ENGLISH).apply { strength = Collator.PRIMARY }
        assertTrue(VideoSorting.compareNames("Part 2", "Part 10", collator) < 0)
        assertTrue(VideoSorting.compareNames("éclair", "Eclipse", collator) < 0)
        assertEquals(0, VideoSorting.compareNames("The Run", "run", collator))
    }
}
