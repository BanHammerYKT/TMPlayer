package com.tmplayer.player

import com.tmplayer.data.Failures
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.LocalFilePolicy
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.errorMessage
import com.tmplayer.data.valueOrNull
import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.dto.File as TdFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Paths
import java.nio.file.StandardOpenOption

/**
 * The part of streaming a Telegram file that does not depend on the player reading it.
 *
 * TDLib is asked to fill the file starting at whatever byte the player wants; reads then come off
 * the partial file on disk as soon as the bytes land. Seeking works because the player re-opens
 * at a new offset and this simply points TDLib at that offset instead: no full download, no
 * waiting for the end of a 12 GB remux to watch minute 90.
 *
 * The player on each platform wraps one of these: Media3's `TdDataSource` on Android, and the
 * desktop's media source. They decide how to block and how to report the end of input; this keeps
 * the window of bytes TDLib has confirmed, the one subscription that keeps it current, and the
 * rules for when a stalled read asks TDLib again.
 *
 * One instance serves one source, which may be opened at several files and offsets in turn:
 * [watch], then [lookUp], then reads, then [close], and again.
 *
 * Written against TDLib's public file API only.
 */
class TdByteWindow(private val td: TdlClient) {

    var fileId = -1
        private set

    /** The file's full size, as of the last [lookUp]. */
    var size = 0L
        private set

    private var handle: FileChannel? = null
    private var localPath: String? = null

    /**
     * The stretch of the file a real [TdFile] last confirmed was on disk, so reads inside it need
     * no call to TDLib at all.
     *
     * Players issue a great many small reads, and asking TDLib about the file on each one would
     * put a request round-trip in front of every byte.
     *
     * Held as one value rather than a pair of fields because both ends have to move together: a
     * reader that caught a new start against an old end would be trusting a window that never
     * existed.
     */
    @Volatile
    private var window = Window.EMPTY

    /**
     * A stretch away from TDLib's download that is on the disk all the same, as
     * `getFileDownloadedPrefixSize` confirmed: an MKV's Cues or an MP4's moov that [StreamWarmup]
     * (or an earlier play) brought down. Read from where it is, without moving the download there
     * and back, which used to cost a round trip each way before the first frame. Never in Low
     * storage mode, whose rolls delete what lies outside the window.
     */
    @Volatile
    private var side = Window.EMPTY

    /** Whether the whole file is on disk, as of the last update seen. */
    @Volatile
    var completed = false
        private set

    /** Whether TDLib says it is currently filling the file, as of the last update seen. */
    @Volatile
    private var downloading = false

    /** Every byte of the file on disk, wherever it is, as of the last update seen. */
    @Volatile
    private var downloadedTotal = 0L

    /**
     * One subscription to `updateFile` for the whole of an open source, rather than a fresh one
     * per slow read.
     *
     * The collector runs from [watch] to [close] and keeps [window] warm on its own, so a read
     * that has to wait is woken by the update itself and reads the answer out of memory instead
     * of paying a `getFile` round trip per second of waiting.
     */
    private var updates: Job? = null
    private var scope: CoroutineScope? = null

    /**
     * Bumped once per absorbed update, so a waiting read can tell "TDLib said something" from
     * "the poll timed out" without comparing windows itself.
     */
    private val revision = MutableStateFlow(0L)

    /**
     * Whether this file streams in Low storage mode, as [LowStorageStreams] said at [watch]. Only
     * a shortcut for the fast path; every decision asks [LowStorageStreams] again.
     */
    @Volatile
    private var lowStorage = false

    /**
     * Whether this file streams with "Save mobile data", as [SaveDataStreams] said at [watch].
     * Only a shortcut for the fast path, like [lowStorage]; every decision asks again.
     */
    @Volatile
    private var saveData = false

    /**
     * Up while a roll or a seek in Low storage mode is deleting the file: updates about the file
     * being deleted say nothing true about the one coming next, so they are not absorbed.
     */
    @Volatile
    private var deleting = false

    /** One roll at a time, however many readers find it due together. */
    private val rollLock = Mutex()

