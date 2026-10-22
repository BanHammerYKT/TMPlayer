package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where subtitles stand while the row is up. The numbers are a 1080 px television (48 dp off the
 * edge at 2x is 96 px, a row 340 px tall, the play disc's foot at 612) and a phone held sideways.
 */
class SubtitleLiftTest {

    private val tvTextBottom = 1080 - 96
    private val tvRowTop = 1080 - 340

    @Test
    fun `with the row down the lines stay where they are`() {
        val place = SubtitleLift.place(false, false, tvTextBottom, tvRowTop, reach = 340, room = 10, gap = 16, tv = true)
        assertEquals(SubtitleLift.Place(0, hidden = false), place)
    }

    @Test
    fun `a television's lines sit just over the raised row instead of fading`() {
        // Room for one line under the disc: 984 - 54 - 16 - 612 = 302, more than the 260 needed.
        val place = SubtitleLift.place(true, false, tvTextBottom, tvRowTop, reach = 340, room = 302, gap = 16, tv = true)
        assertFalse(place.hidden)
        assertEquals(tvTextBottom + 16 - tvRowTop, place.lift)
    }

    @Test
    fun `a television climbs only as far as clearing the row takes, not the row's whole height`() {
        val place = SubtitleLift.place(true, false, tvTextBottom, tvRowTop, reach = 340, room = null, gap = 16, tv = true)
        assertEquals(260, place.lift)
    }

    @Test
    fun `lines already clear of the row do not move`() {
        val place = SubtitleLift.place(true, false, textBottom = 600, rowTop = tvRowTop, reach = 340, room = 302, gap = 16, tv = true)
        assertEquals(SubtitleLift.Place(0, hidden = false), place)
    }

    @Test
    fun `with no room at all between the row and the centre buttons the lines give way`() {
        val place = SubtitleLift.place(true, false, tvTextBottom, tvRowTop, reach = 340, room = 120, gap = 16, tv = true)
        assertTrue(place.hidden)
    }

    @Test
    fun `an open track picker keeps them showing, since it previews them`() {
        val place = SubtitleLift.place(true, true, tvTextBottom, tvRowTop, reach = 340, room = 120, gap = 16, tv = true)
        assertFalse(place.hidden)
    }

    @Test
    fun `a phone climbs the row's whole reach where there is room`() {
        val place = SubtitleLift.place(true, false, textBottom = 1000, rowTop = 880, reach = 200, room = 400, gap = 16, tv = false)
        assertEquals(SubtitleLift.Place(200, hidden = false), place)
    }

    @Test
    fun `a phone short of room climbs what it can and still shows the lines once they clear the row`() {
        // 136 clears the row; the centre buttons allow 150 of the 200.
        val place = SubtitleLift.place(true, false, textBottom = 1000, rowTop = 880, reach = 200, room = 150, gap = 16, tv = false)
        assertEquals(SubtitleLift.Place(150, hidden = false), place)
    }
}
