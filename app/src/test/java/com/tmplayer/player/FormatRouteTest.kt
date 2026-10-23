package com.tmplayer.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatRouteTest {

    private fun dolbyVision(codecs: String, height: Int = 2160) = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_DOLBY_VISION)
        .setCodecs(codecs)
        .setWidth(3840)
        .setHeight(height)
        .build()

    @Test
    fun readsTheProfileFromEveryDolbyVisionTag() {
        assertEquals(7, FormatRoute.dolbyVisionProfile("dvhe.07.06"))
        assertEquals(8, FormatRoute.dolbyVisionProfile("dvh1.08.06"))
        assertEquals(5, FormatRoute.dolbyVisionProfile("DVHE.05.09"))
        assertEquals(9, FormatRoute.dolbyVisionProfile("dva1.09.05"))
        assertNull(FormatRoute.dolbyVisionProfile("hvc1.2.4.L153.B0"))
        assertNull(FormatRoute.dolbyVisionProfile("dvhe"))
        assertNull(FormatRoute.dolbyVisionProfile(null))
    }

    @Test
    fun baseLayerIsHevcForFourSevenEightAndAvcForNine() {
        assertEquals(MimeTypes.VIDEO_H265, FormatRoute.dolbyVisionBaseMime("dvhe.04.06"))
        assertEquals(MimeTypes.VIDEO_H265, FormatRoute.dolbyVisionBaseMime("dvhe.07.06"))
        assertEquals(MimeTypes.VIDEO_H265, FormatRoute.dolbyVisionBaseMime("dvh1.08.06"))
        assertEquals(MimeTypes.VIDEO_H264, FormatRoute.dolbyVisionBaseMime("dvav.09.05"))
        // Profile 5 has no picture a plain decoder shows right; AV1 profile 10 is Media3's call.
        assertNull(FormatRoute.dolbyVisionBaseMime("dvhe.05.06"))
        assertNull(FormatRoute.dolbyVisionBaseMime("dav1.10.09"))
    }

    @Test
    fun profileSevenOnADeviceWithoutDolbyVisionBecomesItsHevcBaseLayer() {
        val format = dolbyVision("dvhe.07.06")
        val routed = FormatRoute.forDevice(format, playsAsDolbyVision = { false }, baseCodecs = { "hvc1.2.4.L153.B0" })
        assertEquals(MimeTypes.VIDEO_H265, routed.sampleMimeType)
        assertEquals("hvc1.2.4.L153.B0", routed.codecs)
        assertEquals(3840, routed.width)
        assertEquals(2160, routed.height)
    }

    @Test
    fun withoutParameterSetsTheBaseLayerIsStillAskedForAsMainTen() {
        val big = FormatRoute.forDevice(dolbyVision("dvh1.08.06"), { false }, { null })
        assertEquals("hvc1.2.4.L153.B0", big.codecs)
        val small = FormatRoute.forDevice(dolbyVision("dvh1.08.03", height = 1080), { false }, { "" })
        assertEquals("hvc1.2.4.L123.B0", small.codecs)
        val avc = FormatRoute.forDevice(dolbyVision("dvav.09.05", height = 1080), { false }, { null })
        assertEquals(MimeTypes.VIDEO_H264, avc.sampleMimeType)
        assertNull(avc.codecs)
    }

    @Test
    fun aDeviceThatShowsDolbyVisionKeepsIt() {
        val format = dolbyVision("dvhe.07.06")
        assertSame(format, FormatRoute.forDevice(format, { true }, { "hvc1.2.4.L153.B0" }))
    }

    @Test
    fun profileFiveAndOtherFormatsAreLeftAlone() {
        val five = dolbyVision("dvhe.05.06")
        assertSame(five, FormatRoute.forDevice(five, { false }, { null }))
        assertTrue(FormatRoute.dolbyVisionOnly(five))
        val hevc = Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H265).setCodecs("hvc1.2.4.L153.B0").build()
        assertSame(hevc, FormatRoute.forDevice(hevc, { error("not asked") }, { error("not asked") }))
        assertFalse(FormatRoute.dolbyVisionOnly(hevc))
        assertFalse(FormatRoute.dolbyVisionOnly(dolbyVision("dvh1.08.06")))
    }

    @Test
    fun aFailureSwitchesByItselfOncePerTrackType() {
        assertTrue(switches(C.TRACK_TYPE_VIDEO, toFfmpeg = true))
        assertTrue(switches(C.TRACK_TYPE_AUDIO, toFfmpeg = true))
        assertTrue(switches(C.TRACK_TYPE_VIDEO, toFfmpeg = false))
        assertFalse(switches(C.TRACK_TYPE_VIDEO, toFfmpeg = false, already = setOf(C.TRACK_TYPE_VIDEO)))
        assertTrue(switches(C.TRACK_TYPE_AUDIO, toFfmpeg = true, already = setOf(C.TRACK_TYPE_VIDEO)))
        assertFalse(switches(C.TRACK_TYPE_TEXT, toFfmpeg = true))
    }

    @Test
    fun neverToSoftwareForAPictureTooLargeForItNorOnceThePictureHasPlayed() {
        assertFalse(switches(C.TRACK_TYPE_VIDEO, toFfmpeg = true, tooSlow = true))
        // Back to the device's decoder is still worth a try.
        assertTrue(switches(C.TRACK_TYPE_VIDEO, toFfmpeg = false, tooSlow = true))
        assertFalse(switches(C.TRACK_TYPE_VIDEO, toFfmpeg = true, started = true))
        // A Dolby track refused part way, after a change of soundtrack, still switches.
        assertTrue(switches(C.TRACK_TYPE_AUDIO, toFfmpeg = true, started = true))
    }

    @Test
    fun subtitleBytesInACodePageArriveAsUtf8() {
        val cyrillic = "Привет"
        val cp1251 = cyrillic.toByteArray(charset("windows-1251"))
        val fixed = SubtitleEncoding.asUtf8(cp1251, 0, cp1251.size, "ru")
        assertArrayEquals(cyrillic.toByteArray(Charsets.UTF_8), fixed)
        val utf8 = cyrillic.toByteArray(Charsets.UTF_8)
        assertNull(SubtitleEncoding.asUtf8(utf8, 0, utf8.size, "ru"))
        // Only the given slice is read.
        val padded = byteArrayOf(0x41) + cp1251 + byteArrayOf(0x42)
        assertArrayEquals(cyrillic.toByteArray(Charsets.UTF_8), SubtitleEncoding.asUtf8(padded, 1, cp1251.size, "ru"))
        assertNull(SubtitleEncoding.asUtf8(ByteArray(0), 0, 0, "ru"))
    }

    private fun switches(
        type: Int,
        toFfmpeg: Boolean,
        already: Set<Int> = emptySet(),
        tooSlow: Boolean = false,
        started: Boolean = false,
    ) = FormatRoute.switchesByItself(type, toFfmpeg, already, tooSlow, started)
}