    /** Bytes `[start, end)` of the file, as TDLib last reported them. */
    private data class Window(val start: Long, val end: Long) {
        companion object {
            val EMPTY = Window(0, 0)
        }
    }

    /**
     * Points this at [fileId] and starts the one subscription that keeps the window current.
     *
     * Called before the first [lookUp], so nothing arriving while it is in flight is missed: an
     * update dropped there is a second of stall for no reason.
     */
    fun watch(fileId: Int) {
        this.fileId = fileId
        lowStorage = LowStorageStreams.isRolling(fileId)
        saveData = !lowStorage && SaveDataStreams.isActive(fileId)
        stopWatching()
        val watcher = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = watcher
        val watched = fileId
        updates = watcher.launch {
            td.fileUpdates.filter { it.file.id == watched }.collect { update ->
                absorb(update.file)
                revision.value += 1
            }
        }
    }

    /** Asks TDLib about the file, records what it says, and returns its size. */
    suspend fun lookUp(): Long {
        val file = td.getFile(fileId).valueOrNull
            ?: throw IOException("Telegram lost track of file $fileId")
        size = if (file.size > 0) file.size else file.expectedSize
        if (size <= 0) throw IOException("Unknown size for file $fileId")
        absorb(file)
        return size
    }

    /**
     * How many bytes at [target] are already known-good, or `null` if TDLib has to be asked.
     *
     * Never optimistic: it only answers from a window that a real [TdFile] confirmed earlier, and
     * any doubt returns `null` so the slow path re-checks.
     *
     * Both ends of that window matter. TDLib fills one stretch at a time and frees what falls
     * outside it, but the partial file on disk keeps its length, so a read below the window
     * succeeds and hands back a hole instead of failing, and the extractor dies on the zeroes.
     * Seeking forward and then back lands exactly there, so the arithmetic is deferred to
     * [DownloadWindow] rather than repeated here.
     */
    fun cachedAvailable(target: Long): Long? {
        if (localPath == null) return null
        // A roll is due: the slow path performs it before this read is served.
        if (lowStorage && LowStorageStreams.rollDue(fileId, target, size)) return null
        // Likewise an extension, or a change of connection, in "Save mobile data".
        if (saveData && SaveDataStreams.extendDue(fileId, target, size)) return null
        val known = window
        val main = DownloadWindow.availableAt(
            position = target,
            size = size,
            downloadOffset = known.start,
            downloadedPrefixSize = known.end - known.start,
            completed = completed,
        )
        if (main > 0) return main
        val aside = side
        return (if (target >= aside.start && target < aside.end) aside.end - target else 0L).takeIf { it > 0 }
    }

    /**
     * Reads up to [length] bytes at [position] off the file on disk. The caller has already
     * established, through [cachedAvailable] or [awaitBytesAt], that they are there.
     */
    fun read(position: Long, buffer: ByteArray, offset: Int, length: Int): Int {
        val read = synchronized(this) {
            // Checked again under the lock that also guards the path and the window: between the
            // caller's check and this line a roll or a seek may have deleted the file and a new
            // one may stand at the path, holes and all. A read that lost that race is sent back
            // to ask again rather than handed zeros.
            val known = window
            val aside = side
            val fits = when {
                completed -> (size - position).coerceAtLeast(0)
                position >= known.start && position < known.end -> known.end - position
                position >= aside.start && position < aside.end -> aside.end - position
                else -> 0L
            }
            if (fits <= 0) throw EOFException("Window moved away from $position of file $fileId")
            openHandle().read(ByteBuffer.wrap(buffer, offset, minOf(length.toLong(), fits).toInt()), position)
        }
        if (read <= 0) {
            // TDLib reported the bytes but the file on disk is shorter: it was trimmed or
            // re-created underneath us. Drop the cached window too, so the next read goes back
            // and asks TDLib what is really there instead of trusting a stale boundary.
            closeHandle()
            window = Window.EMPTY
            side = Window.EMPTY
            completed = false
            throw EOFException("Short read at $position of file $fileId")
        }
        return read
    }

    /**
     * Stops listening and forgets the window. The player may reopen this at a different offset,
     * or a different file, and nothing learned about the old window is safe to carry across.
     */
    fun close() {
        stopWatching()
        closeHandle()
        window = Window.EMPTY
        side = Window.EMPTY
        completed = false
        if (fileId > 0) StreamWarmup.release(fileId)
    }

