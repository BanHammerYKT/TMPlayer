package com.tmplayer.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.SubtitleParser
import com.tmplayer.online.SubtitleText
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.util.Locale

/**
 * Text subtitles inside a video, read as UTF-8 whatever they were saved in.
 *
 * Matroska says a SubRip or SubStation track is UTF-8, and Media3 reads it so, but older remuxes
 * carry the Windows code page the .srt was written in, and an Arabic or Russian track came out as
 * a line of accented Latin letters or question marks. A file picked from the phone is already
 * turned into UTF-8 ([SubtitleFiles]); this does the same for a track inside the video: bytes that
 * are not valid UTF-8 are read in the code page of the track's own language, else the viewer's
 * ([SubtitleText.decode]), and handed to Media3's parser as UTF-8. Valid UTF-8, which is nearly
 * every track, goes through as it came, and picture subtitles (PGS, VobSub, DVB) are not text and
 * are never touched.
 */
@UnstableApi
class SubtitleEncoding(private val delegate: SubtitleParser.Factory) : SubtitleParser.Factory {

    override fun supportsFormat(format: Format): Boolean = delegate.supportsFormat(format)

    override fun getCueReplacementBehavior(format: Format): Int = delegate.getCueReplacementBehavior(format)

    override fun create(format: Format): SubtitleParser {
        val parser = delegate.create(format)
        if (format.sampleMimeType !in TEXT_FORMATS) return parser
        val language = format.language ?: Locale.getDefault().language
        return Reencoding(parser, language)
    }

    private class Reencoding(private val inner: SubtitleParser, private val language: String) : SubtitleParser {
        override fun parse(
            data: ByteArray,
            offset: Int,
            length: Int,
            outputOptions: SubtitleParser.OutputOptions,
            output: Consumer<CuesWithTiming>,
        ) {
            val fixed = asUtf8(data, offset, length, language)
            if (fixed == null) {
                inner.parse(data, offset, length, outputOptions, output)
            } else {
                inner.parse(fixed, 0, fixed.size, outputOptions, output)
            }
        }

        override fun reset() = inner.reset()

        override fun getCueReplacementBehavior(): Int = inner.cueReplacementBehavior
    }

    companion object {
        /** The text formats a code page can be wrong in. */
        val TEXT_FORMATS = setOf(MimeTypes.APPLICATION_SUBRIP, MimeTypes.TEXT_SSA, MimeTypes.TEXT_VTT)

        /**
         * The bytes as UTF-8 when they are not already; null when they are (or are empty), so the
         * common case copies nothing.
         */
        fun asUtf8(data: ByteArray, offset: Int, length: Int, language: String?): ByteArray? {
            if (length <= 0) return null
            if (isUtf8(data, offset, length)) return null
            val bytes = data.copyOfRange(offset, offset + length)
            return SubtitleText.decode(bytes, language).toByteArray(Charsets.UTF_8)
        }

        private fun isUtf8(data: ByteArray, offset: Int, length: Int): Boolean = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(data, offset, length))
            true
        } catch (_: CharacterCodingException) {
            false
        }
    }
}
