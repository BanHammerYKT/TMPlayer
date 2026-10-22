package com.tmplayer.player

import com.tmplayer.data.MediaItem
import com.tmplayer.platform.Background
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@JsFun("(id, size, head, tail, at, span) => window.TmTd && window.TmTd.warm && window.TmTd.warm(id, size, head, tail, at, span)")
private external fun jsWarm(id: Int, size: Double, head: Double, tail: Double, at: Double, span: Double)

@JsFun("() => window.TmPlayer && window.TmPlayer.preload && window.TmPlayer.preload()")
private external fun jsPreload()

/**
 * The desktop's warm up of a video about to be played, in the browser: the head of the file and
 * its last two megabytes (where MP4 and MKV often keep their index) are asked of tdweb ahead, as the
 * reelbox measurements advised, and the software decoder starts loading for a file that needs it.
 * A video with a saved place ([warm]'s `startAt`) also has a few megabytes fetched where that place
 * falls by its share of the running time ([ResumeWindow.estimate]), after the head and the tail.
 */
object StreamWarmup {
    private const val HEAD = 4.0 * 1024 * 1024
    private const val TAIL = 2.0 * 1024 * 1024

    /** What is fetched at a saved place: the estimate starts two megabytes early. */
    private const val RESUME_SPAN = 4.0 * 1024 * 1024

    /** How long the saved place may take to read before the warm-up goes ahead without it. */
    private const val START_LOOKUP_MS = 300L

    fun warm(item: MediaItem, startAt: (suspend () -> Long)? = null) {
        if (item.fileId <= 0 || item.sizeBytes <= 0) return
        if (startAt == null) {
            jsWarm(item.fileId, item.sizeBytes.toDouble(), HEAD, TAIL, 0.0, 0.0)
        } else {
            Background.scope.launch {
                val startMs = withTimeoutOrNull(START_LOOKUP_MS) { runCatching { startAt() }.getOrDefault(0L) } ?: 0L
                val at = ResumeWindow.estimate(startMs, item.durationSec * 1000L, item.sizeBytes) ?: 0L
                jsWarm(item.fileId, item.sizeBytes.toDouble(), HEAD, TAIL, at.toDouble(), if (at > 0) RESUME_SPAN else 0.0)
            }
        }
        if (!item.mimeType.startsWith("video/mp4") && !item.mimeType.startsWith("video/webm")) jsPreload()
    }

    fun warm(fileId: Int, sizeBytes: Long) {
        if (fileId > 0 && sizeBytes > 0) jsWarm(fileId, sizeBytes.toDouble(), HEAD, TAIL, 0.0, 0.0)
    }
}
