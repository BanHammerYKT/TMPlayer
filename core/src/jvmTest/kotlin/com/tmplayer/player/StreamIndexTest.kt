package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

class StreamIndexTest {

    private fun ByteArrayOutputStream.bytes(vararg values: Int) = values.forEach { write(it) }

    /** An EBML element: [id] as written, a one byte size, then [body]. */
    private fun element(id: IntArray, body: ByteArray): ByteArray = ByteArrayOutputStream().apply {
        id.forEach { write(it) }
        write(0x80 or body.size)
        write(body)
    }.toByteArray()

    private fun uint(value: Long, bytes: Int) = ByteArray(bytes) { i -> (value shr (8 * (bytes - 1 - i))).toByte() }

    /** A Matroska head whose SeekHead says the Cues are at [cuesInSegment] inside the Segment. */
    private fun matroska(cuesInSegment: Long, padTo: Int = 4096): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(element(intArrayOf(0x1A, 0x45, 0xDF, 0xA3), byteArrayOf(0x42, 0x82.toByte(), 0x84.toByte(), 0x77, 0x65, 0x62, 0x6D)))
        // Segment, of unknown size (all ones), as a live mux writes it.
        out.bytes(0x18, 0x53, 0x80, 0x67, 0x01, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF, 0xFF)
        val seekInfo = element(intArrayOf(0x15, 0x49, 0xA9, 0x66), ByteArray(4))
        val seekCues = element(
            intArrayOf(0x4D, 0xBB),
            element(intArrayOf(0x53, 0xAB), byteArrayOf(0x1C, 0x53, 0xBB.toByte(), 0x6B)) +
                element(intArrayOf(0x53, 0xAC), uint(cuesInSegment, 4)),
        )
        val seekInfoEntry = element(
            intArrayOf(0x4D, 0xBB),
            element(intArrayOf(0x53, 0xAB), byteArrayOf(0x15, 0x49, 0xA9.toByte(), 0x66)) +
                element(intArrayOf(0x53, 0xAC), uint(200, 2)),
        )
        out.write(element(intArrayOf(0x11, 0x4D, 0x9B, 0x74), seekInfoEntry + seekCues))
        out.write(seekInfo)
        while (out.size() < padTo) out.write(0)
        return out.toByteArray()
    }

    @Test
    fun `an mkv's cues are found through its seek head`() {
        val head = matroska(cuesInSegment = 900_000_000)
        // The Segment's data begins right after its 12 byte header, past the 12 byte EBML header.
        val segmentStart = 12 + 12
        val found = StreamIndex.locate(head, size = 1_000_000_000)
        assertEquals(segmentStart + 900_000_000L, found?.start)
        assertEquals(StreamIndex.CUES_BYTES, found?.length)
    }

    @Test
    fun `cues near the end are cut at the end of the file`() {
        val size = 900_100_000L
        val found = StreamIndex.locate(matroska(cuesInSegment = 900_000_000), size)
        assertEquals(size, found?.end)
    }

    @Test
    fun `cues inside the head need nothing more`() {
        assertNull(StreamIndex.locate(matroska(cuesInSegment = 100), size = 1_000_000_000))
    }

    private fun box(type: String, size: Long, body: Int = 0): ByteArray = ByteArrayOutputStream().apply {
        write(uint(size, 4))
        write(type.toByteArray(Charsets.ISO_8859_1))
        write(ByteArray(body))
    }.toByteArray()

    @Test
    fun `an mp4 with its moov after the media data has the moov fetched`() {
        val ftyp = box("ftyp", 24, 16)
        val mdatSize = 700_000_000L
        val head = ftyp + box("mdat", mdatSize) + ByteArray(4000)
        val found = StreamIndex.locate(head, size = 24 + mdatSize + 3_000_000)
        assertEquals(24 + mdatSize, found?.start)
        assertEquals(24 + mdatSize + 3_000_000, found?.end)
    }

    @Test
    fun `a fast start mp4 needs nothing more`() {
        val head = box("ftyp", 24, 16) + box("moov", 2000, 1992) + box("mdat", 700_000_000) + ByteArray(1000)
        assertNull(StreamIndex.locate(head, size = 700_100_000))
    }

    @Test
    fun `a 64 bit mdat size is read`() {
        val ftyp = box("ftyp", 24, 16)
        val large = 5_000_000_000L
        val mdat = ByteArrayOutputStream().apply {
            write(uint(1, 4))
            write("mdat".toByteArray(Charsets.ISO_8859_1))
            write(uint(large, 8))
        }.toByteArray()
        val found = StreamIndex.locate(ftyp + mdat + ByteArray(4000), size = 24 + large + 5_000_000)
        assertEquals(24 + large, found?.start)
        assertEquals(5_000_000L, found?.length)
    }

    @Test
    fun `anything else is left alone`() {
        assertNull(StreamIndex.locate(ByteArray(4096) { 0x47 }, size = 10_000_000))
        assertNull(StreamIndex.locate(ByteArray(4), size = 10_000_000))
    }
}
