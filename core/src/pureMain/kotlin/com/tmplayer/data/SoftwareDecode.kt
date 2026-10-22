package com.tmplayer.data

import com.tmplayer.platform.concurrentMap

/**
 * A picture this device would decode in software and too slowly to watch: an AV1 film on a TV
 * stick with no AV1 silicon, where dav1d took about two cores of four and still dropped frames,
 * worst after a seek. Pure, so the line is tested alone; Android asks its codec list whether a
 * hardware decoder takes the format and hands the answer here.
 *
 * What the player does with the answer: on a television or a low-end device ([weak]), a video
 * with a lighter copy of Telegram's that decodes in hardware plays that copy, saying so, and the
 * quality list still offers the upload ([TelegramQualities.forSoftwareDecode]); one without such
 * a copy plays as it is, and a television says once that it may stutter.
 */
object SoftwareDecode {

    const val MIME_H264 = "video/avc"
    const val MIME_HEVC = "video/hevc"
    const val MIME_AV1 = "video/av01"
    const val MIME_VP9 = "video/x-vnd.on2.vp9"

    /**
     * Whether a picture of [mime] at [width] by [height] is too much for this device in software:
     * never with a [hardware] decoder for it, nor on a device that is not [weak]. AV1 from 720p up
     * (dav1d's frame threads are off in the FFmpeg decoder the app carries, so a small CPU gets
     * little help from its cores), and anything else from 1080p up: HEVC 10-bit or VP9 profile 2
     * where the hardware takes only 8-bit, VC-1, H.264 10-bit. Sizes go by either side, so a
     * 1920 by 800 film counts as 1080p. A size not known yet counts as large for AV1 only.
     */
    fun heavy(mime: String?, width: Int, height: Int, hardware: Boolean, weak: Boolean): Boolean {
        if (hardware || !weak || mime.isNullOrEmpty()) return false
        if (!mime.startsWith("video/")) return false
        val known = width > 0 || height > 0
        return if (mime == MIME_AV1) {
            !known || width >= HD_WIDTH || height >= HD_HEIGHT
        } else {
            known && (width >= FULL_HD_WIDTH || height >= FULL_HD_HEIGHT)
        }
    }

    /**
     * Whether the device is weak enough for [heavy] to matter: a television (the sticks are
     * phone chips of years ago at best) or a phone [DeviceFit] finds short of memory.
     */
    fun weak(tv: Boolean, fit: DeviceFit): Boolean = tv || fit.shortOfMemory

    // A little under the named sizes, for a crop or an encoder's rounding.
    private const val HD_WIDTH = 1200
    private const val HD_HEIGHT = 700
    private const val FULL_HD_WIDTH = 1800
    private const val FULL_HD_HEIGHT = 1000

    private val heavyUploads = concurrentMap<String, Boolean>()
    private val noted = concurrentMap<String, Boolean>()

    /**
     * Remembers that this video's upload is [heavy] here, for as long as the app runs, so opening
     * it again goes straight to a lighter copy instead of starting the upload to find out.
     */
    fun rememberHeavy(chatId: Long, messageId: Long) {
        heavyUploads[ProgressKeys.of(chatId, messageId)] = true
    }

    fun knownHeavy(chatId: Long, messageId: Long): Boolean = heavyUploads[ProgressKeys.of(chatId, messageId)] == true

    /** True the first time it is asked for a video while the app runs: the note is said once per video. */
    fun noteOnce(chatId: Long, messageId: Long): Boolean =
        noted.put(ProgressKeys.of(chatId, messageId), true) == null
}
