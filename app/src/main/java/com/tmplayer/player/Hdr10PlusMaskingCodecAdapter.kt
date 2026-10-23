package com.tmplayer.player

import android.media.MediaCodec
import android.util.Log
import androidx.media3.common.util.ThrowingRunnable
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import java.nio.ByteBuffer

/**
 * Puts [HevcHdr10PlusMasker] between the video renderer and a Dolby Vision profile 8 decoder.
 *
 * It wraps the codec adapter factory the renderers factory already uses, rather than building a
 * renderer of its own, so every option the factory sets on its renderers (asynchronous queueing,
 * the joining time, the dropped frame reports, the FFmpeg renderer's place when software video is
 * preferred) stays as it is, today and after the next Media3 upgrade. Every other codec, audio
 * included, gets the stock adapter untouched.
 */
@UnstableApi
internal class Hdr10PlusMaskingCodecAdapterFactory(
    private val delegate: MediaCodecAdapter.Factory,
) : MediaCodecAdapter.Factory {

    override fun createAdapter(configuration: MediaCodecAdapter.Configuration): MediaCodecAdapter {
        val adapter = delegate.createAdapter(configuration)
        val codecs = configuration.format.codecs
        if (!HevcHdr10PlusMasker.isDolbyVisionProfile8(codecs)) return adapter
        return Hdr10PlusMaskingCodecAdapter(adapter, codecs, configuration.codecInfo.name)
    }
}

/**
 * Masks the HDR10+ messages of each sample as it is queued, in the decoder's own input buffer.
 *
 * The buffer is the one [getInputBuffer] handed the renderer for that index, kept here rather
 * than asked for again: asking MediaCodec a second time would invalidate the renderer's copy and
 * is not promised to keep the bytes.
 *
 * Only the prefix SEIs are read once the video is under way. The first [PROBE_SAMPLES] samples
 * are read whole, suffix SEIs included, and only if one of those carried HDR10+ does every later
 * sample get the whole walk too. A video that never puts it in a suffix SEI (every HDR10+ encoder
 * seen so far uses the prefix) then costs the parameter sets and SEIs at the front of a frame,
 * not a scan of its megabyte of slice data.
 */
@UnstableApi
private class Hdr10PlusMaskingCodecAdapter(
    private val delegate: MediaCodecAdapter,
    private val codecs: String?,
    private val codecName: String,
) : MediaCodecAdapter by delegate {

    private val masker = HevcHdr10PlusMasker()
    private var lastIndex = -1
    private var lastBuffer: ByteBuffer? = null
    private var samples = 0L
    private var maskedSamples = 0L
    private var wholeSamples = false
    private var failed = false

    override fun getInputBuffer(index: Int): ByteBuffer? {
        val buffer = delegate.getInputBuffer(index)
        lastIndex = index
        lastBuffer = buffer
        return buffer
    }

    override fun queueInputBuffer(index: Int, offset: Int, size: Int, presentationTimeUs: Long, flags: Int) {
        val buffer = lastBuffer
        lastBuffer = null
        if (buffer != null && index == lastIndex && size > 0 && !failed &&
            flags and (MediaCodec.BUFFER_FLAG_END_OF_STREAM or MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 &&
            offset >= 0 && offset + size <= buffer.limit()
        ) {
            maskSample(buffer, offset, size)
        }
        delegate.queueInputBuffer(index, offset, size, presentationTimeUs, flags)
    }

    private fun maskSample(buffer: ByteBuffer, offset: Int, size: Int) {
        samples++
        val whole = wholeSamples || samples <= PROBE_SAMPLES
        try {
            if (masker.mask(buffer, offset, offset + size, stopAtFirstSlice = !whole) > 0) maskedSamples++
            if (masker.maskedAfterSlice > 0) wholeSamples = true
        } catch (e: RuntimeException) {
            // A bug here must not cost the video: the sample goes on as it is, and so do the rest.
            failed = true
            Log.w(TAG, "HDR10+ masking stopped", e)
        }
    }

    // Java default methods, passed on explicitly so the wrapped adapter's own versions are used.
    override fun <E : Exception> useBuffer(runnable: ThrowingRunnable<E>) = delegate.useBuffer(runnable)

    override fun registerOnBufferAvailableListener(listener: MediaCodecAdapter.OnBufferAvailableListener): Boolean =
        delegate.registerOnBufferAvailableListener(listener)

    override fun flush() {
        lastBuffer = null
        delegate.flush()
    }

    override fun release() {
        // One line per decoder, which is one per video: never a line per sample.
        Log.i(
            TAG,
            "Dolby Vision 8 on $codecName ($codecs): HDR10+ masked in $maskedSamples of $samples samples" +
                if (wholeSamples) ", suffix SEIs read" else "",
        )
        lastBuffer = null
        delegate.release()
    }

    private companion object {
        const val TAG = "Hdr10PlusMask"

        /** About two seconds of video read whole before the walk settles on the prefix SEIs. */
        const val PROBE_SAMPLES = 48
    }
}
