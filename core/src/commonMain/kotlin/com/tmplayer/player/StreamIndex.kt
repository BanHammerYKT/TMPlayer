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

    // ---- Resume: the byte a seek to a time reads first ------------------------------------------

    /**
     * The byte of the file a demuxer reads first after a seek to [startMs], from the file's own
     * index: an MKV's Cues (the Cluster with the key frame at or before the time), an MP4's sample
     * tables (the earliest chunk any track needs from the picture's key frame at or before the
     * time). [head] is the file's first bytes; [index] the bytes of the file from [indexStart],
     * the Cues or the moov [locate] or [mp4Moov] found, when they were fetched. Null when the
     * index is not to hand, is cut short of the time, or does not say; the caller then guesses
     * from the running time ([ResumeWindow.estimate]).
     */
    fun offsetAt(head: ByteArray, index: ByteArray?, indexStart: Long, startMs: Long): Long? {
        if (head.size < 8 || startMs <= 0) return null
        return runCatching {
            when {
                isEbml(head) -> matroskaOffsetAt(head, index, indexStart, startMs)
                isMp4(head) -> mp4OffsetAt(head, index, startMs)
                else -> null
            }
        }.getOrNull()
    }

    /**
     * An MP4's moov when it comes first (fast start) but runs on past [head]: where it is, so it
     * can be fetched whole for [offsetAt]. Null when the head holds all of it, it comes after the
     * media data ([locate] finds that one), or the file is not an MP4.
     */
    fun mp4Moov(head: ByteArray): Range? {
        if (head.size < 8 || !isMp4(head)) return null
        var at = 0L
        while (at + 8 <= head.size) {
            val i = at.toInt()
            var boxSize = u32(head, i)
            val type = String(head, i + 4, 4, Charsets.ISO_8859_1)
            if (boxSize == 1L) {
                if (i + 16 > head.size) return null
                boxSize = u64(head, i + 8)
            } else if (boxSize == 0L) {
                return null
            }
            if (boxSize < 8) return null
            when (type) {
                "moov" -> return if (at + boxSize > head.size) Range(at, at + boxSize) else null
                "mdat" -> return null
            }
            at += boxSize
        }
        return null
    }

    private const val INFO = 0x1549A966L
    private const val TIMESTAMP_SCALE = 0x2AD7B1L
    private const val CUE_POINT = 0xBBL
    private const val CUE_TIME = 0xB3L
    private const val CUE_TRACK_POSITIONS = 0xB7L
    private const val CUE_CLUSTER_POSITION = 0xF1L

    /** Where an MKV's Segment data starts, where its Cues are (absolute), and its time unit in ns. */
    private class MkvLayout(val segmentStart: Long, val cuesAt: Long?, val timestampScale: Long)

    private fun matroskaLayout(b: ByteArray): MkvLayout? {
        val headerId = vint(b, 0, true) ?: return null
        val headerSize = vint(b, headerId.length, false) ?: return null
        var at = headerId.length + headerSize.length + headerSize.value.toInt()
        val segmentId = vint(b, at, true) ?: return null
        if (segmentId.value != SEGMENT) return null
        val segmentSize = vint(b, at + segmentId.length, false) ?: return null
        val segmentStart = at + segmentId.length + segmentSize.length
        at = segmentStart
        var cues: Long? = null
        var scale = 1_000_000L
        while (at < b.size) {
            val id = vint(b, at, true) ?: break
            val len = vint(b, at + id.length, false) ?: break
            val body = at + id.length + len.length
            if (id.value == CLUSTER || len.unknown) break
            val end = body + len.value
            if (id.value == SEEK_HEAD && cues == null) {
                cues = seekHeadCues(b, body, minOf(b.size.toLong(), end).toInt())
            }
            if (id.value == INFO && end <= b.size) {
                var inner = body
                while (inner < end) {
                    val childId = vint(b, inner, true) ?: break
                    val childLen = vint(b, inner + childId.length, false) ?: break
                    val childBody = inner + childId.length + childLen.length
                    if (childId.value == TIMESTAMP_SCALE) {
                        uint(b, childBody, childLen.value)?.takeIf { it > 0 }?.let { scale = it }
                    }
                    inner = childBody + childLen.value.toInt()
                }
            }
            if (end > b.size) break
            at = end.toInt()
        }
        return MkvLayout(segmentStart.toLong(), cues?.let { segmentStart + it }, scale)
    }

    private fun matroskaOffsetAt(head: ByteArray, index: ByteArray?, indexStart: Long, startMs: Long): Long? {
        val layout = matroskaLayout(head) ?: return null
        val cuesAt = layout.cuesAt ?: return null
        val (bytes, from) = when {
            index != null && indexStart == cuesAt -> index to 0
            cuesAt < head.size -> head to cuesAt.toInt()
            else -> return null
        }
        val target = startMs.toDouble() * 1_000_000.0 / layout.timestampScale
        val position = cueBefore(bytes, from, target) ?: return null
        return layout.segmentStart + position
    }

    /**
     * The CueClusterPosition of the last CuePoint at or before [target] (in the file's time
     * unit) in the Cues at [from]; null when there is none, or the bytes end before a CuePoint
     * past the target shows none later could be nearer.
     */
    private fun cueBefore(b: ByteArray, from: Int, target: Double): Long? {
        val id = vint(b, from, true) ?: return null
        if (id.value != CUES) return null
        val len = vint(b, from + id.length, false) ?: return null
        val body = from + id.length + len.length
        val whole = !len.unknown && body + len.value <= b.size
        val end = if (whole) body + len.value.toInt() else b.size
        var bestTime = -1L
        var best: Long? = null
        var passed = false
        var at = body
        while (at < end) {
            val pointId = vint(b, at, true) ?: break
            val pointLen = vint(b, at + pointId.length, false) ?: break
            val pointBody = at + pointId.length + pointLen.length
            val pointEnd = pointBody + pointLen.value
            if (pointLen.unknown || pointEnd > end) break
            if (pointId.value == CUE_POINT) {
                var time: Long? = null
                var position: Long? = null
                var inner = pointBody
                while (inner < pointEnd) {
                    val childId = vint(b, inner, true) ?: break
                    val childLen = vint(b, inner + childId.length, false) ?: break
                    val childBody = inner + childId.length + childLen.length
                    val childEnd = childBody + childLen.value
                    if (childEnd > pointEnd) break
                    when (childId.value) {
                        CUE_TIME -> time = uint(b, childBody, childLen.value)
                        CUE_TRACK_POSITIONS -> if (position == null) {
                            var p = childBody
                            while (p < childEnd) {
                                val posId = vint(b, p, true) ?: break
                                val posLen = vint(b, p + posId.length, false) ?: break
                                val posBody = p + posId.length + posLen.length
                                if (posId.value == CUE_CLUSTER_POSITION) {
                                    position = uint(b, posBody, posLen.value)
                                    break
                                }
                                p = posBody + posLen.value.toInt()
                            }
                        }
                    }
                    inner = childEnd.toInt()
                }
                if (time != null && position != null) {
                    if (time <= target) {
                        if (time >= bestTime) {
                            bestTime = time
                            best = position
                        }
                    } else {
                        passed = true
                    }
                }
            }
            at = pointEnd.toInt()
        }
        return best?.takeIf { passed || whole }
    }

    /** An unsigned big endian number of [length] bytes at [at], or null when it does not fit. */
    private fun uint(b: ByteArray, at: Int, length: Long): Long? {
        if (length !in 1..8 || at + length > b.size) return null
        var v = 0L
        for (i in 0 until length.toInt()) v = (v shl 8) or (b[at + i].toLong() and 0xFF)
        return v
    }

    /** A box: its four letter type and its body `[body, end)`. */
    private class Box(val type: String, val body: Int, val end: Int)

    /** The boxes laid end to end in `[from, to)` of [b]; stops at the first that does not fit. */
    private fun boxes(b: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var at = from.toLong()
        while (at + 8 <= to) {
            val i = at.toInt()
            var size = u32(b, i)
            var header = 8
            if (size == 1L) {
                if (i + 16 > to) break
                size = u64(b, i + 8)
                header = 16
            } else if (size == 0L) {
                size = to - at
            }
            if (size < header || at + size > to) break
            out += Box(String(b, i + 4, 4, Charsets.ISO_8859_1), i + header, (at + size).toInt())
            at += size
        }
        return out
    }

    private fun Box.child(b: ByteArray, type: String): Box? = boxes(b, body, end).firstOrNull { it.type == type }

    /** One track's tables, as far as a seek needs them. */
    private class Mp4Track(
        val timescale: Long,
        val video: Boolean,
        /** stts: pairs of sample count and sample duration. */
        val times: LongArray,
        /** stss: the key frames, numbered from 1; null when every sample is one. */
        val sync: LongArray?,
        /** stsc: triples of first chunk (from 1), samples per chunk, description. */
        val chunks: LongArray,
        /** stco or co64. */
        val offsets: LongArray,
    )

    private fun mp4OffsetAt(head: ByteArray, index: ByteArray?, startMs: Long): Long? {
        val moov = index?.let { boxes(it, 0, it.size).firstOrNull { box -> box.type == "moov" }?.let { box -> it to box } }
            ?: boxes(head, 0, head.size).firstOrNull { it.type == "moov" }?.let { head to it }
            ?: return null
        val (b, box) = moov
        val tracks = boxes(b, box.body, box.end).filter { it.type == "trak" }.mapNotNull { mp4Track(b, it) }
        val video = tracks.firstOrNull { it.video } ?: return null
        val key = syncAtOrBefore(video, sampleAt(video, startMs * video.timescale / 1000) ?: return null)
        val keyMs = timeOf(video, key) * 1000 / video.timescale
        return tracks.mapNotNull { t ->
            val sample = if (t === video) key else sampleAt(t, keyMs * t.timescale / 1000) ?: return@mapNotNull null
            chunkOffset(t, sample)
        }.minOrNull()
    }

    private fun mp4Track(b: ByteArray, trak: Box): Mp4Track? {
        val mdia = trak.child(b, "mdia") ?: return null
        val mdhd = mdia.child(b, "mdhd") ?: return null
        val timescale = if (b[mdhd.body].toInt() == 1) u32(b, mdhd.body + 20) else u32(b, mdhd.body + 12)
        if (timescale <= 0) return null
        val handler = mdia.child(b, "hdlr")?.let { String(b, it.body + 8, 4, Charsets.ISO_8859_1) }
        val stbl = mdia.child(b, "minf")?.child(b, "stbl") ?: return null
        val stts = stbl.child(b, "stts")?.let { table(b, it, 2) } ?: return null
        val stsc = stbl.child(b, "stsc")?.let { table(b, it, 3) } ?: return null
        val sync = stbl.child(b, "stss")?.let { table(b, it, 1) }
        val offsets = stbl.child(b, "co64")?.let { table(b, it, 1, wide = true) }
            ?: stbl.child(b, "stco")?.let { table(b, it, 1) }
            ?: return null
        if (stts.isEmpty() || stsc.isEmpty() || offsets.isEmpty()) return null
        return Mp4Track(timescale, handler == "vide", stts, sync, stsc, offsets)
    }

    /** A full box's entry table: the count, then [fields] numbers per entry (64 bit when [wide]). */
    private fun table(b: ByteArray, box: Box, fields: Int, wide: Boolean = false): LongArray? {
        if (box.body + 8 > box.end) return null
        val count = u32(b, box.body + 4)
        val width = if (wide) 8 else 4
        if (count * fields * width > box.end - box.body - 8) return null
        val out = LongArray((count * fields).toInt())
        var at = box.body + 8
        for (i in out.indices) {
            out[i] = if (wide) u64(b, at) else u32(b, at)
            at += width
        }
        return out
    }

    /** The sample (from 0) playing at [time] in the track's timescale; the last one past the end. */
    private fun sampleAt(t: Mp4Track, time: Long): Long? {
        var start = 0L
        var sample = 0L
        for (i in 0 until t.times.size / 2) {
            val count = t.times[2 * i]
            val delta = t.times[2 * i + 1]
            if (delta > 0 && time < start + count * delta) return sample + (time - start) / delta
            start += count * delta
            sample += count
        }
        return (sample - 1).takeIf { it >= 0 }
    }

    private fun timeOf(t: Mp4Track, sample: Long): Long {
        var start = 0L
        var left = sample
        for (i in 0 until t.times.size / 2) {
            val count = t.times[2 * i]
            val delta = t.times[2 * i + 1]
            if (left < count) return start + left * delta
            start += count * delta
            left -= count
        }
        return start
    }

    private fun syncAtOrBefore(t: Mp4Track, sample: Long): Long {
        val sync = t.sync ?: return sample
        var best = sync.firstOrNull()?.minus(1) ?: return sample
        for (s in sync) if (s - 1 <= sample) best = s - 1 else break
        return best.coerceAtLeast(0)
    }

    /** Where the chunk holding [sample] (from 0) starts. */
    private fun chunkOffset(t: Mp4Track, sample: Long): Long? {
        var left = sample
        val entries = t.chunks.size / 3
        for (i in 0 until entries) {
            val first = t.chunks[3 * i]
            val perChunk = t.chunks[3 * i + 1]
            val next = if (i + 1 < entries) t.chunks[3 * (i + 1)] else t.offsets.size + 1L
            if (perChunk <= 0 || next <= first) continue
            val inEntry = (next - first) * perChunk
            if (left < inEntry) {
                val chunk = first - 1 + left / perChunk
                return t.offsets.getOrNull(chunk.toInt())
            }
            left -= inEntry
        }
        return null
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)
}