    /** Records what a fresh [TdFile] tells us, so later reads can skip the round-trip. */
    private fun absorb(file: TdFile) {
        if (deleting) return
        val path = file.local.path
        val diskFile = path.takeIf { it.isNotBlank() }?.let(::File)
        val usablePath = path.takeIf { diskFile?.isFile == true }
        val availability = LocalFilePolicy.evaluate(
            downloadCompleted = file.local.isDownloadingCompleted,
            pathPresent = diskFile != null,
            regularFile = diskFile?.isFile == true,
            length = diskFile?.length() ?: 0,
            size = file.size,
            expectedSize = file.expectedSize,
        )
        downloading = file.local.isDownloadingActive
        downloadedTotal = file.local.downloadedSize
        // The path and the window move together, under the lock [read] checks them with, so no
        // read can pair a new file with an old window.
        synchronized(this) {
            // TDLib moves a file when a download finishes (the partial name is renamed away), and a
            // deleted file comes back under a new name, so the handle is dropped on any path change
            // rather than reading a stale inode.
            if (usablePath != localPath) {
                runCatching { handle?.close() }
                handle = null
                localPath = usablePath
            }
            if (availability == LocalFileAvailability.Complete) {
                completed = true
                window = Window(0, size)
            } else {
                completed = false
                val start = file.local.downloadOffset
                window = Window(start, start + file.local.downloadedPrefixSize)
            }
        }
    }

    private fun stopWatching() {
        updates = null
        scope?.cancel()
        scope = null
    }

    /**
     * Suspends until at least one byte at [target] is on disk, restarting the download whenever
     * TDLib's window has drifted away from where the player is reading.
     *
     * The collector is doing the listening; this only decides when to give up and when to ask
     * TDLib for a download that is not happening. A read that outlives its own subscription (the
     * collector died with the client) still makes progress, because a poll that hears nothing
     * falls back to `getFile`.
     */
    suspend fun awaitBytesAt(target: Long): Long {
        if (lowStorage) rollIfDue(target)
        if (saveData) extendIfDue(target)
        // The collector may already have absorbed what this read needs.
        cachedAvailable(target)?.let { return it }

        val file = td.getFile(fileId).valueOrNull ?: throw IOException("File $fileId disappeared")
        var available = available(file, target)
        if (available > 0) return available
        onDiskAt(target).takeIf { it > 0 }?.let { return it }

        // Wall clock, not a count of turns round the loop: a file updating busily without ever
        // reaching the byte being read must still get the full stall timeout.
        val deadline = System.nanoTime() + STALL_TIMEOUT_MS * 1_000_000
        var nudgeAt = System.nanoTime() + NUDGE_AFTER_MS * 1_000_000
        var totalAtAsk = downloadedTotal
        var nudges = 0
        while (available == 0L) {
            val known = window
            // TDLib can take a request, report itself busy, and fill everything but the bytes that
            // were asked for: seen on the jump to an MKV's index in its last half megabyte, where
            // tens of megabytes came down elsewhere and the prefix at the offset stayed at zero
            // until the viewer backed out and opened the video again. That reopen is what this
            // does, without the viewer: drop TDLib's download and ask afresh, from the byte itself
            // and then from the start of its megabyte, which is how Telegram hands out parts.
            // Only when bytes are visibly arriving somewhere else: a slow link bringing nothing at
            // all is left to the stall timeout, since restarting it every few seconds would only
            // make it slower.
            if (
                downloading &&
                System.nanoTime() >= nudgeAt &&
                downloadedTotal - totalAtAsk >= NUDGE_ELSEWHERE_BYTES
            ) {
                val from = if (nudges % 2 == 0) target else target - target % NUDGE_ALIGN_BYTES
                nudges++
                Logger.i(TAG, "No bytes at $target of file $fileId; asking again from $from ($nudges)")
                // A download the viewer asked to keep shares this file in TDLib; it is not ours to
                // cancel, and the fresh request below moves it just the same.
                if (!OfflineDownloads.isDownloading(fileId)) {
                    td.cancelDownloadFile(fileId, onlyIfPending = false)
                }
                requestDownloadFrom(from)
                nudgeAt = System.nanoTime() + NUDGE_AFTER_MS * 1_000_000
                totalAtAsk = downloadedTotal
            }
            if (DownloadWindow.needsRestart(
                    position = target,
                    downloadOffset = known.start,
                    downloadedPrefixSize = known.end - known.start,
                    active = downloading,
                    completed = completed,
                )
            ) {
                requestDownloadFrom(target)
            }

            // updateFile arrives on every few hundred KB, so this normally returns immediately;
            // the timeout is only there so a silent connection still gets re-poked.
            val seen = revision.value
            val heard = withTimeoutOrNull(POLL_INTERVAL_MS) {
                revision.first { it != seen }
            } != null

            available = if (heard) {
                cachedAvailable(target) ?: 0L
            } else {
                // Silence. Either nothing is moving, or this source has no collector to hear it,
                // so the question goes to TDLib directly.
                val fresh = td.getFile(fileId).valueOrNull
                    ?: throw IOException("File $fileId disappeared")
                available(fresh, target)
            }
            if (available == 0L && System.nanoTime() >= deadline) {
                throw IOException("Telegram stopped sending file $fileId at byte $target")
            }
        }
        return available
    }

