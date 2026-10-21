package com.tmplayer.player

/**
 * Where a video file keeps the index its demuxer jumps to before the first frame, found from the
 * file's first bytes, so [StreamWarmup] can have it on the disk before the player asks.
 *
 * - Matroska and WebM: the SeekHead near the start points at the Cues, usually near the end. The
 *   demuxer reads the head, then at the first Cluster jumps to the Cues and back.
 * - MP4 written without "fast start": the media data (mdat) comes first and the moov, which the
 *   demuxer needs before it can play anything, after it, at the end of the file.
 *
 * Pure byte arithmetic on what is passed in; nothing is read from anywhere else.
 */
object StreamIndex {

    /** How much of an MKV's Cues to fetch: a two hour film's are well under this. */
    const val CUES_BYTES = 3L * 1024 * 1024

    /** How much of an MP4's trailing moov to fetch: a long film's is a few megabytes. */
    const val MOOV_BYTES = 8L * 1024 * 1024

    /** A byte range `[start, end)` of the file. */
    data class Range(val start: Long, val end: Long) {
        val length: Long get() = end - start
    }

    /**
     * The index range beyond [head] (the file's first bytes) for a file of [size] bytes, or null
     * when the index is already inside the head, the file is not one of these, or the head does
     * not say.
     */
    fun locate(head: ByteArray, size: Long): Range? {
        if (head.size < 8 || size <= head.size) return null
        val found = when {
            isEbml(head) -> matroskaCues(head)
            isMp4(head) -> mp4TrailingMoov(head, size)
            else -> null
        } ?: return null
        val start = found.start
        if (start < head.size || start >= size) return null
        return Range(start, minOf(size, found.end))
    }

    private fun isEbml(b: ByteArray) =
        b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte()

    private fun isMp4(b: ByteArray) = String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp"

    // ---- Matroska ----------------------------------------------------------------------------

    private const val SEGMENT = 0x18538067L
    private const val SEEK_HEAD = 0x114D9B74L
    private const val SEEK = 0x4DBBL
    private const val SEEK_ID = 0x53ABL
    private const val SEEK_POSITION = 0x53ACL
    private const val CUES = 0x1C53BB6BL
    private const val CLUSTER = 0x1F43B675L

    /** An EBML variable length number at [at]: its value and its length in bytes. */
    private class VInt(val value: Long, val length: Int, val unknown: Boolean)

    private fun vint(b: ByteArray, at: Int, keepMarker: Boolean): VInt? {
        if (at >= b.size) return null
        val first = b[at].toInt() and 0xFF
        if (first == 0) return null
        val length = Integer.numberOfLeadingZeros(first) - 24 + 1
        if (length > 8 || at + length > b.size) return null
        var value = if (keepMarker) first.toLong() else (first and (0xFF shr length)).toLong()
        var allOnes = (first and (0xFF shr length)) == (0xFF shr length)
        for (i in 1 until length) {
            val next = b[at + i].toInt() and 0xFF
            if (next != 0xFF) allOnes = false
            value = (value shl 8) or next.toLong()
        }
        return VInt(value, length, unknown = !keepMarker && allOnes)
    }

    private fun matroskaCues(b: ByteArray): Range? {
        // The EBML header, skipped whole.
        val headerId = vint(b, 0, true) ?: return null
        val headerSize = vint(b, headerId.length, false) ?: return null
        var at = headerId.length + headerSize.length + headerSize.value.toInt()
        val segmentId = vint(b, at, true) ?: return null
        if (segmentId.value != SEGMENT) return null
        val segmentSize = vint(b, at + segmentId.length, false) ?: return null
        val segmentStart = at + segmentId.length + segmentSize.length
        at = segmentStart
        // The Segment's top level children, up to the first Cluster or the end of the head.
        while (at < b.size) {
            val id = vint(b, at, true) ?: return null
            val len = vint(b, at + id.length, false) ?: return null
            val body = at + id.length + len.length
            if (id.value == CLUSTER) return null
            if (id.value == SEEK_HEAD) {
                val end = minOf(b.size.toLong(), body + len.value).toInt()
                return seekHeadCues(b, body, end)?.let { Range(segmentStart + it, segmentStart + it + CUES_BYTES) }
            }
            if (len.unknown) return null
            at = body + len.value.toInt()
        }
        return null
    }

    /** The Cues' position inside the Segment, from a SeekHead's entries. */
    private fun seekHeadCues(b: ByteArray, from: Int, to: Int): Long? {
        var at = from
        while (at < to) {
            val id = vint(b, at, true) ?: return null
            val len = vint(b, at + id.length, false) ?: return null
            val body = at + id.length + len.length
            val end = body + len.value.toInt()
            if (id.value == SEEK && end <= b.size) {
                var seekId = -1L
                var position = -1L
                var inner = body
                while (inner < end) {
                    val childId = vint(b, inner, true) ?: break
                    val childLen = vint(b, inner + childId.length, false) ?: break
                    val childBody = inner + childId.length + childLen.length
                    val n = childLen.value.toInt()
                    if (childBody + n > b.size || n > 8) break
                    var v = 0L
                    for (i in 0 until n) v = (v shl 8) or (b[childBody + i].toLong() and 0xFF)
                    when (childId.value) {
                        SEEK_ID -> seekId = v
                        SEEK_POSITION -> position = v
                    }
                    inner = childBody + n
                }
                if (seekId == CUES && position >= 0) return position
            }
            at = end
        }
        return null
    }

    // ---- MP4 ---------------------------------------------------------------------------------

    /** Where the moov starts when it follows the media data, read off the top level boxes. */
    private fun mp4TrailingMoov(b: ByteArray, size: Long): Range? {
        var at = 0L
        while (at + 8 <= b.size) {
            val i = at.toInt()
            var boxSize = u32(b, i)
            val type = String(b, i + 4, 4, Charsets.ISO_8859_1)
            if (boxSize == 1L) {
                if (i + 16 > b.size) return null
                boxSize = u64(b, i + 8)
            } else if (boxSize == 0L) {
                boxSize = size - at
            }
            if (boxSize < 8) return null
            when (type) {
                // The index comes first: fast start, nothing to fetch ahead.
                "moov" -> return null
                "mdat" -> {
                    val next = at + boxSize
                    return if (next < size) Range(next, next + MOOV_BYTES) else null
                }
            }
            at += boxSize
        }
        return null
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)
}
