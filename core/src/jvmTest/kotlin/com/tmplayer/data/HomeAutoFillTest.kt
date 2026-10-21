package com.tmplayer.data

import com.tmplayer.online.MetaImageSize
import com.tmplayer.online.MetaPoster
import com.tmplayer.online.MetaRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Home filled from the chats with videos, its movies and shows apart, its cache, and title logos. */
class HomeAutoFillTest {

    private var nextId = 1L

    private fun plainName(n: Long) = "Holiday " + ('a' + (n % 26).toInt()) + ('a' + (n / 26 % 26).toInt()) + ".mp4"

    private fun video(chatId: Long, fileName: String = plainName(nextId), date: Int = nextId.toInt()) = MediaItem(
        chatId = chatId, messageId = nextId++, fileId = 1, title = fileName, sizeBytes = 1, durationSec = 60,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = date, fileName = fileName,
    )

    private fun chat(id: Long, archived: Boolean = false) = ChatSummary(id, "Chat $id", null, 0, ChatKind.Channel, isArchived = archived)

    @Test
    fun `Home picks the chats with the most videos after the starred ones`() {
        val chats = listOf(chat(1), chat(2), chat(3), chat(4, archived = true), chat(5))
        val recent = listOf(video(2), video(3), video(3), video(3), video(4), video(4), video(4), video(4), video(9))
        val home = HomeRows.homeChats(chats, favourites = setOf(5), recent = recent)
        // Starred first; then by count; the archived chat and one not in the list are never picked.
        assertEquals(listOf(5L, 3L, 2L), home.map { it.id })
        // Before the search answers, only the starred chats.
        assertEquals(listOf(5L), HomeRows.homeChats(chats, setOf(5), recent = null).map { it.id })
        assertEquals(1, HomeRows.homeChats(chats, emptySet(), recent, limit = 1).size)
    }

    @Test
    fun `nothing starred and the search still running holds the carousel's place`() {
        val rows = HomeRows.build(emptyList(), emptyList(), emptyMap(), recent = null, recentMinStarred = 1, autoFill = true)
        val recent = rows.single() as HomeRow.Recent
        assertFalse(recent.loaded)
        // Without auto fill an empty Home is the first-run state.
        assertTrue(HomeRows.build(emptyList(), emptyList(), emptyMap(), recent = null, recentMinStarred = 1).isEmpty())
    }

    @Test
    fun `movies and shows never share a row`() {
        val a = video(1, "Big Buck Bunny (2008).mkv")
        val b = video(1, "Harbour Notes S01E01.mkv")
        val c = video(1, "Harbour Notes S01E02.mkv")
        val d = video(2, "Sintel (2010).mkv")
        val e = video(2, "Lecture one.mp4")
        val chats = listOf(chat(1), chat(2))
        val loaded = mapOf(1L to listOf(a, b, c), 2L to listOf(d, e))
        val rows = HomeRows.build(
            emptyList(), chats, loaded, recent = listOf(a, b, c, d, e), recentMinStarred = 1,
            starred = emptySet(), autoFill = true,
        )
        val recent = rows.filterIsInstance<HomeRow.Recent>().single()
        assertFalse("not every chat is starred", recent.starredOnly)
        val across = rows.filterIsInstance<HomeRow.Across>()
        assertEquals(listOf(HomeRow.Across.Kind.Videos, HomeRow.Across.Kind.Shows), across.map { it.kind })
        assertEquals("right under the carousel", listOf("recent", "across-videos", "across-shows"), rows.take(3).map { it.key })
        across.forEach { row -> assertTrue(row.entries.all { HomeRows.kindOf(it) == row.kind }) }
        val chatRows = rows.filterIsInstance<HomeRow.Chat>()
        chatRows.forEach { row -> assertTrue(row.entries.all { HomeRows.kindOf(it) == row.kind }) }
        // Chat 1 has both, so two rows keyed apart; chat 2 only movies and videos.
        assertEquals(listOf("chat-1", "chat-1-more", "chat-2"), chatRows.map { it.key })
        // Films come first among the videos.
        val videos = across.first { it.kind == HomeRow.Across.Kind.Videos }.entries.map { (it as ShelfEntry.File).item.messageId }
        assertEquals(e.messageId, videos.last())
    }

    @Test
    fun `the cache gives back what it was given`() {
        val dir = Files.createTempDirectory("tm-home-cache").toFile()
        HomeCache.file = java.io.File(dir, "home.json")
        try {
            val item = video(7, "Sintel (2010).mkv").copy(miniThumbnail = byteArrayOf(1, 2, 3), versions = listOf(video(7)), locality = MediaItem.Locality.Downloaded, onDevice = true)
            val snapshot = HomeCache.Snapshot(mapOf(7L to listOf(item)), listOf(item), setOf(7L), mapOf("7:1" to item), 1234L)
            val decoded = HomeCache.decode(HomeCache.encode(snapshot))
            assertNotNull(decoded)
            assertEquals(snapshot, decoded)
            HomeCache.save(snapshot)
            assertTrue(HomeCache.file!!.isFile)
            HomeCache.clear()
            assertNull(HomeCache.load())
            assertFalse(HomeCache.file!!.exists())
            assertNull("an unreadable file is no cache", HomeCache.decode("{not json"))
        } finally {
            HomeCache.file = null
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a logo in the UI language beats English, and only PNGs that read as a line`() {
        fun logo(path: String, lang: String?, aspect: Double = 3.0, width: Int = 500, votes: Double = 5.0) =
            MetaPoster(path = path, language = lang, aspect = aspect, width = width, voteAverage = votes)
        val logos = listOf(
            logo("/en.png", "en", votes = 9.0),
            logo("/ml.png", "ml"),
            logo("/ml.svg", "ml", votes = 10.0),
            logo("/tall.png", "ml", aspect = 0.9, votes = 10.0),
            logo("/small.png", "ml", width = 100, votes = 10.0),
        )
        assertEquals("/ml.png", MetaRules.logo(logos, "ml-IN")?.path)
        assertEquals("/en.png", MetaRules.logo(logos, "de")?.path)
        assertNull(MetaRules.logo(listOf(logo("/x.svg", "en")), "en"))
    }

    @Test
    fun `the small stand-in copy of a TMDB picture`() {
        assertEquals("https://image.tmdb.org/t/p/w92/a.jpg", MetaImageSize.tiny("https://image.tmdb.org/t/p/w342/a.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w300/b.jpg", MetaImageSize.tiny("https://image.tmdb.org/t/p/w780/b.jpg"))
        assertEquals("https://image.tmdb.org/t/p/w300/b.jpg", MetaImageSize.tiny("https://image.tmdb.org/t/p/original/b.jpg"))
        assertNull(MetaImageSize.tiny("https://image.tmdb.org/t/p/w92/a.jpg"))
        assertNull(MetaImageSize.tiny("https://static.tvmaze.com/uploads/images/medium_portrait/1/1.jpg"))
    }

    @Test
    fun `a card's warm-up hint reaches the warmer once, and nothing without one`() {
        val told = mutableListOf<Long>()
        PlaybackWarmup.warm(video(1))
        PlaybackWarmup.warmer = PlaybackWarmup.Warmer { told += it.messageId }
        try {
            val a = video(1)
            PlaybackWarmup.warm(a)
            PlaybackWarmup.warm(a)
            val b = video(1)
            PlaybackWarmup.warm(b)
            assertEquals(listOf(a.messageId, b.messageId), told)
        } finally {
            PlaybackWarmup.warmer = null
        }
    }
}
