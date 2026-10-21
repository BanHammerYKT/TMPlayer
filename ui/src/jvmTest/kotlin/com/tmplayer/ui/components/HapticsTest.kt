package com.tmplayer.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which buzz each moment gets on each Android, and when nothing is played at all. */
class HapticsTest {

    @Test
    fun `the newest constants are used where the platform has them`() {
        val u = Haptics.API_U
        assertEquals(Haptics.CONFIRM, Haptics.constant(Haptic.Confirm, u))
        assertEquals(Haptics.REJECT, Haptics.constant(Haptic.Reject, u))
        assertEquals(Haptics.TOGGLE_ON, Haptics.constant(Haptic.ToggleOn, u))
        assertEquals(Haptics.TOGGLE_OFF, Haptics.constant(Haptic.ToggleOff, u))
        assertEquals(Haptics.SEGMENT_TICK, Haptics.constant(Haptic.Tick, u))
        assertEquals(Haptics.SEGMENT_FREQUENT_TICK, Haptics.constant(Haptic.FrequentTick, u))
        assertEquals(Haptics.GESTURE_START, Haptics.constant(Haptic.GestureStart, u))
        assertEquals(Haptics.GESTURE_END, Haptics.constant(Haptic.GestureEnd, u))
    }

    @Test
    fun `Android 11 to 13 keep confirm and reject but fall back for toggles and ticks`() {
        val r = Haptics.API_R
        assertEquals(Haptics.CONFIRM, Haptics.constant(Haptic.Confirm, r))
        assertEquals(Haptics.REJECT, Haptics.constant(Haptic.Reject, r))
        assertEquals(Haptics.VIRTUAL_KEY, Haptics.constant(Haptic.ToggleOn, 33))
        assertEquals(Haptics.CLOCK_TICK, Haptics.constant(Haptic.ToggleOff, 33))
        assertEquals(Haptics.CLOCK_TICK, Haptics.constant(Haptic.Tick, 33))
        assertEquals(Haptics.TEXT_HANDLE_MOVE, Haptics.constant(Haptic.FrequentTick, 33))
    }

    @Test
    fun `the oldest supported Android still feels every moment but a gesture's end`() {
        val o = 26
        Haptic.entries.filter { it != Haptic.GestureEnd }.forEach { event ->
            val constant = Haptics.constant(event, o)
            assertTrue("$event plays nothing on API $o", constant != null && constant >= 0)
            // Nothing from API 30 or later may reach an older phone.
            assertTrue("$event plays $constant on API $o", constant!! < Haptics.GESTURE_START)
        }
        assertEquals(Haptics.VIRTUAL_KEY, Haptics.constant(Haptic.Confirm, o))
        assertEquals(Haptics.CLOCK_TICK, Haptics.constant(Haptic.FrequentTick, o))
        assertNull(Haptics.constant(Haptic.GestureEnd, o))
    }

    @Test
    fun `a rejection never feels like a success, on any Android`() {
        for (sdk in 26..37) {
            assertNotEquals(Haptics.constant(Haptic.Confirm, sdk), Haptics.constant(Haptic.Reject, sdk))
            assertNotEquals(Haptics.constant(Haptic.ToggleOn, sdk), Haptics.constant(Haptic.ToggleOff, sdk))
        }
    }

    @Test
    fun `presses, keys and long presses are the same on every Android`() {
        for (sdk in 26..37) {
            assertEquals(Haptics.VIRTUAL_KEY, Haptics.constant(Haptic.Tap, sdk))
            assertEquals(Haptics.KEYBOARD_TAP, Haptics.constant(Haptic.KeyTap, sdk))
            assertEquals(Haptics.LONG_PRESS, Haptics.constant(Haptic.LongPress, sdk))
        }
    }

    @Test
    fun `a phone with a vibrator and Vibrate on tap plays`() {
        assertTrue(Haptics.allowed(enabled = true, television = false, hasVibrator = true))
    }

    @Test
    fun `a television plays nothing, even with a vibrator reported`() {
        assertFalse(Haptics.allowed(enabled = true, television = true, hasVibrator = true))
        assertFalse(Haptics.allowed(enabled = true, television = true, hasVibrator = false))
    }

    @Test
    fun `a device with no vibrator plays nothing`() {
        assertFalse(Haptics.allowed(enabled = true, television = false, hasVibrator = false))
    }

    @Test
    fun `Vibrate on tap off plays nothing`() {
        assertFalse(Haptics.allowed(enabled = false, television = false, hasVibrator = true))
    }
}
