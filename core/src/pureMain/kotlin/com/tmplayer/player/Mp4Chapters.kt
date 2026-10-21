package com.tmplayer.player

import com.tmplayer.platform.utf16

/**
 * The chapter marks of an MP4 or QuickTime file (.mp4, .m4v, .mov), read from its bytes, the way
 * [MkvChapters] reads a Matroska file's: from the parts already on the device, through a reader
 * that answers null for a part that is not there yet.
 *
 * Two kinds are written. QuickTime chapters, which Apple's tools, HandBrake and ffmpeg write: a
 * text track that some other track points at with a `chap` reference in its `tref`, one sample per
 * chapter, each sample its title and its time the chapter's start. And Nero's `chpl` box in the
 * movie's `udta`, a plain list of starts and titles, which older muxers and mp4v2 write. The text
 * track wins where a file has both, as it does in ffmpeg.
 *
 * Only the `moov` box is read, plus the chapter track's few samples. A file made for streaming
 * ("faststart") has `moov` at the start, which the player has read first anyway; one with `moov`
 * after the media has it at the very end, which a stream has not reached, so nothing is found
 * until the download gets there (and the player asks again a few times, then gives up quietly).
 */
object Mp4Chapters {

    /** More chapters than anyone writes; a bigger box than this is not read. */
    const val MAX_BOX_BYTES = 1024 * 1024

    /** How many boxes at the top of a file are looked through for `moov`. */
    private const val MAX_TOP_BOXES = 32

    /** How many chapter samples are read at most. */
    private const val MAX_SAMPLES = 1000

    /**
     * The chapters of a file, given [read], which returns the [length] bytes at an offset (fewer
     * at the end of the file) or null when that part is not on the disk. Empty for a file with
     * none, one that is not MP4, or one whose `moov` is not downloaded yet.
     */
    suspend fun read(read: suspend (offset: Long, length: Int) -> ByteArray?): List<Chapter> {
        val moov = findMoov(read) ?: return emptyList()
        val tracks = mutableListOf<Track>()
        var nero: List<Chapter> = emptyList()
        for (box in children(read, moov.dataStart, moov.end) ?: return emptyList()) {
            when (box.type) {
                "trak" -> track(read, box)?.let(tracks::add)
                "udta" -> {
                    val chpl = children(read, box.dataStart, box.end)?.firstOrNull { it.type == "chpl" } ?: continue
                    val bytes = payload(read, chpl) ?: continue
                    nero = parseChpl(bytes)
                }
            }
        }
        val pointed = tracks.flatMap { it.chapterRefs }.toSet()
        val chapterTrack = tracks.firstOrNull { it.id in pointed && it.isText }
        val fromTrack = chapterTrack?.let { quickTimeChapters(read, it) }.orEmpty()
        return fromTrack.ifEmpty { nero }
    }

    /**
     * The chapters in a Nero `chpl` box's payload (after its size and type): a version and flags,
     * four reserved bytes from version 1, a count, then each chapter's start in hundreds of
     * nanoseconds and its title, a length byte and UTF-8.
     */
    fun parseChpl(payload: ByteArray): List<Chapter> {
        val r = Bytes(payload)
        val version = r.u8() ?: return emptyList()
        r.skip(3)
        if (version >= 1) r.skip(4)
        val count = r.u8() ?: return emptyList()
        val out = mutableListOf<Chapter>()
        repeat(count) {
            val start = r.u64() ?: return finish(out)
            val length = r.u8() ?: return finish(out)
            val title = r.bytes(length)?.decodeToString()?.trim() ?: return finish(out)
            out += Chapter(startMs = start / 10_000, title = title.ifEmpty { null })
        }
        return finish(out)
    }

