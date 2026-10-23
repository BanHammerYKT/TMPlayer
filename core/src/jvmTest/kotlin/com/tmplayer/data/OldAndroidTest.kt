package com.tmplayer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android 6 and 7 (API 23 to 25): what is left out there, and how a notification weighs. */
class OldAndroidTest {

    @Test
    fun `the TV provider's rows start at Android 8, and a platform that is not Android is not held back`() {
        assertFalse(OldAndroid.hasTvProviderRows(23))
        assertFalse(OldAndroid.hasTvProviderRows(25))
        assertTrue(OldAndroid.hasTvProviderRows(26))
        assertTrue(OldAndroid.hasTvProviderRows(35))
        assertTrue(OldAndroid.hasTvProviderRows(null))
    }

    @Test
    fun `an Android TV before 8 has no Play next row to offer, a newer one keeps it`() {
        // The Fire TV Stick 4K on Fire OS 6 is Android 7.1; Fire TV never had the row anyway.
        assertFalse(RemoteQuirks.of(FireOs.Kind.None, tv = true, sdk = 25).watchNextSupported)
        assertFalse(RemoteQuirks.of(FireOs.Kind.None, tv = true, sdk = 23).watchNextSupported)
        assertTrue(RemoteQuirks.of(FireOs.Kind.None, tv = true, sdk = 26).watchNextSupported)
        assertFalse(RemoteQuirks.of(FireOs.Kind.Tv, tv = true, sdk = 25).watchNextSupported)
        assertFalse(RemoteQuirks.of(FireOs.Kind.None, tv = false, sdk = 34).watchNextSupported)
    }

    @Test
    fun `the rest of the remote's handling does not depend on the Android version`() {
        assertEquals(
            RemoteQuirks.of(FireOs.Kind.Tv, tv = true, sdk = 25),
            RemoteQuirks.of(FireOs.Kind.Tv, tv = true, sdk = 30),
        )
        val old = RemoteQuirks.of(FireOs.Kind.None, tv = true, sdk = 24)
        val new = RemoteQuirks.of(FireOs.Kind.None, tv = true, sdk = 34)
        assertEquals(new.menuOpensPlayerMenu, old.menuOpensPlayerMenu)
        assertEquals(new.holdSeekAccelerates, old.holdSeekAccelerates)
        assertEquals(new.voiceRoute(recognizerInstalled = true), old.voiceRoute(recognizerInstalled = true))
    }

    @Test
    fun `a channel's importance becomes the matching priority before channels existed`() {
        assertEquals(OldAndroid.PRIORITY_MIN, OldAndroid.priorityFor(OldAndroid.IMPORTANCE_MIN))
        assertEquals(OldAndroid.PRIORITY_MIN, OldAndroid.priorityFor(0))
        assertEquals(OldAndroid.PRIORITY_LOW, OldAndroid.priorityFor(OldAndroid.IMPORTANCE_LOW))
        assertEquals(OldAndroid.PRIORITY_DEFAULT, OldAndroid.priorityFor(OldAndroid.IMPORTANCE_DEFAULT))
        assertEquals(OldAndroid.PRIORITY_HIGH, OldAndroid.priorityFor(OldAndroid.IMPORTANCE_HIGH))
        assertEquals(OldAndroid.PRIORITY_HIGH, OldAndroid.priorityFor(5))
    }

    @Test
    fun `a quiet channel stays silent, the default one still makes its sound`() {
        assertFalse(OldAndroid.soundFor(OldAndroid.IMPORTANCE_MIN))
        assertFalse(OldAndroid.soundFor(OldAndroid.IMPORTANCE_LOW))
        assertTrue(OldAndroid.soundFor(OldAndroid.IMPORTANCE_DEFAULT))
        assertTrue(OldAndroid.soundFor(OldAndroid.IMPORTANCE_HIGH))
    }
}
