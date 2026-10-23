package com.tmplayer.player

import android.content.Context
import android.hardware.display.DisplayManager
import android.net.Uri
import android.os.Build
import android.view.Display
import androidx.media3.common.C
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import java.util.concurrent.ConcurrentHashMap

/**
 * Hands a Dolby Vision track to the player as its base layer where this device would not show it
 * as Dolby Vision ([FormatRoute.forDevice]). Done where the extractor names the track's format, so
 * the track selector, both decoders and the error sheet all see the same thing, and every other
 * track, sample and seek passes through untouched.
 *
 * The device is asked once per profile while the app runs: does the screen take Dolby Vision, and
 * does a Dolby Vision decoder take this profile at this size. Both yes keeps Dolby Vision.
 */
@UnstableApi
class DolbyVisionBaseLayer(
    context: Context,
    private val delegate: ExtractorsFactory,
) : ExtractorsFactory {

    private val app = context.applicationContext

    override fun createExtractors(): Array<Extractor> = delegate.createExtractors().map(::wrap).toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::wrap).toTypedArray()

    // What DefaultMediaSourceFactory sets on its extractors (subtitles parsed while extracting,
    // the GOP options), passed on, or the wrapped factory would lose them.
    @Deprecated("Media3's own deprecation, passed through")
    @Suppress("DEPRECATION")
    override fun experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled: Boolean): ExtractorsFactory {
        delegate.experimentalSetTextTrackTranscodingEnabled(textTrackTranscodingEnabled)
        return this
    }

    override fun setSubtitleParserFactory(subtitleParserFactory: SubtitleParser.Factory): ExtractorsFactory {
        delegate.setSubtitleParserFactory(subtitleParserFactory)
        return this
    }

    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies: Int): ExtractorsFactory {
        delegate.experimentalSetCodecsToParseWithinGopSampleDependencies(codecsToParseWithinGopSampleDependencies)
        return this
    }

    override fun setParseHagcMetadata(parseHagcMetadata: Boolean): ExtractorsFactory {
        delegate.setParseHagcMetadata(parseHagcMetadata)
        return this
    }

    private fun wrap(extractor: Extractor): Extractor = Wrapped(extractor) { format ->
        runCatching { FormatRoute.forDevice(format, ::playsAsDolbyVision, ::baseCodecs) }.getOrDefault(format)
    }

    private fun playsAsDolbyVision(format: Format): Boolean {
        // The screen is read each time (a stick can be moved to another television); the
        // decoder's answer is the chip's and is kept.
        val screen = screenTakesDolbyVision(app)
        val key = "${format.codecs}/${format.width}x${format.height}"
        val decoder = decoderAnswers.getOrPut(key) {
            runCatching {
                MediaCodecUtil.getDecoderInfos(MimeTypes.VIDEO_DOLBY_VISION, false, false)
                    .any { it.isFormatSupported(app, format) }
            }.getOrDefault(false)
        }
        android.util.Log.i(
            "TMPlayer",
            "Dolby Vision ${format.codecs} ${format.width}x${format.height}: screen $screen, decoder $decoder" +
                if (screen && decoder) "" else ", playing its base layer",
        )
        return screen && decoder
    }

    private fun baseCodecs(format: Format): String? = when (FormatRoute.dolbyVisionBaseMime(format.codecs)) {
        MimeTypes.VIDEO_H265 -> runCatching { NalUnitUtil.getH265BaseLayerCodecsString(format.initializationData) }.getOrNull()
        else -> null
    }

    /** The extractor, with each track's format put through [route] on its way out. */
    private class Wrapped(private val inner: Extractor, private val route: (Format) -> Format) : Extractor {
        override fun sniff(input: ExtractorInput): Boolean = inner.sniff(input)
        override fun getSniffFailureDetails() = inner.sniffFailureDetails
        override fun init(output: ExtractorOutput) = inner.init(RoutedOutput(output, route))
        override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = inner.read(input, seekPosition)
        override fun seek(position: Long, timeUs: Long) = inner.seek(position, timeUs)
        override fun release() = inner.release()
        override fun getUnderlyingImplementation(): Extractor = inner.underlyingImplementation
    }

    private class RoutedOutput(private val inner: ExtractorOutput, private val route: (Format) -> Format) : ExtractorOutput {
        override fun track(id: Int, type: Int): TrackOutput {
            val track = inner.track(id, type)
            return if (type == C.TRACK_TYPE_VIDEO) RoutedTrack(track, route) else track
        }

        override fun endTracks() = inner.endTracks()
        override fun seekMap(seekMap: SeekMap) = inner.seekMap(seekMap)
    }

    private class RoutedTrack(private val inner: TrackOutput, private val route: (Format) -> Format) : TrackOutput {
        override fun format(format: Format) = inner.format(route(format))
        override fun durationUs(durationUs: Long) = inner.durationUs(durationUs)
        override fun sampleData(input: DataReader, length: Int, allowEndOfInput: Boolean, sampleDataPart: Int): Int =
            inner.sampleData(input, length, allowEndOfInput, sampleDataPart)

        override fun sampleData(data: ParsableByteArray, length: Int, sampleDataPart: Int) =
            inner.sampleData(data, length, sampleDataPart)

        override fun sampleMetadata(timeUs: Long, flags: Int, size: Int, offset: Int, cryptoData: TrackOutput.CryptoData?) =
            inner.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }

    companion object {
        private val decoderAnswers = ConcurrentHashMap<String, Boolean>()

        /**
         * Whether the screen says it shows Dolby Vision: the television's own panel, or on a
         * stick what the HDMI sink reported. Read as Media3 reads it for its own fallback.
         */
        fun screenTakesDolbyVision(context: Context): Boolean = runCatching {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@runCatching false
            val display = context.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
                ?: return@runCatching false
            @Suppress("DEPRECATION")
            val types = display.hdrCapabilities?.supportedHdrTypes ?: IntArray(0)
            Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION in types
        }.getOrDefault(false)
    }
}