    /**
     * One QuickTime text sample: a 16 bit length and the text, UTF-8, or UTF-16 when it opens
     * with a byte order mark. Anything after the text (style boxes) is ignored.
     */
    fun sampleText(sample: ByteArray): String? {
        if (sample.size < 2) return null
        val length = ((sample[0].toInt() and 0xFF) shl 8) or (sample[1].toInt() and 0xFF)
        val end = minOf(sample.size, 2 + length)
        if (end <= 2) return null
        val b0 = sample[2].toInt() and 0xFF
        val b1 = if (end > 3) sample[3].toInt() and 0xFF else -1
        val text = when {
            b0 == 0xFE && b1 == 0xFF -> utf16(sample, 4, end - 4, bigEndian = true)
            b0 == 0xFF && b1 == 0xFE -> utf16(sample, 4, end - 4, bigEndian = false)
            else -> sample.decodeToString(2, end)
        }
        return text.trim().trimEnd('\u0000').takeIf { it.isNotEmpty() }
    }

    private fun finish(chapters: List<Chapter>): List<Chapter> =
        chapters.sortedBy { it.startMs }.distinctBy { it.startMs }

    // ---- finding moov ------------------------------------------------------------------------

    private suspend fun findMoov(read: suspend (Long, Int) -> ByteArray?): Box? {
        var offset = 0L
        repeat(MAX_TOP_BOXES) { index ->
            val box = header(read, offset, limit = Long.MAX_VALUE) ?: return null
            // An MP4 opens with ftyp (or, in an old QuickTime file, with one of these); anything
            // else is not one, and is not walked through box by box.
            if (index == 0 && box.type !in FIRST_BOXES) return null
            if (box.type == "moov") return box
            if (box.size <= 0) return null
            offset = box.end
        }
        return null
    }

    // ---- tracks ------------------------------------------------------------------------------

    private class Track(
        val id: Long,
        val chapterRefs: List<Long>,
        val isText: Boolean,
        val timescale: Long,
        val stbl: Map<String, Box>,
    )

    private suspend fun track(read: suspend (Long, Int) -> ByteArray?, trak: Box): Track? {
        val boxes = children(read, trak.dataStart, trak.end) ?: return null
        val tkhd = boxes.firstOrNull { it.type == "tkhd" }?.let { payload(read, it) } ?: return null
        val id = trackId(tkhd) ?: return null
        val refs = mutableListOf<Long>()
        boxes.firstOrNull { it.type == "tref" }?.let { tref ->
            for (ref in children(read, tref.dataStart, tref.end).orEmpty()) {
                if (ref.type != "chap") continue
                val ids = payload(read, ref) ?: continue
                val r = Bytes(ids)
                while (true) refs += r.u32() ?: break
            }
        }
        var isText = false
        var timescale = 0L
        var stbl: Map<String, Box> = emptyMap()
        // The media's own details are only worth reading for a track something might point at:
        // the handler says whether it is text, and the sample tables where its samples are.
        boxes.firstOrNull { it.type == "mdia" }?.let { mdia ->
            val inside = children(read, mdia.dataStart, mdia.end).orEmpty()
            inside.firstOrNull { it.type == "hdlr" }?.let { payload(read, it) }?.let { hdlr ->
                val handler = hdlr.copyOfRangeOrNull(8, 12)?.decodeToString()
                isText = handler == "text" || handler == "sbtl"
            }
            if (!isText) return@let
            inside.firstOrNull { it.type == "mdhd" }?.let { payload(read, it) }?.let { timescale = mdhdTimescale(it) ?: 0L }
            val minf = inside.firstOrNull { it.type == "minf" } ?: return@let
            val table = children(read, minf.dataStart, minf.end)?.firstOrNull { it.type == "stbl" } ?: return@let
            stbl = children(read, table.dataStart, table.end).orEmpty().associateBy { it.type }
        }
        return Track(id, refs, isText, timescale, stbl)
    }

    private fun trackId(tkhd: ByteArray): Long? {
        val r = Bytes(tkhd)
        val version = r.u8() ?: return null
        r.skip(3)
        r.skip(if (version == 1) 16 else 8)
        return r.u32()
    }

    private fun mdhdTimescale(mdhd: ByteArray): Long? {
        val r = Bytes(mdhd)
        val version = r.u8() ?: return null
        r.skip(3)
        r.skip(if (version == 1) 16 else 8)
        return r.u32()?.takeIf { it > 0 }
    }

