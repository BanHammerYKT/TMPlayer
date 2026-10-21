package com.tmplayer.player

import com.tmplayer.player.OpeningProgress.Signals
import com.tmplayer.player.OpeningProgress.Stage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OpeningProgressTest {

    private val mib = 1024L * 1024

    @Test
    fun `starts connecting at zero and stays below the connecting slice with no bytes`() {
        val p = OpeningProgress(seeks = false)
        assertEquals(0f, p.update(Signals(), 0), 0f)
        var t = 0L
        repeat(600) { t += 100; p.update(Signals(), t) }
        assertEquals(Stage.Connecting, p.stage)
        assertTrue("moved: ${p.value}", p.value > 0.05f)
        assertTrue("capped: ${p.value}", p.value < OpeningProgress.CONNECTING_END)
    }

    @Test
    fun `keeps moving between signals`() {
        val p = OpeningProgress(seeks = false)
        p.update(Signals(readBytes = 1), 0)
        var last = p.value
        for (t in 500L..5_000L step 500L) {
            val now = p.update(Signals(readBytes = 1), t)
            assertTrue("still at $t", now > last)
            last = now
        }
    }

    @Test
    fun `bytes fill the reading slice`() {
        val p = OpeningProgress(seeks = true)
        p.update(Signals(readBytes = 2 * mib), 0)
        assertEquals(Stage.Reading, p.stage)
        // Half the head read: half way through 10 to 40.
        assertEquals(0.25f, p.value, 0.01f)
    }

    @Test
    fun `without a seek reading runs on to the buffer's slice`() {
        val p = OpeningProgress(seeks = false)
        assertEquals(OpeningProgress.FINDING_END, p.range(Stage.Reading).second, 0f)
        p.update(Signals(readBytes = 2 * mib), 0)
        assertEquals(0.35f, p.value, 0.01f)
        p.update(Signals(readBytes = 3 * mib, opened = true), 100)
        assertEquals(Stage.Buffering, p.stage)
    }

    @Test
    fun `a resume finds its place before buffering`() {
        val p = OpeningProgress(seeks = true)
        p.update(Signals(readBytes = 100_000, opened = true, seekDone = false), 0)
        assertEquals(Stage.Finding, p.stage)
        assertTrue(p.value >= OpeningProgress.READING_END)
        p.update(Signals(readBytes = 100_000 + mib, opened = true, seekDone = false), 100)
        assertTrue(p.value < OpeningProgress.FINDING_END)
        p.update(Signals(readBytes = 100_000 + mib, opened = true, seekDone = true, bufferedMs = 500, bufferTargetMs = 1_000), 200)
        assertEquals(Stage.Buffering, p.stage)
        assertEquals(0.8f, p.value, 0.01f)
    }

    @Test
    fun `an engine that cannot say the seek is done counts the bytes since it began`() {
        val p = OpeningProgress(seeks = true)
        p.update(Signals(readBytes = OpeningProgress.READ_BYTES), 0)
        assertEquals(Stage.Finding, p.stage)
        p.update(Signals(readBytes = OpeningProgress.READ_BYTES + mib), 100)
        assertEquals(Stage.Finding, p.stage)
        p.update(Signals(readBytes = OpeningProgress.READ_BYTES + OpeningProgress.FIND_BYTES), 200)
        assertEquals(Stage.Buffering, p.stage)
    }

    @Test
    fun `a buffer in bytes fills from the bitrate`() {
        val p = OpeningProgress(seeks = false)
        p.update(Signals(readBytes = OpeningProgress.READ_BYTES), 0)
        assertEquals(Stage.Buffering, p.stage)
        // 1 MB/s over a 2 s target: 1 MiB more is about half the buffer.
        p.update(Signals(readBytes = OpeningProgress.READ_BYTES + mib, bytesPerSec = mib, bufferTargetMs = 2_000), 0)
        assertEquals(0.8f, p.value, 0.01f)
    }

    @Test
    fun `never moves backwards and never leaves a stage for an earlier one`() {
        val random = Random(7)
        repeat(50) {
            val p = OpeningProgress(seeks = random.nextBoolean())
            var last = 0f
            var lastStage = Stage.Connecting
            var t = 0L
            repeat(200) {
                t += random.nextLong(0, 800)
                val s = Signals(
                    readBytes = random.nextLong(0, 8 * mib),
                    onDisk = random.nextInt(10) == 0,
                    opened = random.nextBoolean(),
                    seekDone = listOf(null, true, false).random(random),
                    bufferedMs = random.nextLong(-1, 3_000),
                    bufferTargetMs = 1_000,
                    bytesPerSec = random.nextLong(0, 2 * mib),
                )
                val v = p.update(s, t)
                assertTrue("backwards $last to $v", v >= last)
                assertTrue("stage back from $lastStage to ${p.stage}", p.stage.ordinal >= lastStage.ordinal)
                assertTrue("full before the frame: $v", v < 1f)
                last = v
                lastStage = p.stage
            }
        }
    }

    @Test
    fun `a full buffer stops short of the end until the first frame`() {
        val p = OpeningProgress(seeks = false)
        p.update(Signals(readBytes = mib, opened = true, bufferedMs = 5_000, bufferTargetMs = 1_000), 0)
        var t = 0L
        repeat(1_000) { t += 100; p.update(Signals(readBytes = mib, opened = true, bufferedMs = 5_000, bufferTargetMs = 1_000), t) }
        assertTrue(p.value < 1f)
        assertEquals(1f, p.finish(), 0f)
        assertEquals(1f, p.update(Signals(), t + 100), 0f)
    }

    @Test
    fun `easing stops short of the stage end`() {
        val p = OpeningProgress(seeks = true)
        p.update(Signals(readBytes = 1), 0)
        var t = 0L
        repeat(10_000) { t += 100; p.update(Signals(readBytes = 1), t) }
        val (start, end) = p.range(Stage.Reading)
        assertTrue(p.value <= start + (end - start) * OpeningProgress.EASE_SHARE + 0.0001f)
        assertTrue(p.value < end)
    }

    @Test
    fun `a file on the disk skips connecting`() {
        val p = OpeningProgress(seeks = false)
        p.update(Signals(onDisk = true), 0)
        assertEquals(Stage.Reading, p.stage)
        assertTrue(p.value >= OpeningProgress.CONNECTING_END)
    }
}
