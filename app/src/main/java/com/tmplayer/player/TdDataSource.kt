package com.tmplayer.player

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DataSourceException
import dev.g000sha256.tdl.TdlClient
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.EOFException
import java.io.IOException
import java.io.InterruptedIOException

/** `tdfile://<fileId>`, the only URI scheme [TdDataSource] understands. */
fun tdFileUri(fileId: Int): Uri = Uri.parse("tdfile://$fileId")

/**
 * Streams a Telegram file straight into the player without downloading it first.
 *
 * The Media3 shell around [TdByteWindow], which does the streaming: this turns Media3's open, read
 * and close into calls on it, blocks Media3's loading thread while a read waits for its bytes,
 * and reports the end of input the way Media3 expects.
 */
@UnstableApi
class TdDataSource(td: TdlClient) : BaseDataSource(true) {

    private val bytes = TdByteWindow(td)

    private var uri: Uri? = null
    private var position = 0L
    private var bytesRemaining = 0L
    private var opened = false

    /**
     * Bytes read ahead of a small read, served to the reads that follow from memory. Media3's
     * Matroska reader asks for one or two bytes at a time while it walks a file's index (its
     * Cues, near the end): read one by one through the window, a 250 KB index took eight seconds
     * on a 1 GB TV before the first frame. Only bytes already on the disk are taken, so this
     * never waits longer than the small read would have.
     */
    private val ahead = ByteArray(AHEAD_BYTES)
    private var aheadAt = -1L
    private var aheadLength = 0

    override fun getUri(): Uri? = uri

    override fun open(dataSpec: DataSpec): Long {
        val startedAt = SystemClock.elapsedRealtime()
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val fileId = dataSpec.uri.authority?.toIntOrNull()
            ?: dataSpec.uri.lastPathSegment?.toIntOrNull()
            ?: throw IOException("Not a Telegram file URI: ${dataSpec.uri}")
        position = dataSpec.position
        aheadAt = -1L

        // Subscribed before the first getFile, so nothing arriving while it is in flight is
        // missed: an update dropped there is a second of stall for no reason.
        bytes.watch(fileId)

        val size = blocking(OPEN_TIMEOUT_MS) { bytes.lookUp() }
        if (position > size) throw DataSourceException(C.RESULT_END_OF_INPUT)

        bytesRemaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            size - position
        } else {
            minOf(dataSpec.length, size - position)
        }

        // A finished file needs nothing from TDLib, not even a download request, which would
        // only re-enter its scheduler for bytes that are already sitting on disk.
        if (!bytes.completed) blocking(OPEN_TIMEOUT_MS) { bytes.requestUnlessOnDisk(position) }

        opened = true
        transferStarted(dataSpec)
        // Only a slow one is written down: the opens of a demuxer jumping to an index and back are
        // where a start's seconds can hide, and a fast open is not news.
        val took = SystemClock.elapsedRealtime() - startedAt
        if (took >= SLOW_LOG_MS) Log.i(LOG_TAG, "open of file $fileId at $position took $took ms")
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val read = readOnce(buffer, offset, length)

        position += read
        bytesRemaining -= read
        bytesTransferred(read)
        return read
    }

    private fun readOnce(buffer: ByteArray, offset: Int, length: Int): Int {
        // Already read ahead: straight from memory.
        if (aheadAt >= 0 && position >= aheadAt && position < aheadAt + aheadLength) {
            val from = (position - aheadAt).toInt()
            val count = minOf(length.toLong(), (aheadLength - from).toLong(), bytesRemaining).toInt()
            System.arraycopy(ahead, from, buffer, offset, count)
            return count
        }
        // A small read: fill the read-ahead with what the disk has from here, then serve from it.
        if (length < SMALL_READ_BYTES) {
            val filled = readThrough(ahead, 0, AHEAD_BYTES)
            aheadAt = position
            aheadLength = filled
            val count = minOf(length, filled)
            System.arraycopy(ahead, 0, buffer, offset, count)
            return count
        }
        return readThrough(buffer, offset, length)
    }

    private fun readThrough(buffer: ByteArray, offset: Int, length: Int): Int {
        var attempt = 0
        while (true) {
            // Fast path: no coroutine and no TDLib round-trip, which is all but a handful of reads.
            val available = bytes.cachedAvailable(position) ?: awaitLogged()
            val wanted = minOf(length.toLong(), bytesRemaining, available).toInt()
            try {
                return bytes.read(position, buffer, offset, wanted)
            } catch (e: EOFException) {
                // The window moved between the check and the read: a Low storage roll, or TDLib
                // re-creating the file. Asking again finds the bytes wherever they are now.
                if (++attempt >= SHORT_READ_RETRIES) throw e
            }
        }
    }

    /** The slow path of a read: waits for bytes at [position], and says so when that took a while. */
    private fun awaitLogged(): Long {
        val startedAt = SystemClock.elapsedRealtime()
        val available = blocking(READ_TIMEOUT_MS) { bytes.awaitBytesAt(position) }
        val took = SystemClock.elapsedRealtime() - startedAt
        if (took >= SLOW_LOG_MS) Log.i(LOG_TAG, "waited $took ms for bytes at $position of file ${bytes.fileId}")
        return available
    }

    override fun close() {
        // Media3 may reopen this instance at a different offset; the window forgets the old one.
        bytes.close()
        aheadAt = -1L
        uri = null
        if (opened) {
            opened = false
            transferEnded()
        }
    }

    /**
     * Media3 calls [open] and [read] on its own loading thread and cancels a load by
     * interrupting it, so blocking here is expected; it just has to stay interruptible.
     */
    private fun <T> blocking(timeoutMs: Long, block: suspend () -> T): T = try {
        runBlocking { withTimeout(timeoutMs) { block() } }
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
        throw InterruptedIOException("Cancelled")
    } catch (e: TimeoutCancellationException) {
        throw IOException("Telegram did not respond in time", e)
    }

    class Factory(private val client: TdlClient) : DataSource.Factory {
        override fun createDataSource(): DataSource = TdDataSource(client)
    }

    private companion object {
        const val OPEN_TIMEOUT_MS = 120_000L
        const val SHORT_READ_RETRIES = 3

        /** How much a small read takes ahead from the disk, and what counts as small. */
        const val AHEAD_BYTES = 64 * 1024
        const val SMALL_READ_BYTES = 4 * 1024

        /** An open or a wait for bytes this slow goes to the start log, where a device pass reads it. */
        const val SLOW_LOG_MS = 250L
        const val LOG_TAG = "PlayerStart"

        /**
         * The read's own ceiling, a little above the stall timeout it wraps.
         *
         * The inner wait is the one that decides when a stalled read fails; this is only the
         * backstop for a wait that never returns at all. Keep it above
         * [TdByteWindow.STALL_TIMEOUT_MS].
         */
        const val READ_TIMEOUT_MS = TdByteWindow.STALL_TIMEOUT_MS + 10_000L
    }
}
