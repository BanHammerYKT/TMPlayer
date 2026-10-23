package com.tmplayer.ui.browse

import com.tmplayer.data.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DetailBackStackTest {

    private fun video(message: Long, versions: List<Long> = emptyList()): MediaItem {
        val base = MediaItem(1, message, message.toInt(), "v$message", 0, 0, "video/mp4", 0, null, 0)
        return if (versions.isEmpty()) base else base.copy(versions = (listOf(message) + versions).map { base.copy(messageId = it) })
    }

    private fun stack(cap: Int = DetailBackStack.MAX_PAGES) = DetailBackStack<MediaItem>({ it }, cap)

    private fun DetailBackStack<MediaItem>.shown() = entries.map { it.page.messageId }

    @Test
    fun backWalksDownThroughThePagesThenCloses() {
        val pages = stack()
        pages.open(video(1))
        pages.push(video(2))
        pages.push(video(3))
        assertEquals(listOf(1L, 2L, 3L), pages.shown())
        assertTrue(pages.canGoBack)

        assertTrue(pages.back())
        assertEquals(listOf(1L, 2L), pages.shown())
        // The page Back came down to puts the remote on the tile it was left by.
        assertEquals("1:3", pages.top?.returnTo?.toId)

        assertTrue(pages.back())
        assertEquals("1:2", pages.top?.returnTo?.toId)
        assertFalse(pages.canGoBack)

        assertFalse(pages.back())
        assertNull(pages.top)
    }

    @Test
    fun openStartsAfresh() {
        val pages = stack()
        pages.open(video(1))
        pages.push(video(2))
        pages.open(video(5))
        assertEquals(listOf(5L), pages.shown())
        assertNull(pages.top?.returnTo)
    }

    @Test
    fun aVideoAlreadyOnTheStackIsGoneBackTo() {
        val pages = stack()
        pages.open(video(1))
        pages.push(video(2))
        pages.push(video(3))
        pages.push(video(1))
        assertEquals(listOf(1L), pages.shown())
        // As if Back had been pressed twice: the remote goes to the tile page 2 was opened by.
        assertEquals("1:2", pages.top?.returnTo?.toId)
    }

    @Test
    fun theVideoShowingIsNotStackedAgain() {
        val pages = stack()
        pages.open(video(1))
        pages.push(video(2))
        pages.push(video(2))
        assertEquals(listOf(1L, 2L), pages.shown())
    }

    @Test
    fun anotherCopyOfAStackedFilmCountsAsThatFilm() {
        val pages = stack()
        pages.open(video(1, versions = listOf(7)))
        pages.push(video(2))
        pages.push(video(7))
        assertEquals(listOf(1L), pages.shown())
    }

    @Test
    fun turningToACopyKeepsTheTileThePageWasOpenedBy() {
        val pages = stack()
        pages.open(video(1))
        pages.push(video(2, versions = listOf(8)))
        pages.turn(video(8))
        assertEquals(listOf(1L, 8L), pages.shown())
        assertTrue(pages.back())
        // Page 1's "More like this" showed 2, the copy it was opened as.
        assertEquals("1:2", pages.top?.returnTo?.toId)
    }

    @Test
    fun aLongChainKeepsOnlyTheLastPages() {
        val pages = stack(cap = 3)
        pages.open(video(1))
        (2L..6L).forEach { pages.push(video(it)) }
        assertEquals(listOf(4L, 5L, 6L), pages.shown())
        assertTrue(pages.back())
        assertTrue(pages.back())
        assertFalse(pages.back())
        assertNull(pages.top)
    }

    @Test
    fun pushOnNothingOpens() {
        val pages = stack()
        pages.push(video(4))
        assertEquals(listOf(4L), pages.shown())
        assertFalse(pages.canGoBack)
    }

    @Test
    fun theReturnIsSettledOnce() {
        val back = DetailReturn("1:2")
        assertTrue(back.claim())
        assertTrue(back.landed)
        assertFalse(back.claim())

        val late = DetailReturn("1:3")
        late.giveUp()
        assertFalse(late.claim())
        assertFalse(late.landed)
    }
}
