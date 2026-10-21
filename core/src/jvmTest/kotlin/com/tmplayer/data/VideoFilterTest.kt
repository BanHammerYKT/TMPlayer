package com.tmplayer.data

import com.tmplayer.data.VideoSorting.Progress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class VideoFilterTest {

    private var nextId = 1L

    private fun video(fileName: String, date: Int, onDevice: Boolean = false, height: Int = 0) = MediaItem(
        chatId = 3,
        messageId = nextId++,
        fileId = nextId.toInt(),
        title = fileName,
        sizeBytes = 100,
        durationSec = 0,
        mimeType = "video/mp4",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = date,
        fileName = fileName,
        onDevice = onDevice,
        width = if (height > 0) height * 16 / 9 else 0,
        height = height,
    )

    private val film = video("Coast Run (2014) 1080p.mkv", date = 90)
    private val filmSd = video("Anchor Point (2012) 480p.mkv", date = 80, onDevice = true)
    private val stated4k = video("Quiet Field (2019).mkv", date = 70, height = 2160)
    private val ep1 = video("Harbour.Notes.S01E01.720p.mkv", date = 60)
    private val ep2 = video("Harbour.Notes.S01E02.720p.mkv", date = 50)
    private val copy720 = video("Long Bay (2016) 720p.mkv", date = 40)
    private val copy1080 = video("Long Bay (2016) 1080p.mkv", date = 30)

    private val entries = SeriesShelf.arrange(listOf(film, filmSd, stated4k, ep1, ep2, copy720, copy1080), emptyMap())

    private fun names(filter: VideoFilter, progress: Map<Long, Progress> = emptyMap()) =
        VideoFiltering.filter(entries, filter) { progress[it.messageId] ?: Progress.Untouched }.map { entry ->
            when (entry) {
                is ShelfEntry.Show -> "show"
                is ShelfEntry.File -> SeriesShelf.posterTitle(entry.item)
            }
        }

    @Test
    fun `all keeps every entry in its order`() {
        assertEquals(entries, VideoFiltering.filter(entries, VideoFilter.All))
    }

    @Test
    fun `shows and single videos split the listing`() {
        assertEquals(listOf("show"), names(VideoFilter.Shows))
        assertEquals(entries.size - 1, names(VideoFilter.Singles).size)
    }

    @Test
    fun `a show is watched only when every episode is, and in progress between`() {
        val half = mapOf(ep1.messageId to Progress.Watched)
        assertEquals(listOf("show"), names(VideoFilter.InProgress, half))
        assertFalse("show" in names(VideoFilter.Watched, half))
        assertFalse("show" in names(VideoFilter.Unwatched, half))
        val all = mapOf(ep1.messageId to Progress.Watched, ep2.messageId to Progress.Watched)
        assertEquals(listOf("show"), names(VideoFilter.Watched, all))
    }

    @Test
    fun `unwatched, in progress and watched never overlap`() {
        val progress = mapOf(film.messageId to Progress.InProgress, filmSd.messageId to Progress.Watched, ep2.messageId to Progress.InProgress)
        val total = listOf(VideoFilter.Unwatched, VideoFilter.InProgress, VideoFilter.Watched).sumOf { names(it, progress).size }
        assertEquals(entries.size, total)
    }

    @Test
    fun `on this device passes a video with a whole copy here`() {
        assertEquals(listOf(SeriesShelf.posterTitle(filmSd)), names(VideoFilter.OnDevice))
    }

    @Test
    fun `1080p and up reads the name or the stated size, and any copy will do`() {
        val hd = names(VideoFilter.HighDefinition)
        assertEquals(3, hd.size)
        assertFalse("show" in hd)
        assertFalse(SeriesShelf.posterTitle(filmSd) in hd)
    }

    @Test
    fun `a stored filter reads back, an unknown one is all`() {
        for (filter in VideoFilter.entries) assertEquals(filter, VideoFilter.decode(filter.name))
        assertEquals(VideoFilter.All, VideoFilter.decode("Sideways"))
        assertEquals(VideoFilter.All, VideoFilter.decode(null))
    }
}
