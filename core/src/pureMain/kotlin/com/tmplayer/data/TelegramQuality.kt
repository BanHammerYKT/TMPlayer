package com.tmplayer.data

/**
 * One of Telegram's own lighter copies of a big video (`MessageVideo.alternativeVideos`, CP80):
 * Telegram makes a 720p, a 480p and the like on its servers from what was uploaded, and plays one
 * of them on a weak line itself. Each is an ordinary MP4 file of the same message, so playing one
 * keeps the message's place, its Watched mark and its bookmarks.
 *
 * Telegram also gives each an HLS playlist; TMPlayer plays the MP4 file, which streams through the
 * same TDLib reader as any other video.
 */
@androidx.compose.runtime.Immutable
data class TelegramQuality(
    val fileId: Int,
    val width: Int,
    val height: Int,
    /** "h264", "h265" or "av1", as Telegram names it. */
    val codec: String,
    val sizeBytes: Long,
)
