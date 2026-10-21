package com.tmplayer.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioDownmixTest {

    @Test
    fun `a phone folds 5 point 1 down to two channels, dialogue forward`() {
        assertEquals(
            AudioDownmix.Fold.Wide(6, AudioDownmix.dialogueCoefficients(6)!!),
            AudioDownmix.folds(television = false)[5],
        )
    }

    /** Row major pairs: what input [channel] gives the left and the right output. */
    private fun pair(coefficients: FloatArray, channel: Int) = coefficients[channel * 2] to coefficients[channel * 2 + 1]

    @Test
    fun `the centre, where the dialogue is, reaches both sides at full in every surround layout`() {
        for (channels in listOf(3, 5, 6, 7, 8)) {
            val c = AudioDownmix.dialogueCoefficients(channels)!!
            assertEquals("$channels", 1f to 0f, pair(c, 0))
            assertEquals("$channels", 0f to 1f, pair(c, 1))
            assertEquals("$channels", AudioDownmix.CENTRE to AudioDownmix.CENTRE, pair(c, 2))
        }
    }

    /** The bug behind quiet 7.1 tracks: the alternating fold sent the centre left only, at half. */
    @Test
    fun `seven point one no longer sends its centre to the left alone`() {
        val old = AudioDownmix.wideCoefficients(8)
        assertEquals(0.5f to 0f, pair(old, 2))
        assertEquals(AudioDownmix.Fold.Wide(8, AudioDownmix.dialogueCoefficients(8)!!), AudioDownmix.folds(television = false)[7])
        val c = AudioDownmix.dialogueCoefficients(8)!!
        // Side surrounds to their own side, like the back ones.
        assertEquals(AudioDownmix.SURROUND to 0f, pair(c, 6))
        assertEquals(0f to AudioDownmix.SURROUND, pair(c, 7))
    }

    @Test
    fun `quad has no centre and keeps media3's fold`() {
        assertEquals(null, AudioDownmix.dialogueCoefficients(4))
        assertEquals(AudioDownmix.Fold.ConstantPower(4), AudioDownmix.folds(television = false)[3])
    }

    @Test
    fun `stereo and mono are handed through untouched`() {
        val folds = AudioDownmix.folds(television = false)
        assertEquals(AudioDownmix.Fold.Untouched(1), folds[0])
        assertEquals(AudioDownmix.Fold.Untouched(2), folds[1])
    }

    @Test
    fun `every layout up to the widest has a matrix, so nothing can refuse to configure`() {
        val folds = AudioDownmix.folds(television = false)
        assertEquals(AudioDownmix.MAX_LAYOUT, folds.size)
        val counts = folds.map {
            when (it) {
                is AudioDownmix.Fold.Untouched -> it.channels
                is AudioDownmix.Fold.ConstantPower -> it.channels
                is AudioDownmix.Fold.Wide -> it.channels
            }
        }
        assertEquals((1..AudioDownmix.MAX_LAYOUT).toList(), counts)
    }

    /** The layout that broke an episode: past Media3's helper, so this file builds the fold. */
    @Test
    fun `twelve channels get a fold built here`() {
        val fold = AudioDownmix.folds(television = false)[11]
        assertEquals(AudioDownmix.Fold.Wide(12, AudioDownmix.wideCoefficients(12)), fold)
    }

    @Test
    fun `a wide fold alternates the channels between the two sides`() {
        val coefficients = AudioDownmix.wideCoefficients(4)
        // Row major: channel 0 to left only, channel 1 to right only, and so on.
        assertTrue(coefficients[0] > 0f && coefficients[1] == 0f)
        assertTrue(coefficients[2] == 0f && coefficients[3] > 0f)
        assertTrue(coefficients[4] > 0f && coefficients[5] == 0f)
        assertTrue(coefficients[6] == 0f && coefficients[7] > 0f)
    }

    @Test
    fun `a wide fold keeps the power constant, so it cannot clip`() {
        for (channels in 9..AudioDownmix.MAX_LAYOUT) {
            val coefficients = AudioDownmix.wideCoefficients(channels)
            for (output in 0 until AudioDownmix.STEREO) {
                val power = (0 until channels).sumOf { input ->
                    val value = coefficients[input * AudioDownmix.STEREO + output].toDouble()
                    value * value
                }
                assertEquals("$channels channels, output $output", 1.0, power, 1e-5)
            }
        }
    }

    @Test
    fun `a television keeps the processor out of the chain`() {
        assertEquals(emptyList<AudioDownmix.Fold>(), AudioDownmix.folds(television = true))
    }

    @Test
    fun `with no choice made the device decides, and a choice beats the device`() {
        assertEquals(true, AudioDownmix.wanted(choice = null, television = false))
        assertEquals(false, AudioDownmix.wanted(choice = null, television = true))
        assertEquals(true, AudioDownmix.wanted(choice = true, television = true))
        assertEquals(false, AudioDownmix.wanted(choice = false, television = false))
    }

    @Test
    fun `the stereo fold is the phone's fold`() {
        assertEquals(AudioDownmix.folds(television = false), AudioDownmix.stereoFolds())
    }
}
