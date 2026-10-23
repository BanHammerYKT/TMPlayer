package com.tmplayer.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The viewer's own downloads are measured only for a refusal. Measuring them walks every download
 * and can ask Telegram about each message, so a press of Play that fits must never pay for it.
 */
class RoomOnDiskTest {

    @Test
    fun aPlayThatFitsNeverMeasuresTheDownloads() = runTest {
        var measured = 0
        val plan = RoomOnDisk.quoteKept(CacheShelf.Plan.Proceed) { measured++; 5L }
        assertEquals(CacheShelf.Plan.Proceed, plan)
        assertEquals(0, measured)
    }

    @Test
    fun anEvictionNeverMeasuresTheDownloads() = runTest {
        var measured = 0
        val evict = CacheShelf.Plan.Evict(listOf(1, 2), 300L)
        val plan = RoomOnDisk.quoteKept(evict) { measured++; 5L }
        assertEquals(evict, plan)
        assertEquals(0, measured)
    }

    @Test
    fun aRefusalQuotesWhatTheDownloadsHold() = runTest {
        var measured = 0
        val refusal = CacheShelf.Plan.NotEnoughSpace(shortfallBytes = 100L, reclaimBytes = 0L, fitsInLowStorage = true)
        val plan = RoomOnDisk.quoteKept(refusal) { measured++; 4_000L }
        assertEquals(refusal.copy(reclaimBytes = 4_000L), plan)
        assertEquals(1, measured)
    }
}
