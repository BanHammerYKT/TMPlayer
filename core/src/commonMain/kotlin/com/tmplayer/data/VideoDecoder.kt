package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * Which decoder draws the picture on Android, from Settings, Playback.
 *
 * [Automatic] is the device's own decoder (hardware first), with the app's FFmpeg decoder taking
 * a format the device has none for. [Software] asks for the app's own FFmpeg decoder for every
 * format it has, and the device's software decoder for the rest: slower and warmer, but the cure
 * for a hardware decoder that shows green, blocky or frozen frames. The desktop has its own switch
 * for the same thing (Force software decoding).
 */
enum class VideoDecoder(private val labelText: () -> String, private val detailText: () -> String) {
    Automatic({ L.settingsDecoderAuto }, { L.settingsDecoderAutoDetail }),
    Software({ L.settingsDecoderSoftware }, { L.settingsDecoderSoftwareDetail }),
    ;

    val label: String get() = labelText()
    val detail: String get() = detailText()

    /** The other one: what "Try the other decoder" on a decoding error switches to. */
    val other: VideoDecoder get() = if (this == Automatic) Software else Automatic

    companion object {
        fun from(stored: String?): VideoDecoder = entries.firstOrNull { it.name == stored } ?: Automatic
    }
}
