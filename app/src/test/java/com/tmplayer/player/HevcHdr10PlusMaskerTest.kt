package com.tmplayer.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer

/** Synthetic Annex-B access units, built the way Media3's extractors hand them to the decoder. */
class HevcHdr10PlusMaskerTest {

    private val masker = HevcHdr10PlusMasker()

    @Test
    fun hdr10PlusAloneInAPrefixSei() {
        val au = accessUnit(4, aud(), sei(PREFIX, message(4, hdr10Plus())), slice())
        val (out, masked) = run(au)
        assertEquals(1, masked)
        assertOnlyChanged(au, out, typeByteOf(au, 1))
    }

    @Test
    fun hdr10PlusNextToAnotherMessageKeepsTheOther() {
        val mastering = message(137, ByteArray(24) { (it + 7).toByte() })
        val au = accessUnit(4, sei(PREFIX, mastering, message(4, hdr10Plus())), slice())
        val (out, masked) = run(au)
        assertEquals(1, masked)
        // The first message's type byte is untouched; only the second one moves.
        val seiStart = 4 + 2
        assertEquals(137, out[seiStart].toInt() and 0xFF)
        assertOnlyChanged(au, out, seiStart + 2 + 24)
    }

    @Test
    fun otherT35MessagesAreLeftAlone() {
        // Same payload type, another provider (a closed caption style ATSC registration).
        val a53 = byteArrayOf(0xB5.toByte(), 0x00, 0x31, 0x47, 0x41, 0x39, 0x34)
        val au = accessUnit(4, sei(PREFIX, message(4, a53)), slice())
        val (out, masked) = run(au)
        assertEquals(0, masked)
        assertArrayEquals(au, out)
    }

    @Test
    fun suffixSeiIsMaskedOnlyWhenTheWholeSampleIsRead() {
        val au = accessUnit(4, slice(), sei(SUFFIX, message(4, hdr10Plus())))
        val (prefixOnly, none) = run(au, stopAtFirstSlice = true)
        assertEquals(0, none)
        assertArrayEquals(au, prefixOnly)

        val (out, masked) = run(au, stopAtFirstSlice = false)
        assertEquals(1, masked)
        assertEquals(1, masker.maskedAfterSlice)
        // From the end: the stop bit, 14 payload bytes, the size, then the type.
        assertEquals(0xFE, out[out.size - 17].toInt() and 0xFF)
    }

    @Test
    fun emulationPreventionInsideThePayloads() {
        // Zeros that need escaping, in a message before the HDR10+ one and inside it, so the walk
        // only stays aligned if it skips the 0x03s.
        val zeros = message(5, byteArrayOf(0, 0, 0, 0, 1, 0, 0, 2, 9, 0, 0))
        val hdr = message(4, hdr10Plus(extra = byteArrayOf(0, 0, 0, 0, 3, 0, 0, 1)))
        val au = accessUnit(4, sei(PREFIX, zeros, hdr), slice())
        assertTrue("the fixture must carry emulation prevention", countEpb(au) >= 3)
        val (out, masked) = run(au)
        assertEquals(1, masked)
        assertEquals(au.size, out.size)
        assertEquals(1, au.indices.count { au[it] != out[it] })
        assertEquals(0xFE, out[au.indices.first { au[it] != out[it] }].toInt() and 0xFF)
    }

    @Test
    fun payloadSizeRunningPastTheNalLeavesItUnchanged() {
        val broken = byteArrayOf(4, 120) + hdr10Plus()
        val au = accessUnit(4, nal(PREFIX, broken), slice())
        val (out, masked) = run(au)
        assertEquals(0, masked)
        assertArrayEquals(au, out)
    }

    @Test
    fun truncatedSignatureLeavesItUnchanged() {
        val au = accessUnit(4, nal(PREFIX, byteArrayOf(4, 10, 0xB5.toByte(), 0x00, 0x3C)))
        val (out, masked) = run(au)
        assertEquals(0, masked)
        assertArrayEquals(au, out)
    }

