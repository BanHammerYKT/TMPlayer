package com.tmplayer.web

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The web player's rules as the worker calls them: plain JS objects in, plain objects out, so they
 * read nothing through Kotlin's own classes and cross postMessage as they are.
 */
class WebRulesTest {

    private val mb = 1024.0 * 1024

    private fun raw(
        id: Int,
        fileName: String,
        size: Double = 700 * mb,
        kind: String = "document",
        caption: String? = null,
        canBeSaved: Boolean = true,
        selfDestructs: Boolean = false,
        withheld: Boolean = false,
        albumId: String? = null,
    ): RawVideo {
        val o: dynamic = js("({})")
        o.chatId = -1001234567890.0
        o.messageId = id.toDouble()
        o.kind = kind
        o.fileName = fileName
        o.mimeType = if (fileName.endsWith(".pdf")) "application/pdf" else "video/x-matroska"
        o.caption = caption
        o.size = size
        o.duration = 0.0
        o.width = 0.0
        o.height = 0.0
        o.date = 1_700_000_000.0 + id
        o.topicId = 0.0
        o.albumId = albumId
        o.canBeSaved = canBeSaved
        o.selfDestructs = selfDestructs
        o.withheld = withheld
        return o.unsafeCast<RawVideo>()
    }

    /** Has only its own fields, as postMessage's structured clone keeps them. */
    private fun isPlain(value: Any?) {
        val proto: dynamic = js("Object.getPrototypeOf(value)")
        assertTrue(proto == js("Object.prototype"), "a plain object")
    }

    @Test
    fun `a page keeps the videos, drops the rest and counts the self destructing`() {
        val page = screen(
            arrayOf(
                raw(1, "Harbour.Notes.S01E01.1080p.mkv"),
                raw(2, "notes.pdf"),
                raw(3, "Harbour.Notes.S01E02.mkv", selfDestructs = true),
                raw(4, "Harbour.Notes.S01E03.mkv", withheld = true),
                raw(5, "Harbour.Notes.S01E04.mkv", canBeSaved = false),
            ),
        )
        assertEquals(listOf("-1001234567890:1", "-1001234567890:5"), page.videos.map { it.id })
        assertEquals(1, page.selfDestructing)
        assertTrue(page.videos[0].canBeSaved)
        assertFalse(page.videos[1].canBeSaved)
        isPlain(page)
        isPlain(page.videos[0])
    }

    @Test
    fun `episodes fold into one show and copies into one film`() {
        val videos = screen(
            arrayOf(
                raw(1, "Harbour.Notes.S01E01.720p.mkv", size = 400 * mb),
                raw(2, "Harbour.Notes.S01E02.720p.mkv", size = 410 * mb),
                raw(3, "Quiet.Valley.2019.720p.mkv", size = 900 * mb),
                raw(4, "Quiet.Valley.2019.1080p.mkv", size = 1900 * mb),
            ),
        ).videos
        val shelf = shelf(videos, "Newest", 50 * mb, 8 * 1024 * mb, emptyArray(), emptyArray())
        assertEquals(listOf("show", "file"), shelf.tiles.map { it.kind })
        val show = shelf.tiles[0]
        assertEquals(listOf("S01E01", "S01E02"), show.seasons.flatMap { it.episodes.toList() }.map { it.code }.sorted())
        assertEquals(2, shelf.tiles[1].copies.size)
        assertEquals(0, shelf.hiddenBySize)
        isPlain(show)
        isPlain(show.seasons[0].episodes[0])
    }

    @Test
    fun `the size limits hide what is outside them and say how many`() {
        val videos = screen(arrayOf(raw(1, "A.mkv", size = 10 * mb), raw(2, "B.mkv", size = 0.0), raw(3, "C.mkv"))).videos
        val shelf = shelf(videos, "Name", 50 * mb, 8 * 1024 * mb, emptyArray(), emptyArray())
        assertEquals(1, shelf.hiddenBySize)
        assertEquals(listOf("B", "C"), shelf.tiles.map { it.title })
    }

    @Test
    fun `next and previous episode`() {
        val videos = screen((1..3).map { raw(it, "Harbour.Notes.S01E0$it.mkv") }.toTypedArray()).videos
        val steps = steps(videos[1], videos)
        assertEquals(videos[0].id, steps.previous?.id)
        assertEquals(videos[2].id, steps.next?.id)
        assertEquals("S01E02", steps.current?.code)
        assertEquals("show", steps.series?.kind)
    }

    @Test
    fun `a name is parsed as the apps parse it`() {
        val name = parse("Harbour.Notes.S02E05.1080p.WEB-DL.mkv", null)
        assertEquals("Harbour Notes", name.title)
        assertEquals(2, name.season)
        assertEquals(5, name.episode)
        assertEquals("S02E05", name.code)
        val film = parse("Quiet.Valley.2019.720p.mkv", null)
        assertEquals(2019, film.year)
        assertNull(film.code)
    }

    @Test
    fun `fuzzy search forgives a typo and ranks the whole phrase first`() {
        val texts = arrayOf("Quiet Valley", "Harbour Notes", "Harbor Notes Extra")
        assertContentEquals(arrayOf(1, 2), rank(texts, "harbour notes"))
        assertContentEquals(arrayOf(0, 1, 2), rank(texts, " "))
    }

    @Test
    fun `saving rules, resume rewind and the size slider`() {
        assertEquals("StreamOnly", protection(canBeSaved = true, chatProtected = true, selfDestructs = false))
        assertEquals("Hidden", protection(canBeSaved = true, chatProtected = false, selfDestructs = true))
        assertEquals(57_000.0, resumeStart(60_000.0, 3_000.0))
        assertEquals(5_000.0, resumeStart(5_000.0, 3_000.0))
        assertEquals(60_000.0, resumeKept(60_000.0, 57_200.0, 3_000.0))
        assertEquals(3_000.0, rewindSetting(7.0))
        val limits = sizeLimits()
        assertEquals(50 * mb, limits.defaultMin)
        assertEquals(100 * mb, sizeStep(limits.defaultMin, 1))
        assertEquals(0.05, sizeFraction(limits.defaultMin), 0.0001)
        assertEquals(limits.ceiling, sizeAt(1.0))
        assertTrue(sizeMatches(0.0, limits.defaultMin, limits.ceiling))
    }
}