    /**
     * Bytes at [target] on the disk outside TDLib's current download (see [side]), recorded so the
     * reads can be served without re-aiming the download; 0 when there are none or it cannot say.
     */
    private suspend fun onDiskAt(target: Long): Long {
        if (lowStorage || localPath == null) return 0
        val here = td.getFileDownloadedPrefixSize(fileId, target).valueOrNull?.size ?: return 0
        if (here <= 0) return 0
        synchronized(this) { side = Window(target, minOf(size, target + here)) }
        return here
    }

    private fun available(file: TdFile, target: Long): Long {
        absorb(file)
        if (localPath == null) return 0
        return DownloadWindow.availableAt(
            position = target,
            size = size,
            downloadOffset = file.local.downloadOffset,
            downloadedPrefixSize = file.local.downloadedPrefixSize,
            completed = completed,
        )
    }

    /**
     * After a read at [target] gave up ("Telegram stopped sending"): TDLib's download of the file
     * dropped and asked for afresh from [target], the reopen the stall wait does when bytes arrive
     * elsewhere, for a reader (the cast server's) that tries again rather than failing. A download
     * the viewer asked to keep is left running, and moved by the fresh request just the same.
     */
    suspend fun askAgainFrom(target: Long) {
        if (completed) return
        Logger.i(TAG, "Asking again for file $fileId from $target after a stall")
        if (!OfflineDownloads.isDownloading(fileId)) {
            td.cancelDownloadFile(fileId, onlyIfPending = false)
        }
        requestDownloadFrom(target)
    }

    /**
     * `limit = 0` means "keep going to the end of the file", exactly what playback wants, unless
     * Low storage mode or "Save mobile data" bounds the request.
     *
     * The result is checked rather than dropped. A refused request looks exactly like a slow
     * one from here (no bytes arrive), and swallowing it turns a flood wait or an expired file
     * reference into a minute of blank screen followed by "Telegram stopped sending", which
     * says nothing a viewer can act on.
     */
    suspend fun requestDownloadFrom(offset: Long, roll: Boolean = false) {
        // In Low storage mode the request is bounded to the window, and one that would leave more
        // than the window on disk deletes the file first: a roll, or a seek far from what is here.
        val bounded = LowStorageStreams.plan(fileId, offset, size, roll)
        if (bounded?.deleteFirst == true) deleteForLowStorage(offset)
        // On mobile data with "Save mobile data" on, a request reaches only so far ahead and is
        // extended as reading nears its end; nothing is deleted. Low storage mode's own window
        // wins when both apply, which [SaveDataStreams] sees to.
        val saving = if (bounded == null) SaveDataStreams.plan(fileId, offset, size) else null
        // Any warm-up of this file stops here, and its last request is already out: this one wins.
        StreamWarmup.claim(fileId)
        val result = td.downloadFile(
            fileId = fileId,
            priority = PLAYBACK_PRIORITY,
            offset = offset,
            limit = bounded?.limit ?: saving ?: 0L,
            synchronous = false,
        )
        val error = result.errorMessage
        if (error != null) throw IOException(Failures.humanise(error))
        // After a delete the window is empty, which the wait would read as a seek far ahead and
        // move the download again. What TDLib says now is the new file, starting at [offset].
        if (bounded?.deleteFirst == true) td.getFile(fileId).valueOrNull?.let(::absorb)
    }