    @Test
    fun malformedSeiDoesNotStopAGoodOneLater() {
        val bad = nal(PREFIX, byteArrayOf(4, 120, 1, 2, 3))
        val au = accessUnit(4, bad, sei(PREFIX, message(4, hdr10Plus())), slice())
        val (out, masked) = run(au)
        assertEquals(1, masked)
        assertEquals(1, au.indices.count { au[it] != out[it] })
    }

    @Test
    fun noSeiNoChangeNoAllocation() {
        val au = accessUnit(4, aud(), nal(33, ByteArray(40) { 1 }), slice(size = 4096))
        val buffer = ByteBuffer.allocateDirect(au.size).put(au).flip() as ByteBuffer
        repeat(2_000) { masker.mask(buffer, 0, au.size, stopAtFirstSlice = false) }
        val before = allocatedBytes()
        var masked = 0
        repeat(2_000) { masked += masker.mask(buffer, 0, au.size, stopAtFirstSlice = false) }
        val allocated = allocatedBytes() - before
        assertEquals(0, masked)
        val copy = ByteArray(au.size).also { buffer.duplicate().get(it) }
        assertArrayEquals(au, copy)
        assertTrue("allocated $allocated bytes over 2,000 samples", allocated < 2_000)
    }

    @Test
    fun maskingAllocatesNothing() {
        val au = accessUnit(4, sei(PREFIX, message(137, ByteArray(24) { 5 }), message(4, hdr10Plus())), slice(size = 4096))
        val at = 4 + 2 + 2 + 24
        val buffer = ByteBuffer.allocateDirect(au.size).put(au).flip() as ByteBuffer
        repeat(2_000) {
            buffer.put(at, 4.toByte())
            masker.mask(buffer, 0, au.size, stopAtFirstSlice = false)
        }
        val before = allocatedBytes()
        var masked = 0
        repeat(2_000) {
            buffer.put(at, 4.toByte())
            masked += masker.mask(buffer, 0, au.size, stopAtFirstSlice = false)
        }
        val allocated = allocatedBytes() - before
        assertEquals(2_000, masked)
        assertTrue("allocated $allocated bytes over 2,000 samples", allocated < 2_000)
    }

    @Test
    fun threeByteStartCodes() {
        val au = accessUnit(3, aud(), sei(PREFIX, message(4, hdr10Plus())), slice())
        val (out, masked) = run(au)
        assertEquals(1, masked)
        assertOnlyChanged(au, out, typeByteOf(au, 1, startCode = 3))
    }

    @Test
    fun mixedStartCodesAndASampleAtAnOffset() {
        val au = accessUnit(4, aud()) + accessUnit(3, sei(PREFIX, message(4, hdr10Plus())), slice())
        val padded = byteArrayOf(9, 9, 9) + au + byteArrayOf(7, 7)
        val buffer = ByteBuffer.wrap(padded.copyOf())
        val masked = masker.mask(buffer, 3, 3 + au.size, stopAtFirstSlice = true)
        assertEquals(1, masked)
        val out = buffer.array()
        assertEquals(1, padded.indices.count { padded[it] != out[it] })
        assertArrayEquals(padded.copyOfRange(0, 3), out.copyOfRange(0, 3))
    }

    @Test
    fun maskingTwiceChangesNothingMore() {
        val au = accessUnit(4, sei(PREFIX, message(4, hdr10Plus())), slice())
        val (once, _) = run(au)
        val (twice, masked) = run(once)
        assertEquals(0, masked)
        assertArrayEquals(once, twice)
    }

    @Test
    fun dolbyVisionProfileFromCodecs() {
        assertTrue(HevcHdr10PlusMasker.isDolbyVisionProfile8("dvhe.08.06"))
        assertTrue(HevcHdr10PlusMasker.isDolbyVisionProfile8("dvh1.08.07"))
        assertTrue(HevcHdr10PlusMasker.isDolbyVisionProfile8("hvc1.2.4.L153.B0, DVH1.08.06"))
        assertFalse(HevcHdr10PlusMasker.isDolbyVisionProfile8("dvhe.05.06"))
        assertFalse(HevcHdr10PlusMasker.isDolbyVisionProfile8("dvhe.07.06"))
        assertFalse(HevcHdr10PlusMasker.isDolbyVisionProfile8("dvav.08.06"))
        assertFalse(HevcHdr10PlusMasker.isDolbyVisionProfile8("hvc1.2.4.L153.B0"))
        assertFalse(HevcHdr10PlusMasker.isDolbyVisionProfile8(null))
    }

