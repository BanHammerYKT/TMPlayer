package com.tmplayer.player

import java.nio.ByteBuffer

/**
 * Hides the HDR10+ (SMPTE ST 2094-40) SEI messages of an HEVC access unit from the decoder, in
 * place, by turning their payloadType from 4 into 254, a type no decoder knows and every decoder
 * skips by its size.
 *
 * Why this exists: a Dolby Vision profile 8 file that also carries HDR10+ plays as a black screen
 * on the MediaTek MT8696 Fire TV sticks. Their decoder takes the RPU and the HDR10+ message
 * together and applies neither. With the HDR10+ message out of sight the RPU drives the picture
 * alone, which is what the file asks for on a Dolby Vision screen anyway.
 *
 * Why in place rather than cut out: the earlier version copied every sample, rebuilt the SEI and
 * wrote it back, several megabytes a second of garbage and copying on the playback thread of a
 * 1 GB stick. Rewriting one byte keeps the sample's length, so nothing is copied or allocated, and
 * no emulation prevention has to be redone: the byte goes from 0x04 to 0xFE, neither of which is
 * zero or one of the 0x00 to 0x03 bytes an emulation prevention 0x03 guards, so no 00 00 0x
 * sequence can appear or vanish around it. Plex, Wholphin and Nuvio take the same approach.
 *
 * The sample is Annex-B: Media3's MP4 and Matroska extractors replace each NAL length prefix with
 * a four byte start code before the sample reaches the decoder, and MPEG-TS is Annex-B already
 * with three or four byte start codes. Only prefix and suffix SEI NAL units (types 39 and 40) are
 * read; everything else is stepped over. With [mask]'s `stopAtFirstSlice` the walk ends at the
 * first slice, which is where a steady state caller wants it: the prefix NAL units are a few
 * hundred bytes, the slice data behind them is the bulk of a 4K frame.
 *
 * Anything unexpected (a payloadType or payloadSize that runs past the NAL unit, a multi byte
 * payloadType) leaves that NAL unit exactly as it was: a sample this cannot read is better played
 * as it is than half rewritten.
 *
 * Not thread safe and holds per call state in fields, so a rewrite allocates nothing: one instance
 * per codec, used from the playback thread only.
 */
internal class HevcHdr10PlusMasker {

    /** How many of the last [mask] call's messages sat after the first slice, in a suffix SEI. */
    var maskedAfterSlice = 0
        private set

    // The reader's state, fields rather than locals so the helpers below need no captured refs.
    private var buf: ByteBuffer = EMPTY
    private var pos = 0
    private var end = 0
    private var zeros = 0

    /**
     * Masks every HDR10+ message in the access unit held in `buffer` from `from` up to `to`
     * (absolute indices; position and limit are left alone) and returns how many it masked.
     * With [stopAtFirstSlice] the walk ends at the first VCL NAL unit, so suffix SEIs are not
     * seen.
     */
    fun mask(buffer: ByteBuffer, from: Int, to: Int, stopAtFirstSlice: Boolean): Int {
        maskedAfterSlice = 0
        var masked = 0
        var afterSlice = false
        var sc = findStartCode(buffer, from, to)
        while (sc >= 0) {
            val nal = sc + 3
            if (nal + 2 > to) break
            val type = (buffer.get(nal).toInt() ushr 1) and 0x3F
            if (type < FIRST_NON_VCL_TYPE) {
                if (stopAtFirstSlice) break
                afterSlice = true
            }
            val next = findStartCode(buffer, nal + 2, to)
            if (type == PREFIX_SEI || type == SUFFIX_SEI) {
                val nalEnd = if (next < 0) to else next
                // A first pass only reads, so a NAL unit that turns out malformed half way is
                // left untouched rather than masked up to the point where it broke.
                if (walkSei(buffer, nal + 2, nalEnd, rewrite = false) > 0) {
                    val n = walkSei(buffer, nal + 2, nalEnd, rewrite = true)
                    masked += n
                    if (afterSlice) maskedAfterSlice += n
                }
            }
            sc = next
        }
        buf = EMPTY
        return masked
    }

    /**
     * Index of the first byte of the next 00 00 01 at or after [from], or -1. A four byte start
     * code is found by its last three bytes; its leading zero stays with the NAL unit before,
     * where the SEI walk reads it as trailing zero bytes.
     *
     * Steps three bytes at a time while the byte under it is neither 0 nor 1, since no start
     * code can then end at it or at either of the next two bytes.
     */
    private fun findStartCode(b: ByteBuffer, from: Int, to: Int): Int {
        var i = from + 2
        while (i < to) {
            val v = b.get(i).toInt()
            when {
                v == 0 -> i++
                v == 1 -> {
                    if (b.get(i - 1).toInt() == 0 && b.get(i - 2).toInt() == 0) return i - 2
                    i += 3
                }
                else -> i += 3
            }
        }
        return -1
    }

