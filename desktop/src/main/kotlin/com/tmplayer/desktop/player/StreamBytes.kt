package com.tmplayer.desktop.player

/**
 * The bytes of one file that is still arriving, as the desktop's media source sees them.
 *
 * In the app this is TDLib's partial file behind [com.tmplayer.player.TdByteWindow]
 * ([TdStreamBytes]); in the tests and the dev harness it is a file being copied at a set rate
 * ([GrowingFileBytes]). [StreamInput] blocks on it and never needs to know which.
 */
interface StreamBytes {

    /** The whole file's length, known before the first read. */
    val size: Long

    /** How many bytes at [position] are on disk right now, or null if that takes a wait. */
    fun cachedAvailable(position: Long): Long?

    /**
     * Suspends until at least one byte at [position] is on disk, aiming the download there if it
     * is somewhere else, and returns how many are. Throws an IOException when it gives up.
     */
    suspend fun awaitBytesAt(position: Long): Long

    /** Reads bytes that [cachedAvailable] or [awaitBytesAt] has already vouched for. */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int

    /**
     * A wait at [position] gave up: asks whatever fetches the bytes for them again from there,
     * for a reader that tries once more (the cast server's). Nothing for bytes on the disk.
     */
    suspend fun askAgain(position: Long) = Unit

    /**
     * Reads up to [length] bytes at [position] only if they are on the disk already, wherever the
     * download is: 0 when they are not. Never moves the download, never waits on the network: for
     * the scrub preview's second decoder ([DiskOnlyInput]), which must not take the playing
     * video's bandwidth or move its range.
     */
    suspend fun readOnDisk(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val here = cachedAvailable(position) ?: return 0
        return read(position, buffer, offset, minOf(length.toLong(), here).toInt())
    }

    /** Stops listening. Reads after this are not expected. */
    fun close()
}
