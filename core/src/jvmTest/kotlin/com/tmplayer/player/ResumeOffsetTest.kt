package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** The byte a resumed video's warm-up starts from: [StreamIndex.offsetAt] and [ResumeWindow]. */
class ResumeOffsetTest {

    private fun uint(value: Long, bytes: Int) = ByteArray(bytes) { i -> (value shr (8 * (bytes - 1 - i))).toByte() }

    // ---- Matroska --------------------------------------------------------------------------

    /** An EBML element with its size written in eight bytes, so any body fits. */
    private fun ebml(id: IntArray, body: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        id.forEach { write(it) }
        write(0x01)
        write(uint(body.size.toLong(), 7))
        write(body)
    }.toByteArray()

    private fun ebmlUint(id: IntArray, value: Long) = ebml(id, uint(value, 8))

    private val segmentStart = 12 + 12

    /** A Matroska head: SeekHead pointing at the Cues at [cuesInSegment], Info with [scaleNs]. */
    private fun matroskaHead(cuesInSegment: Long, scaleNs: Long = 1_000_000, padTo: Int = 4096): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 0x87.toByte(), 0x42, 0x82.toByte(), 0x84.toByte(), 0x77, 0x65, 0x62, 0x6D))
        out.write(byteArrayOf(0x18, 0x53, 0x80.toByte(), 0x67, 0x01, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
        val seek = ebml(
            intArrayOf(0x4D, 0xBB),
            ebml(intArrayOf(0x53, 0xAB), byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)) + ebmlUint(intArrayOf(0x53, 0xAC), cuesInSegment),
        )
        out.write(ebml(intArrayOf(0x11, 0x4D, 0x9B, 0x74), seek))
        out.write(ebml(intArrayOf(0x15, 0x49, 0xA9, 0x66), ebmlUint(intArrayOf(0x2A, 0xD7, 0xB1), scaleNs)))
        while (out.size() < padTo) out.write(0)
        return out.toByteArray()
    }

    /** Cues with one CuePoint per (time in the file's unit, cluster position in the Segment). */
    private fun cues(vararg points: Pair<Long, Long>): ByteArray {
        val body = ByteArrayOutputStream()
        for ((time, position) in points) {
            body.write(
                ebml(
                    intArrayOf(0xBB),
                    ebmlUint(intArrayOf(0xB3), time) +
                        ebml(intArrayOf(0xB7), ebmlUint(intArrayOf(0xF7), 1) + ebmlUint(intArrayOf(0xF1), position)),
                ),
            )
        }
        return ebml(intArrayOf(0x1C, 0x53, 0xBB, 0x6B), body.toByteArray())
    }

    private val cuesAt = 900_000_000L
    private val cuePoints = arrayOf(0L to 5_000L, 10_000L to 40_000_000L, 20_000L to 80_000_000L, 30_000L to 120_000_000L)

    @Test
    fun `an mkv resumes at the cluster of the last cue at or before the place`() {
        val head = matroskaHead(cuesAt)
        val at = StreamIndex.offsetAt(head, cues(*cuePoints), segmentStart + cuesAt, startMs = 25_000)
        assertEquals(segmentStart + 80_000_000L, at)
    }

    @Test
    fun `an mkv's own time unit is used`() {
        // Ten microsecond units: the cue at 2_000_000 units is 20 s in.
        val head = matroskaHead(cuesAt, scaleNs = 10_000)
        val points = arrayOf(0L to 5_000L, 1_000_000L to 40_000_000L, 2_000_000L to 80_000_000L, 3_000_000L to 120_000_000L)
        val at = StreamIndex.offsetAt(head, cues(*points), segmentStart + cuesAt, startMs = 25_000)
        assertEquals(segmentStart + 80_000_000L, at)
    }

    @Test
    fun `whole cues answer a place past the last cue`() {
        val at = StreamIndex.offsetAt(matroskaHead(cuesAt), cues(*cuePoints), segmentStart + cuesAt, startMs = 3_600_000)
        assertEquals(segmentStart + 120_000_000L, at)
    }

    @Test
    fun `cues cut short of the place say nothing`() {
        val whole = cues(*cuePoints)
        // The last CuePoint lost: the cue at 30 s could have been nearer to 35 s than the one at 20 s.
        val cut = whole.copyOf(whole.size - 10)
        assertNull(StreamIndex.offsetAt(matroskaHead(cuesAt), cut, segmentStart + cuesAt, startMs = 35_000))
        // A place before the cut is still answered: a later cue shows nothing nearer was lost.
        assertEquals(segmentStart + 40_000_000L, StreamIndex.offsetAt(matroskaHead(cuesAt), cut, segmentStart + cuesAt, startMs = 15_000))
    }

    @Test
    fun `cues from somewhere else are not read`() {
        assertNull(StreamIndex.offsetAt(matroskaHead(cuesAt), cues(*cuePoints), indexStart = 123, startMs = 25_000))
        assertNull(StreamIndex.offsetAt(matroskaHead(cuesAt), null, -1, startMs = 25_000))
    }

    // ---- MP4 -------------------------------------------------------------------------------

    private fun box(type: String, body: ByteArray): ByteArray =
        uint(8L + body.size, 4) + type.toByteArray(Charsets.ISO_8859_1) + body

    private fun fullBox(type: String, body: ByteArray) = box(type, ByteArray(4) + body)

    private fun table(vararg entries: Long): ByteArray =
        entries.fold(ByteArrayOutputStream()) { out, v -> out.apply { write(uint(v, 4)) } }.toByteArray()

    /** A track of [samples] one second samples, one per chunk at [offsets], key frames every [keyEvery]. */
    private fun trak(handler: String, timescale: Long, samples: Int, offsets: (Int) -> Long, keyEvery: Int?): ByteArray {
        val mdhd = fullBox("mdhd", uint(0, 4) + uint(0, 4) + uint(timescale, 4) + uint(samples * timescale, 4) + ByteArray(4))
        val hdlr = fullBox("hdlr", ByteArray(4) + handler.toByteArray(Charsets.ISO_8859_1) + ByteArray(12))
        val stts = fullBox("stts", table(1, samples.toLong(), timescale))
        val stsc = fullBox("stsc", table(1, 1, 1, 1))
        val stco = fullBox("stco", table(samples.toLong(), *LongArray(samples) { offsets(it) }))
        val stss = keyEvery?.let { every ->
            val keys = (0 until samples step every).map { it + 1L }
            fullBox("stss", table(keys.size.toLong(), *keys.toLongArray()))
        } ?: ByteArray(0)
        val stbl = box("stbl", stts + stss + stsc + stco)
        return box("trak", box("mdia", mdhd + hdlr + box("minf", stbl)))
    }

    private val moov = box(
        "moov",
        trak("vide", 1000, 100, { 1_000L + it * 100_000L }, keyEvery = 10) +
            trak("soun", 48_000, 100, { 1_000L + it * 100_000L - 50_000L }, keyEvery = null),
    )

    @Test
    fun `an mp4 resumes at the earliest chunk any track needs from the key frame`() {
        val head = box("ftyp", ByteArray(16)) + box("mdat", ByteArray(0)).copyOf(8) + ByteArray(4000)
        // 25.5 s: the key frame before it is sample 21 (20 s); the sound for 20 s sits 50 KB ahead.
        val at = StreamIndex.offsetAt(head, moov, indexStart = 700_000_000, startMs = 25_500)
        assertEquals(1_000L + 20 * 100_000L - 50_000L, at)
    }

    @Test
    fun `a fast start mp4 is read from the head`() {
        val head = box("ftyp", ByteArray(16)) + moov + ByteArray(2000)
        assertEquals(1_000L + 10 * 100_000L - 50_000L, StreamIndex.offsetAt(head, null, -1, startMs = 12_000))
        assertNull(StreamIndex.mp4Moov(head))
    }

    @Test
    fun `a fast start moov running past the head is found`() {
        val ftyp = box("ftyp", ByteArray(16))
        val head = (ftyp + moov).copyOf(ftyp.size + 64)
        assertEquals(StreamIndex.Range(ftyp.size.toLong(), (ftyp.size + moov.size).toLong()), StreamIndex.mp4Moov(head))
        // Cut short in the head, it says nothing; whole from the fetch, it does.
        assertNull(StreamIndex.offsetAt(head, null, -1, startMs = 12_000))
        assertEquals(1_000L + 10 * 100_000L - 50_000L, StreamIndex.offsetAt(head, moov, ftyp.size.toLong(), startMs = 12_000))
    }

    @Test
    fun `an mp4 without a picture track or a whole moov says nothing`() {
        val head = box("ftyp", ByteArray(16)) + ByteArray(4000)
        val soundOnly = box("moov", trak("soun", 48_000, 100, { it * 1000L }, keyEvery = null))
        assertNull(StreamIndex.offsetAt(head, soundOnly, 0, startMs = 12_000))
        assertNull(StreamIndex.offsetAt(head, moov.copyOf(moov.size - 20), 0, startMs = 12_000))
    }

    @Test
    fun `the start of the file needs no offset`() {
        assertNull(StreamIndex.offsetAt(matroskaHead(cuesAt), cues(*cuePoints), segmentStart + cuesAt, startMs = 0))
    }

    // ---- ResumeWindow ----------------------------------------------------------------------

    @Test
    fun `an indexed offset is put back a little and down to a megabyte`() {
        val mib = 1024L * 1024
        assertEquals(40 * mib, ResumeWindow.indexed(40 * mib + 300_000, size = 1000 * mib))
        // Within the back margin of a megabyte boundary: the megabyte before.
        assertEquals(39 * mib, ResumeWindow.indexed(40 * mib + 1000, size = 1000 * mib))
        assertEquals(0L, ResumeWindow.indexed(1000, size = 1000 * mib))
        assertNull(ResumeWindow.indexed(1000 * mib, size = 1000 * mib))
    }

    @Test
    fun `without an index the place is guessed from the running time`() {
        val mib = 1024L * 1024
        // Half way through a 1 GiB file: 512 MiB, two back, on a megabyte.
        assertEquals(510 * mib, ResumeWindow.estimate(startMs = 1_800_000, durationMs = 3_600_000, size = 1024 * mib))
        assertEquals(0L, ResumeWindow.estimate(startMs = 1_000, durationMs = 3_600_000, size = 1024 * mib))
        assertNull(ResumeWindow.estimate(startMs = 0, durationMs = 3_600_000, size = 1024 * mib))
        assertNull(ResumeWindow.estimate(startMs = 1_800_000, durationMs = 0, size = 1024 * mib))
        assertNull(ResumeWindow.estimate(startMs = 3_600_000, durationMs = 3_600_000, size = 1024 * mib))
    }
}