    /**
     * Walks the sei_message()s of one SEI NAL unit's payload, raw bytes `start` up to `stop`,
     * and returns the HDR10+ messages found, or -1 when the payload does not parse. With
     * [rewrite] each one's payloadType byte is changed to [MASKED_PAYLOAD_TYPE].
     */
    private fun walkSei(b: ByteBuffer, start: Int, stop: Int, rewrite: Boolean): Int {
        buf = b
        pos = start
        end = stop
        zeros = 0
        var found = 0
        while (!atTrailingBits()) {
            skipEmulationPrevention()
            val typeAt = pos
            var payloadType = 0
            var typeBytes = 0
            do {
                val v = read()
                if (v < 0) return -1
                payloadType += v
                typeBytes++
            } while (v == 0xFF)
            var payloadSize = 0
            do {
                val v = read()
                if (v < 0) return -1
                payloadSize += v
            } while (v == 0xFF)
            var consumed = 0
            var hdr10Plus = payloadType == USER_DATA_REGISTERED_T35 && typeBytes == 1 &&
                payloadSize >= HDR10_PLUS_PREFIX.size
            if (hdr10Plus) {
                for (expected in HDR10_PLUS_PREFIX) {
                    val v = read()
                    if (v < 0) return -1
                    consumed++
                    if (v != expected) {
                        hdr10Plus = false
                        break
                    }
                }
            }
            while (consumed < payloadSize) {
                if (read() < 0) return -1
                consumed++
            }
            if (hdr10Plus) {
                found++
                if (rewrite) b.put(typeAt, MASKED_PAYLOAD_TYPE)
            }
        }
        return found
    }

    /** One RBSP byte, emulation prevention skipped, or -1 at the end of the NAL unit. */
    private fun read(): Int {
        skipEmulationPrevention()
        if (pos >= end) return -1
        val v = buf.get(pos++).toInt() and 0xFF
        zeros = if (v == 0) zeros + 1 else 0
        return v
    }

    private fun skipEmulationPrevention() {
        if (zeros >= 2 && pos < end && buf.get(pos).toInt() == 3) {
            pos++
            zeros = 0
        }
    }

    /**
     * True at the rbsp_trailing_bits: nothing left, or a lone 0x80 stop bit followed by nothing
     * but zero bytes (cabac_zero_words, the next start code's leading zero) and their 0x03s.
     */
    private fun atTrailingBits(): Boolean {
        skipEmulationPrevention()
        if (pos >= end) return true
        if (buf.get(pos).toInt() and 0xFF != 0x80) return false
        for (i in pos + 1 until end) {
            val v = buf.get(i).toInt()
            if (v != 0 && v != 3) return false
        }
        return true
    }

    companion object {
        private const val PREFIX_SEI = 39
        private const val SUFFIX_SEI = 40

        /** NAL unit types below this are slices (VCL). */
        private const val FIRST_NON_VCL_TYPE = 32

        /** user_data_registered_itu_t_t35, the SEI that carries HDR10+. */
        private const val USER_DATA_REGISTERED_T35 = 4

        /** Reserved, so a decoder reads its size and steps over it. Not 0xFF, which continues. */
        private const val MASKED_PAYLOAD_TYPE = 0xFE.toByte()

        /**
         * country code 0xB5 (United States), terminal provider 0x003C (Samsung), provider
         * oriented code 0x0001 and application identifier 4: the ST 2094-40 signature, the same
         * one Media3 checks before it reads HDR10+.
         */
        private val HDR10_PLUS_PREFIX = intArrayOf(0xB5, 0x00, 0x3C, 0x00, 0x01, 0x04)

        private val EMPTY: ByteBuffer = ByteBuffer.allocate(0)

        /**
         * The Fire TV sticks whose MediaTek MT8696 decoder goes black on Dolby Vision with HDR10+:
         * the Fire TV Stick 4K and 4K Max, both generations (Jellyfin's KnownDefects list).
         */
        private val AFFECTED_MODELS = setOf("AFTMM", "AFTKM", "AFTKA", "AFTKRT")

        fun affectedDevice(manufacturer: String?, model: String?): Boolean =
            manufacturer.equals("Amazon", ignoreCase = true) && model?.uppercase() in AFFECTED_MODELS

        /**
         * Whether a codecs string names Dolby Vision profile 8 (dvhe.08 or dvh1.08), the HDR10
         * compatible profile where an HDR10+ message can ride along. Profiles 5 and 7 have no
         * HDR10+ base to clash with and are left alone. HLS lists several codecs, comma separated.
         */
        fun isDolbyVisionProfile8(codecs: String?): Boolean {
            if (codecs.isNullOrEmpty()) return false
            for (raw in codecs.split(',')) {
                val codec = raw.trim().lowercase()
                if (!codec.startsWith("dvhe.") && !codec.startsWith("dvh1.")) continue
                val profile = codec.substring(5).substringBefore('.').toIntOrNull()
                if (profile == 8) return true
            }
            return false
        }
    }
}
