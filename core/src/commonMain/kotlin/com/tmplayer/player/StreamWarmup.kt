package com.tmplayer.player

import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.Td
import com.tmplayer.data.valueOrNull
import com.tmplayer.platform.Background
import com.tmplayer.platform.Logger
import dev.g000sha256.tdl.TdlClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.io.RandomAccessFile

/**
 * Brings a video's first bytes, and the index its demuxer jumps to next ([StreamIndex]), onto the
 * disk before the player asks for them, so the start costs no round trip the viewer waits on.
 *
 * Why: TDLib keeps one download per file, and the player's first request used to go out only once
 * the player existed, after the settings reads and the player build; then an MKV's Cues or an
 * MP4's trailing moov sent the download to the end of the file and back, a round trip each way.
 * Warmed, the head is already here and the index too, and [TdByteWindow] reads the index from the
 * disk without moving the download ([TdByteWindow.awaitBytesAt]).
 *
 * The call to make, from anywhere and as often as wanted (it is cheap, deduplicated and never
 * blocks): `StreamWarmup.warm(item)`, or `StreamWarmup.warm(fileId, sizeBytes)`. Wanted on a
 * press of Play (at once), on a card that has had the focus or the pointer for about 250 ms, on
 * a video page's Play button, and when the next episode card comes up.
 *
 * Never in the player's way: a player that opens the file claims it ([claim]), and from then on
 * nothing is asked of TDLib here; claim and request are serialised, so the player's own request
 * is always the last word. Skipped for a file already whole, one the viewer is downloading (that
 * download is not to be moved), a cut video's pieces or a film in parts, and wherever [allowed]
 * says no (Low storage mode, "Save mobile data" on a metered line).
 */
object StreamWarmup {

    /** The platform's say: false in Low storage mode or with "Save mobile data" on a metered line. */
    @Volatile
    var allowed: () -> Boolean = { true }

    /** How much of the start to bring: what the demuxer reads before the first frame. */
    const val HEAD_BYTES = 1L * 1024 * 1024

    /** 1..32; under playback's 32, over thumbnails. */
    private const val PRIORITY = 24

    /** How long one stretch may take before the warm-up gives up on it. */
    private const val STRETCH_TIMEOUT_MS = 8_000L

    private const val POLL_MS = 120L

    /** A file warmed this recently is not warmed again. */
    private const val FRESH_MS = 10 * 60_000L

    /** At most this many warm-ups at once: the newest ones, as the focus moves. */
    private const val AT_ONCE = 2

    private val lock = Mutex()
    private val claimed = HashSet<Int>()
    private val warmedAt = LinkedHashMap<Int, Long>()
    private val jobs = LinkedHashMap<Int, Job>()

    /** Warms [item]'s file; see the class. Does nothing for a cut video or a film in parts. */
    fun warm(item: MediaItem) {
        if (item.pieces.isNotEmpty() || item.parts.size > 1) return
        warm(item.fileId, item.sizeBytes)
    }

    /** Warms file [fileId] of [sizeBytes] bytes (0 when unknown); see the class. */
    fun warm(fileId: Int, sizeBytes: Long) {
        if (fileId <= 0) return
        if (!runCatching { allowed() }.getOrDefault(false)) return
        val now = System.currentTimeMillis()
        synchronized(this) {
            if (fileId in claimed || jobs[fileId]?.isActive == true) return
            if ((warmedAt[fileId] ?: 0L) > now - FRESH_MS) return
            warmedAt[fileId] = now
            if (warmedAt.size > 64) warmedAt.remove(warmedAt.keys.first())
            // The oldest give way to the newest: the focus has moved on from them.
            while (jobs.count { it.value.isActive } >= AT_ONCE) {
                val oldest = jobs.entries.first { it.value.isActive }
                oldest.value.cancel()
                jobs.remove(oldest.key)
            }
            jobs[fileId] = Background.scope.launch { run(fileId, sizeBytes) }
        }
    }

    /**
     * The player opens [fileId]: nothing more is asked of TDLib for it here. Suspends only for the
     * moment a warm-up's own request is being sent, so the player's request always comes after it.
     */
    suspend fun claim(fileId: Int) {
        lock.withLock {
            synchronized(this) {
                claimed += fileId
                jobs.remove(fileId)?.cancel()
            }
        }
    }

    /** The player let go of [fileId]. */
    fun release(fileId: Int) {
        synchronized(this) { claimed -= fileId }
    }

    private fun isClaimed(fileId: Int) = synchronized(this) { fileId in claimed }

    private suspend fun run(fileId: Int, sizeHint: Long) {
        val started = System.currentTimeMillis()
        val session = Td.awaitConnectedSessionOrNull(3_000) ?: return
        val td = session.client
        val file = td.getFile(fileId).valueOrNull ?: return
        if (file.local.isDownloadingCompleted || OfflineDownloads.isDownloading(fileId)) return
        val size = if (file.size > 0) file.size else file.expectedSize.takeIf { it > 0 } ?: sizeHint
        if (size <= 0) return
        val head = minOf(HEAD_BYTES, size)
        if (!fetch(td, fileId, 0, head)) return
        val bytes = readHead(td, fileId, head) ?: return
        val index = StreamIndex.locate(bytes, size)
        if (index != null) fetch(td, fileId, index.start, index.length)
        Logger.i(TAG, "Warmed file $fileId: head ${head / 1024} KB${index?.let { ", index ${it.length / 1024} KB at ${it.start}" } ?: ""} in ${System.currentTimeMillis() - started} ms")
    }

    /** Brings `[offset, offset + length)` onto the disk; false when it could not, or the player came. */
    private suspend fun fetch(td: TdlClient, fileId: Int, offset: Long, length: Long): Boolean {
        if (have(td, fileId, offset) >= length) return true
        lock.withLock {
            if (isClaimed(fileId) || OfflineDownloads.isDownloading(fileId)) return false
            td.downloadFile(fileId, PRIORITY, offset, length, false)
        }
        val done = withTimeoutOrNull(STRETCH_TIMEOUT_MS) {
            while (have(td, fileId, offset) < length) {
                if (isClaimed(fileId)) return@withTimeoutOrNull false
                delay(POLL_MS)
            }
            true
        }
        return done == true
    }

    private suspend fun have(td: TdlClient, fileId: Int, offset: Long): Long =
        td.getFileDownloadedPrefixSize(fileId, offset).valueOrNull?.size ?: 0L

    private suspend fun readHead(td: TdlClient, fileId: Int, length: Long): ByteArray? {
        val path = td.getFile(fileId).valueOrNull?.local?.path?.takeIf { it.isNotBlank() } ?: return null
        return runCatching {
            RandomAccessFile(path, "r").use { f ->
                val n = minOf(length, f.length()).toInt()
                ByteArray(n).also { f.readFully(it) }
            }
        }.getOrNull()
    }

    private const val TAG = "StreamWarmup"
}