    /** The chapter track's samples: each one's start from `stts`, its bytes from `stsz`, `stsc` and `stco`. */
    private suspend fun quickTimeChapters(read: suspend (Long, Int) -> ByteArray?, track: Track): List<Chapter>? {
        if (track.timescale <= 0) return null
        val stts = track.stbl["stts"]?.let { payload(read, it) } ?: return null
        val stsz = track.stbl["stsz"]?.let { payload(read, it) } ?: return null
        val stsc = track.stbl["stsc"]?.let { payload(read, it) } ?: return null
        val chunks = track.stbl["stco"]?.let { box -> payload(read, box)?.let { chunkOffsets(it, wide = false) } }
            ?: track.stbl["co64"]?.let { box -> payload(read, box)?.let { chunkOffsets(it, wide = true) } }
            ?: return null
        val starts = sampleStarts(stts)
        val sizes = sampleSizes(stsz)
        val offsets = sampleOffsets(stsc, chunks, sizes)
        val count = minOf(starts.size, sizes.size, offsets.size, MAX_SAMPLES)
        val out = mutableListOf<Chapter>()
        for (i in 0 until count) {
            val size = sizes[i].toInt().coerceAtMost(1024)
            val sample = if (size > 0) read(offsets[i], size) else null
            out += Chapter(startMs = starts[i] * 1000 / track.timescale, title = sample?.let(::sampleText))
        }
        return finish(out)
    }

    /** Each sample's start, in the track's timescale, from its run-length list of durations. */
    fun sampleStarts(stts: ByteArray): List<Long> {
        val r = Bytes(stts)
        r.skip(4)
        val entries = r.u32() ?: return emptyList()
        val out = mutableListOf<Long>()
        var at = 0L
        for (e in 0 until entries) {
            val count = r.u32() ?: break
            val delta = r.u32() ?: break
            for (i in 0 until count) {
                if (out.size >= MAX_SAMPLES) return out
                out += at
                at += delta
            }
        }
        return out
    }

    fun sampleSizes(stsz: ByteArray): List<Long> {
        val r = Bytes(stsz)
        r.skip(4)
        val uniform = r.u32() ?: return emptyList()
        val count = (r.u32() ?: return emptyList()).coerceAtMost(MAX_SAMPLES.toLong())
        if (uniform != 0L) return List(count.toInt()) { uniform }
        val out = mutableListOf<Long>()
        repeat(count.toInt()) { out += r.u32() ?: return out }
        return out
    }

    fun chunkOffsets(box: ByteArray, wide: Boolean): List<Long> {
        val r = Bytes(box)
        r.skip(4)
        val count = (r.u32() ?: return emptyList()).coerceAtMost(MAX_SAMPLES.toLong())
        val out = mutableListOf<Long>()
        repeat(count.toInt()) { out += (if (wide) r.u64() else r.u32()) ?: return out }
        return out
    }

    /** Where each sample's bytes sit: its chunk's offset, plus the sizes of the samples before it in that chunk. */
    fun sampleOffsets(stsc: ByteArray, chunks: List<Long>, sizes: List<Long>): List<Long> {
        val r = Bytes(stsc)
        r.skip(4)
        val entries = r.u32() ?: return emptyList()
        val runs = mutableListOf<Pair<Long, Long>>() // first chunk (1-based), samples per chunk
        for (e in 0 until entries) {
            val first = r.u32() ?: break
            val perChunk = r.u32() ?: break
            r.skip(4)
            runs += first to perChunk
        }
        if (runs.isEmpty()) return emptyList()
        val out = mutableListOf<Long>()
        var sample = 0
        for (chunk in chunks.indices) {
            val number = chunk + 1L
            val perChunk = runs.lastOrNull { it.first <= number }?.second ?: continue
            var at = chunks[chunk]
            for (i in 0 until perChunk) {
                if (sample >= sizes.size) return out
                out += at
                at += sizes[sample]
                sample++
            }
        }
        return out
    }

    // ---- boxes -------------------------------------------------------------------------------

    private class Box(val type: String, val start: Long, val dataStart: Long, val size: Long) {
        val end: Long get() = start + size
    }

