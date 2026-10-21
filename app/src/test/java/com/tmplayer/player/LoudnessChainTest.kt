package com.tmplayer.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The phone's audio chain measured end to end on synthetic tracks: the stereo fold, then Even out
 * volume. The levels are RMS in dBFS of what the AudioTrack would be handed, which is what the
 * "the film is quieter than everything else" complaint is about.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [35])
class LoudnessChainTest {

    private val rate = 48_000

    /** [seconds] of a 220 Hz tone at [dbfs] RMS in the given [channels] of a [count] channel track. */
    private fun tone(count: Int, channels: Set<Int>, dbfs: Double, seconds: Double): ByteBuffer {
        val frames = (rate * seconds).toInt()
        val amplitude = Math.pow(10.0, dbfs / 20) * sqrt(2.0)
        val buffer = ByteBuffer.allocateDirect(frames * count * 4).order(ByteOrder.nativeOrder())
        repeat(frames) { i ->
            val v = (amplitude * sin(2 * PI * 220 * i / rate)).toFloat()
            repeat(count) { c -> buffer.putFloat(if (c in channels) v else 0f) }
        }
        buffer.flip()
        return buffer
    }

    private fun pump(processor: AudioProcessor, input: ByteBuffer): ByteBuffer {
        val out = ByteBuffer.allocateDirect(input.remaining() * 2).order(ByteOrder.nativeOrder())
        while (input.hasRemaining()) {
            val slice = input.slice().order(ByteOrder.nativeOrder())
            val take = minOf(slice.remaining(), 4096 * 4)
            slice.limit(take)
            processor.queueInput(slice)
            input.position(input.position() + take - slice.remaining())
            val got = processor.output
            out.put(got)
        }
        out.flip()
        return out
    }

    /** RMS in dBFS of channel [channel] of a [count] channel float buffer, over its last [tail] seconds. */
    private fun rms(buffer: ByteBuffer, count: Int, channel: Int, tail: Double = 1.0): Double {
        val floats = buffer.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer()
        val frames = floats.remaining() / count
        val from = (frames - (tail * rate).toInt()).coerceAtLeast(0)
        var sum = 0.0
        for (f in from until frames) {
            val v = floats.get(f * count + channel).toDouble()
            sum += v * v
        }
        return 10 * log10(sum / (frames - from))
    }

    private fun fold(count: Int, coefficients: FloatArray?): ChannelMixingAudioProcessor = ChannelMixingAudioProcessor().apply {
        putChannelMixingMatrix(
            if (coefficients == null) ChannelMixingMatrix.createForConstantPower(count, 2) else ChannelMixingMatrix(count, 2, coefficients),
        )
        configure(AudioProcessor.AudioFormat(rate, count, C.ENCODING_PCM_FLOAT))
        flush()
    }

    private fun leveller(count: Int, on: Boolean = true) = VolumeBoostProcessor().apply {
        level = on
        configure(AudioProcessor.AudioFormat(rate, count, C.ENCODING_PCM_FLOAT))
        flush()
    }

    /** Dialogue at -27 dBFS in the centre of a 5.1 track, as a film mastered for a cinema carries it. */
    @Test
    fun `5 point 1 dialogue comes out 3 dB louder in the dialogue forward fold`() {
        val before = rms(pump(fold(6, null), tone(6, setOf(2), -27.0, 2.0)), 2, 0)
        val after = rms(pump(fold(6, AudioDownmix.dialogueCoefficients(6)), tone(6, setOf(2), -27.0, 2.0)), 2, 0)
        println("5.1 centre -27 dBFS: Media3 fold $before dBFS, dialogue fold $after dBFS")
        assertEquals(-30.0, before, 0.2)
        assertEquals(-27.0, after, 0.2)
    }

    /** The 7.1 bug: the alternating fold put the centre in the left speaker only, at half. */
    @Test
    fun `7 point 1 dialogue reaches both speakers at full`() {
        val oldFold = pump(fold(8, AudioDownmix.wideCoefficients(8)), tone(8, setOf(2), -27.0, 2.0))
        val newFold = pump(fold(8, AudioDownmix.dialogueCoefficients(8)), tone(8, setOf(2), -27.0, 2.0))
        val oldLeft = rms(oldFold, 2, 0)
        val oldRight = rms(oldFold, 2, 1)
        val newLeft = rms(newFold, 2, 0)
        val newRight = rms(newFold, 2, 1)
        println("7.1 centre -27 dBFS: old fold L $oldLeft R $oldRight, dialogue fold L $newLeft R $newRight")
        assertEquals(-33.0, oldLeft, 0.2)
        assertTrue(oldRight < -90)
        assertEquals(-27.0, newLeft, 0.2)
        assertEquals(-27.0, newRight, 0.2)
    }

    @Test
    fun `even out volume lifts a quiet film towards the level of a web video`() {
        val out = pump(leveller(2), tone(2, setOf(0, 1), -28.0, 12.0))
        val level = rms(out, 2, 0)
        println("stereo -28 dBFS through Even out volume: $level dBFS")
        // About -18 dBFS, +10 dB, and never past the cap.
        assertEquals(-18.0, level, 1.5)
    }

    @Test
    fun `even out volume never lifts past its cap`() {
        val out = pump(leveller(2), tone(2, setOf(0, 1), -45.0, 12.0))
        val level = rms(out, 2, 0)
        println("stereo -45 dBFS through Even out volume: $level dBFS")
        assertEquals(-45.0 + 20 * log10(VolumeBoostProcessor.LEVEL_MAX.toDouble()), level, 1.0)
    }

    @Test
    fun `even out volume leaves a track already at web loudness alone`() {
        val out = pump(leveller(2), tone(2, setOf(0, 1), -14.0, 6.0))
        assertEquals(-14.0, rms(out, 2, 0), 0.3)
    }

    @Test
    fun `off, a quiet film stays quiet`() {
        val out = pump(leveller(2, on = false), tone(2, setOf(0, 1), -28.0, 6.0))
        assertEquals(-28.0, rms(out, 2, 0), 0.2)
    }

    @Test
    fun `a whole quiet 5 point 1 film, folded and levelled, lands near a web video`() {
        // Dialogue in the centre and a bed in the fronts, both at cinema level.
        val track = tone(6, setOf(0, 1, 2), -28.0, 12.0)
        val folded = pump(fold(6, AudioDownmix.dialogueCoefficients(6)), track)
        val out = pump(leveller(2), folded)
        val level = rms(out, 2, 0)
        println("5.1 film at -28 dBFS, folded and levelled: $level dBFS")
        assertTrue("landed at $level", level in -19.5..-16.0)
        val peak = out.duplicate().order(ByteOrder.nativeOrder()).asFloatBuffer().let { f -> (0 until f.remaining()).maxOf { kotlin.math.abs(f.get(it)) } }
        assertTrue("peak $peak", peak <= 1f)
    }
}
