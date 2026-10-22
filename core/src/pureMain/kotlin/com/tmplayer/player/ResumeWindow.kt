package com.tmplayer.player

/**
 * Where in a file the warm-up of a resumed video starts fetching: the byte the demuxer will read
 * after its seek to the saved place, so the first frame there needs no round trip either.
 *
 * The byte comes from the file's own index where the warm-up has it ([StreamIndex.offsetAt]: an
 * MKV's Cues, an MP4's sample tables), else from the place's share of the running time
 * ([estimate]), which is only a guess for a file whose rate varies, so it starts further back.
 * Either way the start is put back a little and down to a whole megabyte, which is how Telegram
 * hands out a large file's parts: the bytes before the key frame (an MP4's sound interleaved
 * ahead of the picture, a Cluster's head) come with it.
 *
 * Pure arithmetic, shared with the browser's warm-up.
 */
object ResumeWindow {

    /** The start is put down to a multiple of this: Telegram's part size for a large file. */
    const val ALIGN_BYTES = 1L * 1024 * 1024

    /** Taken off a start the file's index gave, for what is stored just ahead of the key frame. */
    const val INDEXED_BACK_BYTES = 256L * 1024

    /** Taken off a start worked out from the running time, which can be off either way. */
    const val ESTIMATE_BACK_BYTES = 2L * 1024 * 1024

    /** [offset] less [back], down to a whole megabyte, never below zero. */
    fun aligned(offset: Long, back: Long): Long {
        val from = (offset - back).coerceAtLeast(0L)
        return from - from % ALIGN_BYTES
    }

    /** The start for a byte [offset] the file's index gave, or null when it is not in the file. */
    fun indexed(offset: Long, size: Long): Long? =
        if (offset < 0 || (size > 0 && offset >= size)) null else aligned(offset, INDEXED_BACK_BYTES)

    /**
     * The start for a place [startMs] into a video [durationMs] long and [size] bytes big, by its
     * share of the running time; null when any of them is unknown or the place is past the end.
     */
    fun estimate(startMs: Long, durationMs: Long, size: Long): Long? {
        if (startMs <= 0 || durationMs <= 0 || size <= 0 || startMs >= durationMs) return null
        val at = (size.toDouble() * startMs / durationMs).toLong()
        return aligned(at, ESTIMATE_BACK_BYTES)
    }
}
