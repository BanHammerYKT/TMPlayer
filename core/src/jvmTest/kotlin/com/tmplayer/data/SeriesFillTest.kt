package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** A show's page made whole from a search of its chat. */
class SeriesFillTest {

    private fun episode(chatId: Long, n: Int, show: String = "Quiet Hills") = MediaItem(
        chatId = chatId, messageId = 100L + n, fileId = n, title = "$show S01E%02d 1080p.mkv".format(n), sizeBytes = 900L * 1024 * 1024,
        durationSec = 2400, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 1000 + n,
        fileName = "$show S01E%02d 1080p.mkv".format(n),
    )

    private fun show(items: List<MediaItem>): Series =
        (SeriesShelf.arrange(items.sortedWith(FeedMerge.newestFirst)).single() as ShelfEntry.Show).series

    @Test
    fun `the episodes a search finds are folded into the show`() {
        val opened = show(listOf(episode(1, 5), episode(1, 6)))
        assertEquals("Quiet Hills", SeriesFill.queryOf(opened))
        val found = (1..4).map { episode(1, it) } + episode(1, 3, show = "Other Show") + episode(2, 1)
        val whole = SeriesFill.merge(opened, found)
        assertEquals(opened.key, whole.key)
        assertEquals(listOf(1, 2, 3, 4, 5, 6), whole.episodes.map { it.episode })
    }

    @Test
    fun `a search that finds nothing of it leaves the show as it was`() {
        val opened = show(listOf(episode(1, 5), episode(1, 6)))
        assertSame(opened, SeriesFill.merge(opened, emptyList()))
        assertEquals(opened.episodeCount, SeriesFill.merge(opened, listOf(episode(1, 2, show = "Other Show"))).episodeCount)
    }
}
