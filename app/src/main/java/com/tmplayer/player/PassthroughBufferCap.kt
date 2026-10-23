package com.tmplayer.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.DefaultAudioSink

/**
 * Keeps the AudioTrack of a bitstream (passthrough) track under what a Fire TV can allocate.
 *
 * Its AudioFlinger rounds a track's buffer up to the next power of two and refuses anything over
 * about 2 MB with "not enough memory for AudioTrack" (error -12). Media3 multiplies the
 * passthrough allowance for DTS-HD, so a DTS-HD MA track asked for about 4.5 MB, which became 8 MB,
 * and Media3's one retry at half became 4 MB: both refused, and the video failed with "AudioTrack
 * init failed". Kept just under the line instead,
 * which still rounds up to 2 MB, a couple of hundred milliseconds of DTS-HD and more than Media3's
 * stock allowance. PCM and every other output mode, and every size already under the line, are
 * left exactly as [delegate] sized them.
 */
@UnstableApi
internal class PassthroughBufferCap(
    private val delegate: DefaultAudioSink.AudioTrackBufferSizeProvider,
) : DefaultAudioSink.AudioTrackBufferSizeProvider {

    override fun getBufferSizeInBytes(
        minBufferSizeInBytes: Int,
        encoding: Int,
        outputMode: Int,
        pcmFrameSize: Int,
        sampleRate: Int,
        bitrate: Int,
        maxAudioTrackPlaybackSpeed: Double,
    ): Int {
        val size = delegate.getBufferSizeInBytes(
            minBufferSizeInBytes, encoding, outputMode, pcmFrameSize, sampleRate, bitrate, maxAudioTrackPlaybackSpeed,
        )
        if (outputMode != DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH || size <= MAX_PASSTHROUGH_BYTES) return size
        // Never below what the platform says the track needs to be created at all.
        return maxOf(MAX_PASSTHROUGH_BYTES, minBufferSizeInBytes)
    }

    private companion object {
        /** Under 2 MiB (2,097,152), so the power of two it rounds up to is one the allocator takes. */
        const val MAX_PASSTHROUGH_BYTES = 1_900_000
    }
}
