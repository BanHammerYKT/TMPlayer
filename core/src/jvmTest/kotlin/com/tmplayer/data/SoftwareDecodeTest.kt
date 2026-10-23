package com.tmplayer.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoftwareDecodeTest {

    @Test
    fun `av1 in software is heavy on a weak device from 720p, cinema crops included`() {
        assertTrue(SoftwareDecode.heavy(SoftwareDecode.MIME_AV1, 1920, 1080, hardware = false, weak = true))
        assertTrue(SoftwareDecode.heavy(SoftwareDecode.MIME_AV1, 1280, 536, hardware = false, weak = true))
        assertTrue(SoftwareDecode.heavy(SoftwareDecode.MIME_AV1, 0, 0, hardware = false, weak = true))
        assertFalse(SoftwareDecode.heavy(SoftwareDecode.MIME_AV1, 854, 480, hardware = false, weak = true))
    }

    @Test
    fun `other formats in software are heavy from 1080p`() {
        assertTrue(SoftwareDecode.heavy(SoftwareDecode.MIME_HEVC, 1920, 800, hardware = false, weak = true))
        assertTrue(SoftwareDecode.heavy(SoftwareDecode.MIME_VP9, 3840, 2160, hardware = false, weak = true))
        assertFalse(SoftwareDecode.heavy(SoftwareDecode.MIME_HEVC, 1280, 720, hardware = false, weak = true))
        // A size not known yet is no reason for anything but AV1.
        assertFalse(SoftwareDecode.heavy(SoftwareDecode.MIME_HEVC, 0, 0, hardware = false, weak = true))
    }

    @Test
    fun `hardware, a strong device or no picture is never heavy`() {
        assertFalse(SoftwareDecode.heavy(SoftwareDecode.MIME_AV1, 1920, 1080, hardware = true, weak = true))
        assertFalse(SoftwareDecode.heavy(SoftwareDecode.MIME_AV1, 1920, 1080, hardware = false, weak = false))
        assertFalse(SoftwareDecode.heavy(null, 1920, 1080, hardware = false, weak = true))
        assertFalse(SoftwareDecode.heavy("image/jpeg", 1920, 1080, hardware = false, weak = true))
    }

    @Test
    fun `a television or a phone short of memory counts as weak`() {
        assertTrue(SoftwareDecode.weak(tv = true, fit = DeviceFit.Fine))
        assertTrue(SoftwareDecode.weak(tv = false, fit = DeviceFit(shortOfMemory = true, shortOfStorage = false)))
        assertFalse(SoftwareDecode.weak(tv = false, fit = DeviceFit(shortOfMemory = false, shortOfStorage = true)))
    }

    @Test
    fun `the note is said once per video`() {
        assertTrue(SoftwareDecode.noteOnce(91, 1))
        assertFalse(SoftwareDecode.noteOnce(91, 1))
        assertTrue(SoftwareDecode.noteOnce(91, 2))
    }

    @Test
    fun `a heavy upload is remembered for the next open`() {
        assertFalse(SoftwareDecode.knownHeavy(92, 1))
        SoftwareDecode.rememberHeavy(92, 1)
        assertTrue(SoftwareDecode.knownHeavy(92, 1))
    }

    @Test
    fun `4k in software on a weak device is unwatchable, 1080p is only heavy`() {
        assertTrue(SoftwareDecode.unwatchable(SoftwareDecode.MIME_HEVC, 3840, 2160, hardware = false, weak = true))
        assertTrue(SoftwareDecode.unwatchable(SoftwareDecode.MIME_HEVC, 3840, 1600, hardware = false, weak = true))
        assertTrue(SoftwareDecode.unwatchable(SoftwareDecode.MIME_AV1, 3840, 2076, hardware = false, weak = true))
        assertFalse(SoftwareDecode.unwatchable(SoftwareDecode.MIME_HEVC, 1920, 1080, hardware = false, weak = true))
        assertFalse(SoftwareDecode.unwatchable(SoftwareDecode.MIME_HEVC, 3840, 2160, hardware = true, weak = true))
        assertFalse(SoftwareDecode.unwatchable(SoftwareDecode.MIME_HEVC, 3840, 2160, hardware = false, weak = false))
    }
}
