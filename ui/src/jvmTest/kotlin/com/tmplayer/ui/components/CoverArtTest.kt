package com.tmplayer.ui.components

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a file with no art is given a cover: its colours, its number, its title. */
class CoverArtTest {

    @Test
    fun `a title always gets the same pair, in any chat`() {
        val first = CoverArt.of(chatId = -100123456789L, name = "Algebra basics, lecture 3.mp4")
        val again = CoverArt.of(chatId = -100123456789L, name = "Algebra basics, lecture 3.mp4")
        assertEquals(first.pair, again.pair)
        assertEquals(CoverArt.pairIndex("Harbour Notes"), CoverArt.pairIndex("  harbour  notes "))
        assertEquals(CoverArt.of(1L, "Harbour.Notes.2024.mkv").pair, CoverArt.of(-100987L, "Harbour Notes (2024).mp4").pair)
        assertTrue(CoverArt.pairIndex("Anything") in CoverArt.Pairs.indices)
    }

    @Test
    fun `every episode of a show shares its colour`() {
        val one = CoverArt.of(7L, "Harbour.Notes.S01E03.1080p.mkv")
        val two = CoverArt.of(7L, "Harbour.Notes.S01E04.720p.mkv")
        assertEquals("Harbour Notes", one.title)
        assertEquals(one.pair, two.pair)
    }

    @Test
    fun `the pairs are spread, not all one colour`() {
        val used = (1..200).map { CoverArt.pairIndex("Course part $it") }.toSet()
        assertEquals(CoverArt.Pairs.size, used.size)
    }

    @Test
    fun `white text reads at 4,5 to 1 or better on every stop`() {
        for ((start, end) in CoverArt.Pairs) {
            for (stop in listOf(start, end)) {
                assertTrue("$stop gives ${contrast(Color.White, stop)}", contrast(Color.White, stop) >= 4.5f)
            }
        }
    }

    @Test
    fun `the number comes out of a lecture, a part and an episode`() {
        assertEquals(7, CoverArt.of(1L, "Lecture 07 Vectors.mp4").number)
        assertEquals(4, CoverArt.of(1L, "Part 4.mkv").number)
        assertEquals(3, CoverArt.of(1L, "Harbour.Notes.S01E03.mkv").number)
        assertEquals(12, CoverArt.numeral("Organic_Chemistry_Chapter_12.mp4"))
        assertEquals(2, CoverArt.numeral("Coast walk, day 2"))
    }

    @Test
    fun `a name with no counting word has no number`() {
        assertNull(CoverArt.of(1L, "Birthday highlights.mp4").number)
        assertNull(CoverArt.numeral("Watch 3 friends cook"))
        assertNull(CoverArt.numeral("Screen recording 2026-10-13.mp4"))
    }

    @Test
    fun `the chat initial is given only when asked for`() {
        assertNull(CoverArt.of(1L, "Part 1.mp4").chatInitial)
        assertEquals("W", CoverArt.of(1L, "Part 1.mp4", chatTitle = "weekend clips").chatInitial)
    }

    private fun contrast(a: Color, b: Color): Float {
        val (light, dark) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (light + 0.05f) / (dark + 0.05f)
    }
}
