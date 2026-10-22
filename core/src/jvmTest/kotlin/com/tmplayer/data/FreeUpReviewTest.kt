package com.tmplayer.data

import com.tmplayer.data.FreeUpReview.Group
import com.tmplayer.data.FreeUpReview.Item
import com.tmplayer.data.FreeUpReview.Lock
import com.tmplayer.data.FreeUpReview.Section
import com.tmplayer.data.FreeUpReview.Tick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FreeUpReviewTest {

    private val mb = 1024L * 1024

    private val unfinished = Section(
        Group.Unfinished, 0,
        items = listOf(Item("u1", "Paused", 300 * mb), Item("u2", "Coming down", 200 * mb, Lock.Downloading)),
    )
    private val recent = Section(Group.Recent, 900 * mb, count = 2, names = listOf("A", "B"))
    private val pictures = Section(Group.Pictures, 40 * mb)
    private val downloads = Section(
        Group.Downloads, 0,
        items = listOf(Item("d1", "Film", 2_000 * mb), Item("d2", "Episode", 800 * mb), Item("d3", "Open", 500 * mb, Lock.Playing)),
    )
    private val all = listOf(downloads, pictures, recent, unfinished)

    @Test
    fun `caches and unfinished parts start ticked, downloads never do`() {
        val picks = FreeUpReview.initial(all)
        assertEquals(Tick.On, FreeUpReview.tick(picks, unfinished))
        assertEquals(Tick.On, FreeUpReview.tick(picks, recent))
        assertEquals(Tick.On, FreeUpReview.tick(picks, pictures))
        assertEquals(Tick.Off, FreeUpReview.tick(picks, downloads))
        assertEquals(300 * mb + 900 * mb + 40 * mb, FreeUpReview.bytes(picks, all))
        assertFalse(FreeUpReview.asksFirst(picks, all))
    }

    @Test
    fun `a download in progress or playing is listed but never ticked`() {
        var picks = FreeUpReview.initial(all)
        picks = FreeUpReview.toggleGroup(picks, downloads)
        assertEquals(Tick.On, FreeUpReview.tick(picks, downloads))
        assertEquals(listOf("d1", "d2"), FreeUpReview.picked(picks, downloads).map { it.key })
        assertEquals(FreeUpReview.toggleItem(picks, unfinished, "u2"), picks)
        assertEquals(300 * mb, unfinished.freeable)
        assertTrue(FreeUpReview.asksFirst(picks, all))
    }

    @Test
    fun `one download ticked makes the group partly ticked, and the group's tick then takes all`() {
        var picks = FreeUpReview.initial(all)
        picks = FreeUpReview.toggleItem(picks, downloads, "d2")
        assertEquals(Tick.Some, FreeUpReview.tick(picks, downloads))
        assertEquals(800 * mb, FreeUpReview.bytes(picks, downloads))
        picks = FreeUpReview.toggleGroup(picks, downloads)
        assertEquals(Tick.On, FreeUpReview.tick(picks, downloads))
        picks = FreeUpReview.toggleGroup(picks, downloads)
        assertEquals(Tick.Off, FreeUpReview.tick(picks, downloads))
    }

    @Test
    fun `a group cleared as a whole ticks as one`() {
        var picks = FreeUpReview.initial(all)
        assertTrue(FreeUpReview.wholePicked(picks, recent))
        picks = FreeUpReview.toggleGroup(picks, recent)
        assertFalse(FreeUpReview.wholePicked(picks, recent))
        assertEquals(0L, FreeUpReview.bytes(picks, recent))
    }

    @Test
    fun `nothing to free hides empty groups and says so`() {
        val empty = listOf(Section(Group.Recent, 0), Section(Group.Pictures, 0), Section(Group.Downloads, 0))
        assertTrue(FreeUpReview.nothingToFree(empty))
        assertTrue(FreeUpReview.shown(empty).isEmpty())
        assertEquals(0L, FreeUpReview.bytes(FreeUpReview.initial(empty), empty))
        // Only a download coming down: listed, so the sheet can say it is kept, but nothing to free.
        val busy = listOf(Section(Group.Unfinished, 0, items = listOf(Item("u", "x", mb, Lock.Downloading))))
        assertTrue(FreeUpReview.nothingToFree(busy))
        assertEquals(1, FreeUpReview.shown(busy).size)
    }

    @Test
    fun `groups are listed safest first and downloads last`() {
        assertEquals(listOf(Group.Unfinished, Group.Recent, Group.Pictures, Group.Downloads), FreeUpReview.shown(all).map { it.group })
    }

    @Test
    fun `a fresh measurement keeps the viewer's ticks and never ticks a new download`() {
        var picks = FreeUpReview.initial(all)
        picks = FreeUpReview.toggleGroup(picks, recent)
        picks = FreeUpReview.toggleItem(picks, downloads, "d1")
        val after = listOf(
            downloads.copy(items = downloads.items + Item("d4", "New", mb)),
            Section(Group.Unmatched, 0, items = listOf(Item("s1", "stray.mp4", 70 * mb))),
            recent, unfinished.copy(items = unfinished.items.drop(1)),
        )
        val carried = FreeUpReview.carry(picks, all, after)
        assertFalse(FreeUpReview.wholePicked(carried, recent))
        assertEquals(listOf("d1"), FreeUpReview.picked(carried, after[0]).map { it.key })
        assertEquals(Tick.On, FreeUpReview.tick(carried, after[1]))
        assertEquals(Tick.Off, FreeUpReview.tick(carried, after[3]))
    }
}
