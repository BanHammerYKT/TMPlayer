package com.tmplayer.ui.components

import com.tmplayer.ui.browse.ScrollLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The fast scroller's track and the letters its bubble files names under. */
class FastScrollMathTest {

    @Test
    fun `the track and the list map onto each other both ways`() {
        // 250 rows, 8 on screen: 242 rows to scroll through.
        assertEquals(0f, FastScrollMath.progress(0f, 250f, 8f), 0.0001f)
        assertEquals(0.5f, FastScrollMath.progress(121f, 250f, 8f), 0.0001f)
        assertEquals(1f, FastScrollMath.progress(400f, 250f, 8f), 0.0001f)
        assertEquals(121f, FastScrollMath.line(0.5f, 250f, 8f), 0.0001f)
        for (f in listOf(0f, 0.1f, 0.37f, 0.9f, 1f)) {
            assertEquals(f, FastScrollMath.progress(FastScrollMath.line(f, 250f, 8f), 250f, 8f), 0.0001f)
        }
    }

    @Test
    fun `a list that fits stands at the top`() {
        assertEquals(0f, FastScrollMath.progress(0f, 5f, 8f), 0.0001f)
        assertEquals(0f, FastScrollMath.line(0.7f, 5f, 8f), 0.0001f)
    }

    @Test
    fun `names are filed by their first letter, numbers under one sign`() {
        assertEquals("A", ScrollLabels.letter("  avatar"))
        assertEquals("É", ScrollLabels.letter("école"))
        assertEquals("#", ScrollLabels.letter("1917"))
        assertEquals("B", ScrollLabels.letter("[b] side"))
        assertNull(ScrollLabels.letter("  "))
    }
}
