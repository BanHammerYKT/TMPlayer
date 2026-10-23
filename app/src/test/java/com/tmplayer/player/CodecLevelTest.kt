package com.tmplayer.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CodecLevelTest {

    private fun video(mime: String, codecs: String, width: Int, height: Int): Format =
        Format.Builder().setSampleMimeType(mime).setCodecs(codecs).setWidth(width).setHeight(height).build()

    @Test
    fun hevcClaimingLevel51At1080pIsAskedAt41() {
        val relaxed = CodecLevel.relaxed(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.90", 1920, 1080))
        assertEquals("hvc1.2.4.L123.90", relaxed?.codecs)
    }

    @Test
    fun hevcHighTierBecomesMainTier() {
        assertEquals("hev1.1.6.L123.B0", CodecLevel.hevc("hev1.1.6.H150.B0"))
    }

    @Test
    fun hevcAlreadyAt41IsLeftAlone() {
        assertNull(CodecLevel.hevc("hvc1.1.6.L120.90"))
        assertNull(CodecLevel.hevc("hvc1.1.6.L123.90"))
    }

    @Test
    fun avcClaimingLevel51At1080pIsAskedAt41() {
        val relaxed = CodecLevel.relaxed(video(MimeTypes.VIDEO_H264, "avc1.640033", 1920, 800))
        assertEquals("avc1.640029", relaxed?.codecs)
    }

    @Test
    fun largerThan1080pKeepsItsLevel() {
        assertNull(CodecLevel.relaxed(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.90", 3840, 1606)))
        assertNull(CodecLevel.relaxed(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.90", 2560, 1440)))
    }

    @Test
    fun otherCodecsAndUnreadableStringsAreLeftAlone() {
        assertNull(CodecLevel.relaxed(video(MimeTypes.VIDEO_DOLBY_VISION, "dvhe.08.06", 1920, 1080)))
        assertNull(CodecLevel.relaxed(video(MimeTypes.VIDEO_H265, "hvc1", 1920, 1080)))
        assertNull(CodecLevel.avc("avc1.4d40"))
    }

    @Test
    fun aPortraitPictureCountsByItsLongSide() {
        assertEquals("hvc1.2.4.L123.90", CodecLevel.relaxed(video(MimeTypes.VIDEO_H265, "hvc1.2.4.L153.90", 1080, 1920))?.codecs)
    }
}