    /** The box header at [offset]; null when it is not on the disk, is cut short, or runs past [limit]. */
    private suspend fun header(read: suspend (Long, Int) -> ByteArray?, offset: Long, limit: Long): Box? {
        val bytes = read(offset, 16) ?: return null
        if (bytes.size < 8) return null
        val r = Bytes(bytes)
        val size32 = r.u32() ?: return null
        val type = r.bytes(4)?.decodeToString() ?: return null
        val (size, headerLength) = when (size32) {
            1L -> (r.u64() ?: return null) to 16
            // Runs to the end of the file: only ever the last box, the media.
            0L -> 0L to 8
            else -> size32 to 8
        }
        if (size != 0L && size < headerLength) return null
        if (size != 0L && offset + size > limit) return null
        return Box(type, offset, offset + headerLength, size)
    }

    /** The boxes inside a parent's payload from [from] to [to]; null when a header is not on the disk. */
    private suspend fun children(read: suspend (Long, Int) -> ByteArray?, from: Long, to: Long): List<Box>? {
        val out = mutableListOf<Box>()
        var at = from
        while (at + 8 <= to) {
            val box = header(read, at, limit = to) ?: return if (out.isEmpty()) null else out
            if (box.size <= 0) break
            out += box
            at = box.end
            if (out.size > 512) break
        }
        return out
    }

    /** A small box's payload, after its header; null for one too big to be a table this needs. */
    private suspend fun payload(read: suspend (Long, Int) -> ByteArray?, box: Box): ByteArray? {
        val length = box.end - box.dataStart
        if (length < 0 || length > MAX_BOX_BYTES) return null
        if (length == 0L) return ByteArray(0)
        val bytes = read(box.dataStart, length.toInt()) ?: return null
        return bytes.takeIf { it.size.toLong() == length }
    }

    private fun ByteArray.copyOfRangeOrNull(from: Int, to: Int): ByteArray? =
        if (to <= size) copyOfRange(from, to) else null

    /** A big-endian reader over a byte array, answering null past its end. */
    private class Bytes(val data: ByteArray) {
        var pos = 0

        fun skip(n: Int) {
            pos += n
        }

        fun u8(): Int? = if (pos + 1 > data.size) null else (data[pos++].toInt() and 0xFF)

        fun u32(): Long? {
            if (pos + 4 > data.size) return null
            var v = 0L
            repeat(4) { v = (v shl 8) or (data[pos++].toLong() and 0xFF) }
            return v
        }

        fun u64(): Long? {
            if (pos + 8 > data.size) return null
            var v = 0L
            repeat(8) { v = (v shl 8) or (data[pos++].toLong() and 0xFF) }
            return v
        }

        fun bytes(n: Int): ByteArray? {
            if (n < 0 || pos + n > data.size) return null
            return data.copyOfRange(pos, pos + n).also { pos += n }
        }
    }

    private val FIRST_BOXES = setOf("ftyp", "moov", "free", "skip", "wide", "mdat", "pnot")
}

/**
 * The chapter marks of whatever file is playing, read by the reader its first bytes call for:
 * Matroska ([MkvChapters]) or MP4 and QuickTime ([Mp4Chapters]). Media3 reads neither kind.
 * Anything else, or a file whose head is not on the disk yet, has none.
 */
object FileChapters {
    suspend fun read(language: String? = null, read: suspend (offset: Long, length: Int) -> ByteArray?): List<Chapter> {
        val magic = read(0, 12) ?: return emptyList()
        if (magic.size < 8) return emptyList()
        val ebml = (magic[0].toInt() and 0xFF) == 0x1A && (magic[1].toInt() and 0xFF) == 0x45 &&
            (magic[2].toInt() and 0xFF) == 0xDF && (magic[3].toInt() and 0xFF) == 0xA3
        if (ebml) return MkvChapters.read(language, read)
        val type = magic.decodeToString(4, 8)
        if (type in setOf("ftyp", "moov", "free", "skip", "wide", "mdat", "pnot")) return Mp4Chapters.read(read)
        return emptyList()
    }
}
