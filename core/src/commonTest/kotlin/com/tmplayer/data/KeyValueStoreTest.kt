package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The storage the browser build keeps its lists in, on Node and the JVM alike. */
class KeyValueStoreTest {

    @Test
    fun theCodecKeepsEveryType() {
        val entries = mapOf<String, Any>(
            "s" to "Harbour \"Notes\"\n", "b" to true, "i" to 7, "l" to 9_007_199_254_740_993L,
            "f" to 1.25f, "d" to 2.5, "S" to setOf("1", "2"),
        )
        assertEquals(entries, KeyValueCodec.decode(KeyValueCodec.encode(entries)))
        assertTrue(KeyValueCodec.decode("not json").isEmpty())
        assertTrue(KeyValueCodec.decode(null).isEmpty())
    }

    @Test
    fun editsArePersistedAndSeen() = runTest {
        var saved = ""
        val kv = MemoryKeyValueStore(persist = { saved = KeyValueCodec.encode(it) })
        kv.edit { it["a"] = 1L }
        assertEquals(mapOf<String, Any>("a" to 1L), kv.data.first())
        assertEquals(mapOf<String, Any>("a" to 1L), KeyValueCodec.decode(saved))
        kv.edit { it.remove("a") }
        assertTrue(kv.snapshot().isEmpty())
    }

    private fun record(messageId: Long, at: Long, manual: Boolean = false) = WatchedRecord(
        chatId = -100L, messageId = messageId, fileId = 7, title = "Ep $messageId", chatTitle = "Show",
        sizeBytes = 1_000, durationSec = 1_400, watchedAt = at, manual = manual,
    )

    @Test
    fun watchedAndWatchLaterShareOneFile() = runTest {
        ChatGate.set(null)
        val book = WatchedBook(MemoryKeyValueStore())
        book.watchLater.add(WatchLaterRecord.of(record(1, 0).toMediaItem(), "Show", addedAt = 5))
        assertTrue(book.watchLater.contains(-100L, 1L))
        book.markWatched(record(1, at = 10, manual = true))
        book.markWatched(record(1, at = 20))
        assertFalse(book.watchLater.contains(-100L, 1L))
        val kept = book.history.first().single()
        assertTrue(kept.manual)
        assertEquals(20L, kept.watchedAt)
        book.clear()
        assertTrue(book.history.first().isEmpty())
    }

    @Test
    fun favouritesAndContinueWatching() = runTest {
        ChatGate.set(null)
        val settings = SettingsBook(MemoryKeyValueStore())
        assertTrue(settings.toggleFavorite(3L))
        assertEquals(setOf(3L), settings.favorites.first())
        val item = record(4, 0).toMediaItem()
        settings.saveResumePosition(-100L, 4L, 60_000, 1_400_000, settings.describe(item, "Show"))
        assertTrue(settings.continueWatching.first().isEmpty(), "under three minutes is not a place")
        settings.saveResumePosition(-100L, 4L, 600_000, 1_400_000, settings.describe(item, "Show"))
        assertEquals(listOf(4L), settings.continueWatching.first().map { it.messageId })
        settings.saveResumePosition(-100L, 4L, 1_390_000, 1_400_000)
        assertTrue(settings.continueWatching.first().isEmpty(), "the credits forget the place")
    }
}