    /**
     * Rolls the Low storage window on when reading at [target] has nearly reached the end of it:
     * a seek this source makes to itself, to just behind [target], through [requestDownloadFrom],
     * which deletes the file first because keeping it would double what is on disk. Readers that
     * find the roll due meanwhile wait here, and read only once TDLib reports the new file.
     */
    private suspend fun rollIfDue(target: Long) = rollLock.withLock {
        if (!LowStorageStreams.rollDue(fileId, target, size)) return@withLock
        val from = LowStorageWindow.rollStart(target)
        Logger.i(TAG, "Low storage: rolling file $fileId on at $target, from $from")
        requestDownloadFrom(from, roll = true)
    }

    /**
     * Asks TDLib again when [SaveDataStreams] says a read at [target] is due one: reading has
     * nearly reached the end of the stretch asked for on mobile data, or the connection changed
     * between metered and not since the last request. The request starts at [target], on a part
     * boundary, and deletes nothing: TDLib skips what it already has and carries on from the end
     * of it. Readers that find it due meanwhile wait here, as for a roll.
     */
    private suspend fun extendIfDue(target: Long) = rollLock.withLock {
        if (!SaveDataStreams.extendDue(fileId, target, size)) return@withLock
        val from = SaveDataWindow.extendStart(target)
        Logger.i(TAG, "Save mobile data: asking again for file $fileId from $from")
        requestDownloadFrom(from)
    }

    /**
     * Deletes the file ahead of a request from [offset] in Low storage mode.
     *
     * The window is dropped first, under the lock reads take, so nothing is read from a file that
     * is going; updates about it are ignored until TDLib has been asked for the next one.
     */
    private suspend fun deleteForLowStorage(offset: Long) {
        synchronized(this) {
            deleting = true
            window = Window.EMPTY
        }
        try {
            Logger.i(TAG, "Low storage: deleting file $fileId to restart at $offset")
            runCatching { td.deleteFile(fileId) }
        } finally {
            deleting = false
        }
    }

    /**
     * Call under the instance lock. [awaitBytesAt] has already recorded a usable path.
     *
     * A NIO channel rather than a `RandomAccessFile`: on Windows, NIO opens with delete sharing,
     * so TDLib can still rename the partial file into place when the download finishes while
     * the player has it open; `java.io` would hold the rename off with a sharing violation. On
     * Android and Linux the two read the same.
     */
    private fun openHandle(): FileChannel = handle ?: run {
        val path = localPath ?: throw IOException("No local file for $fileId yet")
        FileChannel.open(Paths.get(path), StandardOpenOption.READ).also { handle = it }
    }

    private fun closeHandle() = synchronized(this) {
        runCatching { handle?.close() }
        handle = null
        localPath = null
    }

    companion object {
        /** How long a read may wait for its byte before the stream is declared stalled. */
        const val STALL_TIMEOUT_MS = 60_000L

        /** 1..32; playback gets the top of the range so thumbnails never starve it. */
        private const val PLAYBACK_PRIORITY = 32
        private const val POLL_INTERVAL_MS = 1_000L

        /** The Android log tag this has always written under, from before it left the source. */
        private const val TAG = "TdDataSource"

        /** How long a busy download may bring nothing at the read position before it is redone. */
        private const val NUDGE_AFTER_MS = 3_000L
        private const val NUDGE_ALIGN_BYTES = 1024L * 1024

        /** What has to arrive elsewhere in the file meanwhile for the wait to count as stuck. */
        private const val NUDGE_ELSEWHERE_BYTES = 2L * 1024 * 1024
    }
}
