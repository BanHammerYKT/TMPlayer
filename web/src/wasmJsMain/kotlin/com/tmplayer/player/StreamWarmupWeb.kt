package com.tmplayer.player

import com.tmplayer.data.MediaItem

@JsFun("(id, size, head, tail) => window.TmTd && window.TmTd.warm && window.TmTd.warm(id, size, head, tail)")
private external fun jsWarm(id: Int, size: Double, head: Double, tail: Double)

@JsFun("() => window.TmPlayer && window.TmPlayer.preload && window.TmPlayer.preload()")
private external fun jsPreload()

/**
 * The desktop's warm up of a video about to be played, in the browser: the head of the file and
 * its last megabyte (where MP4 and MKV often keep their index) are asked of tdweb ahead, as the
 * reelbox measurements advised, and the software decoder starts loading for a file that needs it.
 */
object StreamWarmup {
    private const val HEAD = 2.0 * 1024 * 1024
    private const val TAIL = 1.0 * 1024 * 1024

    fun warm(item: MediaItem) {
        if (item.fileId <= 0 || item.sizeBytes <= 0) return
        jsWarm(item.fileId, item.sizeBytes.toDouble(), HEAD, TAIL)
        if (!item.mimeType.startsWith("video/mp4") && !item.mimeType.startsWith("video/webm")) jsPreload()
    }

    fun warm(fileId: Int, sizeBytes: Long) {
        if (fileId > 0 && sizeBytes > 0) jsWarm(fileId, sizeBytes.toDouble(), HEAD, TAIL)
    }
}
