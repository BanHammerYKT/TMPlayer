package com.tmplayer.desktop.player

import com.tmplayer.data.valueOrNull
import com.tmplayer.player.TdByteWindow
import dev.g000sha256.tdl.TdlClient
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * [StreamBytes] over TDLib, through the same [TdByteWindow] Android's `TdDataSource` wraps.
 *
 * Opened once per playback with [start], which subscribes before the first `getFile` (so no update
 * is missed while it is in flight) and learns the size. The download is asked for where the first
 * read lands, not at byte 0: a resumed video's first request goes to where it resumes.
 */
class TdStreamBytes(private val td: TdlClient, val fileId: Int) : StreamBytes {

    private val window = TdByteWindow(td)

    override var size: Long = 0L
        private set

    /** Whether the whole file was already on disk when last looked at. */
    val completed: Boolean get() = window.completed

    /**
     * Watches the file and learns its size; with [from], asks for the download from there at once
     * too, for a piece of a joined video opened at a known offset. Without it the first read
     * decides where the download starts ([TdByteWindow.awaitBytesAt]).
     */
    suspend fun start(from: Long? = null) {
        window.watch(fileId)
        size = window.lookUp()
        // A finished file needs nothing from TDLib, not even a download request, which would only
        // re-enter its scheduler for bytes that are already sitting on disk.
        if (from != null && !window.completed) window.requestUnlessOnDisk(from)
    }

    override fun cachedAvailable(position: Long): Long? = window.cachedAvailable(position)

    override suspend fun awaitBytesAt(position: Long): Long = window.awaitBytesAt(position)

    override fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int =
        window.read(position, buffer, offset, length)

    override suspend fun askAgain(position: Long) = window.askAgainFrom(position)

    /** See [StreamBytes.readOnDisk]: TDLib is asked only what it has, never for a download. */
    override suspend fun readOnDisk(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        if (position >= size) return -1
        val file = td.getFile(fileId).valueOrNull ?: return 0
        val run = if (file.local.isDownloadingCompleted) {
            size - position
        } else {
            td.getFileDownloadedPrefixSize(fileId, position).valueOrNull?.size ?: 0L
        }
        if (run <= 0) return 0
        val path = file.local.path.takeIf { it.isNotBlank() } ?: return 0
        val want = minOf(length.toLong(), run).toInt()
        return FileChannel.open(Paths.get(path), StandardOpenOption.READ).use { channel ->
            channel.read(ByteBuffer.wrap(buffer, offset, want), position).coerceAtLeast(0)
        }
    }

    override fun close() = window.close()
}
