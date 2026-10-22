package com.tmplayer.data

import com.tmplayer.data.StorageChart.Part
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StorageChartTest {

    private val gb = 1024L * 1024 * 1024
    private val mb = 1024L * 1024

    private fun figures(
        downloads: Long = 0,
        unfinished: Long = 0,
        recent: Long = 0,
        unmatched: Long = 0,
        pictures: Long = 0,
        total: Long = 32 * gb,
        free: Long = 10 * gb,
    ) = StorageChart.Figures(downloads, unfinished, recent, unmatched, pictures, total, free)

    @Test
    fun `other apps is the used space that is not TMPlayer's`() {
        val f = figures(downloads = 4 * gb, recent = 2 * gb, pictures = 500 * mb)
        assertEquals(4 * gb + 2 * gb + 500 * mb, f.app)
        assertEquals(32 * gb - 10 * gb - f.app, f.otherApps)
        assertEquals(32 * gb, f.whole)
    }

    @Test
    fun `figures read a moment apart never make other apps negative`() {
        val f = figures(downloads = 30 * gb, free = 10 * gb)
        assertEquals(0L, f.otherApps)
        assertEquals(40 * gb, f.whole)
    }

    @Test
    fun `slices go round once, in the legend's order, from the top`() {
        val f = figures(downloads = 4 * gb, unfinished = 1 * gb, recent = 2 * gb, unmatched = 2 * gb, pictures = 1 * gb)
        val slices = StorageChart.slices(f)
        assertEquals(listOf(Part.Downloads, Part.Unfinished, Part.Recent, Part.Unmatched, Part.Pictures, Part.OtherApps, Part.Free), slices.map { it.part })
        assertEquals(0f, slices.first().startDeg, 0.001f)
        assertEquals(360f, slices.sumOf { it.sweepDeg.toDouble() }.toFloat(), 0.01f)
        slices.zipWithNext().forEach { (a, b) -> assertEquals(a.endDeg, b.startDeg, 0.01f) }
    }

    @Test
    fun `a part under one per cent is still a visible sliver`() {
        val f = figures(downloads = 10 * gb, pictures = 20 * mb)
        val pictures = StorageChart.slices(f).single { it.part == Part.Pictures }
        assertEquals(StorageChart.MIN_SWEEP_DEG, pictures.sweepDeg, 0.001f)
        assertEquals(360f, StorageChart.slices(f).sumOf { it.sweepDeg.toDouble() }.toFloat(), 0.01f)
    }

    @Test
    fun `an empty part is neither drawn nor listed`() {
        val f = figures(downloads = 1 * gb)
        assertFalse(Part.Unmatched in f.shown)
        assertFalse(StorageChart.slices(f).any { it.part == Part.Unmatched })
    }

    @Test
    fun `with the disk unknown the ring is TMPlayer's own parts`() {
        val f = figures(downloads = 3 * gb, recent = 1 * gb, total = 0, free = 0)
        assertEquals(listOf(Part.Downloads, Part.Recent), f.shown)
        assertEquals(270f, StorageChart.slices(f).first().sweepDeg, 0.01f)
    }

    @Test
    fun `many tiny parts share the floor without overrunning the ring`() {
        val sweeps = StorageChart.sweeps(listOf(1000.0, 1.0, 1.0, 1.0, 1.0), 4.5)
        assertEquals(360.0, sweeps.sum(), 0.001)
        assertEquals(4.5, sweeps[1], 0.001)
        assertEquals(360.0 - 4 * 4.5, sweeps[0], 0.001)
    }

    @Test
    fun `a tap finds the slice under it`() {
        val slices = StorageChart.slices(figures(downloads = 16 * gb, free = 16 * gb))
        assertEquals(Part.Downloads, StorageChart.sliceAt(slices, 10f)?.part)
        assertEquals(Part.Free, StorageChart.sliceAt(slices, 350f)?.part)
        assertEquals(Part.Downloads, StorageChart.sliceAt(slices, -350f)?.part)
    }

    @Test
    fun `nothing measured is nothing drawn`() {
        assertTrue(StorageChart.slices(figures(total = 0, free = 0)).isEmpty())
    }
}

class FreeUpSpaceTest {

    private val gb = 1024L * 1024 * 1024

    @Test
    fun `short means asked for room, under three gigabytes, or under a tenth of the disk`() {
        assertTrue(FreeUpSpace.isShort(100 * gb, 200 * gb, askedForRoom = true))
        assertTrue(FreeUpSpace.isShort(2 * gb, 200 * gb, askedForRoom = false))
        assertTrue(FreeUpSpace.isShort(15 * gb, 200 * gb, askedForRoom = false))
        assertFalse(FreeUpSpace.isShort(50 * gb, 200 * gb, askedForRoom = false))
        assertFalse(FreeUpSpace.isShort(0, 0, askedForRoom = false))
    }
}