    @Test
    fun affectedDevices() {
        assertTrue(HevcHdr10PlusMasker.affectedDevice("Amazon", "AFTKA"))
        assertTrue(HevcHdr10PlusMasker.affectedDevice("amazon", "AFTMM"))
        assertTrue(HevcHdr10PlusMasker.affectedDevice("Amazon", "AFTKM"))
        assertTrue(HevcHdr10PlusMasker.affectedDevice("Amazon", "AFTKRT"))
        assertFalse(HevcHdr10PlusMasker.affectedDevice("Amazon", "AFTSSS"))
        assertFalse(HevcHdr10PlusMasker.affectedDevice("Google", "AFTKA"))
        assertFalse(HevcHdr10PlusMasker.affectedDevice(null, null))
    }

    // Fixture builders.

    private fun run(au: ByteArray, stopAtFirstSlice: Boolean = false): Pair<ByteArray, Int> {
        val copy = au.copyOf()
        val masked = masker.mask(ByteBuffer.wrap(copy), 0, copy.size, stopAtFirstSlice)
        return copy to masked
    }

    private fun assertOnlyChanged(before: ByteArray, after: ByteArray, at: Int) {
        assertEquals(before.size, after.size)
        assertEquals(4, before[at].toInt())
        assertEquals(0xFE, after[at].toInt() and 0xFF)
        for (i in before.indices) if (i != at) assertEquals("byte $i", before[i], after[i])
    }

    /** Where the payloadType of the SEI that is NAL number [nalIndex] sits, for single message SEIs. */
    private fun typeByteOf(au: ByteArray, nalIndex: Int, startCode: Int = 4): Int {
        var found = -1
        var i = 0
        while (i + 2 < au.size) {
            if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) {
                found++
                if (found == nalIndex) return i + 3 + 2
                i += 3
            } else {
                i++
            }
        }
        error("no NAL $nalIndex (start codes of $startCode)")
    }

    private fun countEpb(au: ByteArray): Int =
        (2 until au.size - 1).count { au[it - 2].toInt() == 0 && au[it - 1].toInt() == 0 && au[it].toInt() == 3 }

    private fun accessUnit(startCode: Int, vararg nals: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        for (n in nals) {
            if (startCode == 4) out.write(0)
            out.write(byteArrayOf(0, 0, 1))
            out.write(n)
        }
        return out.toByteArray()
    }

    /** A NAL unit: header, then [rbsp] with emulation prevention applied. */
    private fun nal(type: Int, rbsp: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(type shl 1)
        out.write(1)
        var zeros = 0
        for (b in rbsp) {
            val v = b.toInt() and 0xFF
            if (zeros >= 2 && v <= 3) {
                out.write(3)
                zeros = 0
            }
            out.write(v)
            zeros = if (v == 0) zeros + 1 else 0
        }
        return out.toByteArray()
    }

    private fun sei(type: Int, vararg messages: ByteArray): ByteArray {
        val rbsp = ByteArrayOutputStream()
        messages.forEach { rbsp.write(it) }
        rbsp.write(0x80)
        return nal(type, rbsp.toByteArray())
    }

    private fun message(type: Int, payload: ByteArray): ByteArray {
        require(type < 255 && payload.size < 255)
        return byteArrayOf(type.toByte(), payload.size.toByte()) + payload
    }

    private fun hdr10Plus(extra: ByteArray = ByteArray(8) { 0x11 }): ByteArray =
        byteArrayOf(0xB5.toByte(), 0x00, 0x3C, 0x00, 0x01, 0x04) + extra

    private fun aud() = nal(35, byteArrayOf(0x50))

    /** An IDR slice whose body is not zero, as compressed data mostly is. */
    private fun slice(size: Int = 64) = nal(19, ByteArray(size) { (0x40 + it % 100).toByte() })

    private fun allocatedBytes(): Long =
        (ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean).currentThreadAllocatedBytes

    private companion object {
        const val PREFIX = 39
        const val SUFFIX = 40
    }
}
