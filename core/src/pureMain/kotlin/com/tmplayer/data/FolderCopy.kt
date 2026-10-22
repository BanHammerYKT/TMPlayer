package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * Save to folder: copies of finished downloads in a folder the viewer picks, one video or a whole
 * selection at a time, on the phone and the desktop alike. The rules both share live here; the
 * copying itself is each platform's (the Storage Access Framework on Android, plain files on the
 * desktop).
 *
 * The original never moves and is never deleted: the folder gets a copy, named after the video,
 * and a name already taken there gets " (2)", " (3)" and so on rather than being written over.
 * A video whose chat does not allow saving is skipped ([KeepRules.mayExport]), and so is one that
 * is not whole on this device yet; the result says how many of each.
 */
object FolderCopy {

    /** The longest name given to a copy, before its extension. */
    const val NAME_MAX = 120

    /**
     * The copy's file name: [displayName] (the file's own name in the chat, or its title) made safe
     * for every file system, with the extension of [sourceName], the file on the disk.
     */
    fun nameFor(displayName: String, sourceName: String): String {
        val sourceExtension = sourceName.substringAfterLast('.', "")
            .takeIf { it.isNotEmpty() && it.length <= EXTENSION_MAX && it != sourceName && it.all(Char::isLetterOrDigit) }
        val extension = sourceExtension?.lowercase() ?: DEFAULT_EXTENSION
        var base = sanitise(displayName).ifBlank { sanitise(sourceName.substringBeforeLast('.')) }
        if (base.endsWith(".$extension", ignoreCase = true)) base = base.dropLast(extension.length + 1)
        base = base.take(NAME_MAX).trim().trimEnd('.', ' ')
        if (base.isEmpty()) base = FALLBACK_BASE
        if (base.uppercase() in RESERVED) base = "${base}_"
        return "$base.$extension"
    }

    /**
     * [name], or the first "name (n).ext" that [taken] says is free. [taken] is asked of the
     * folder and of the names this batch has already used, so two videos named alike in one
     * selection do not land on one file either.
     */
    fun freeName(name: String, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var n = 2
        while (true) {
            val next = "$base ($n)$extension"
            if (!taken(next)) return next
            n++
        }
    }

    /** What a selection comes to before anything is copied. */
    class Sorted<T>(val copy: List<T>, val forbidden: Int, val unfinished: Int)

    /**
     * Splits [items] into what will be copied and what is skipped: one not whole on the device
     * first (no question asked of Telegram for it), then one whose chat forbids saving, asked of
     * [canBeSaved] at the moment of copying, since a channel can turn that on after the download.
     */
    suspend fun <T> sort(items: List<T>, whole: (T) -> Boolean, canBeSaved: suspend (T) -> Boolean): Sorted<T> {
        val copy = ArrayList<T>()
        var forbidden = 0
        var unfinished = 0
        for (item in items) {
            when {
                !whole(item) -> unfinished++
                !KeepRules.mayExport(canBeSaved(item), inDownloads = true) -> forbidden++
                else -> copy += item
            }
        }
        return Sorted(copy, forbidden, unfinished)
    }

    /** Why a batch stopped before its end. */
    enum class Stop { Cancelled, NoSpace, FolderLost, ReadOnly }

    /**
     * How far a batch has got, for the progress dialog: video [done] of [total] (counted from
     * nought), how far into that one, and the bytes across the whole batch.
     */
    data class Progress(
        val done: Int,
        val total: Int,
        val title: String,
        val fraction: Float,
        val bytesDone: Long = 0,
        val bytesTotal: Long = 0,
    ) {
        /** "Saving 2 of 5". */
        val line: String get() = L.downloadsSaveToFolderProgress(done = (done + 1).coerceAtMost(total.coerceAtLeast(1)), total = total.coerceAtLeast(1))

        /** "1.2 GB of 4.6 GB", or nothing before the sizes are known. */
        val bytesLine: String?
            get() = if (bytesTotal <= 0) {
                null
            } else {
                L.downloadsSaveToFolderBytes(done = L.messages.formatter.bytes(bytesDone), total = L.messages.formatter.bytes(bytesTotal))
            }

        /** The whole batch's share done, for one bar. */
        val overall: Float
            get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else ((done + fraction) / total.coerceAtLeast(1)).coerceIn(0f, 1f)
    }

    /**
     * What the confirmation shows before a byte is written: how many and how much, where to, the
     * room there, and whether it all fits. [freeBytes] is null where the folder will not say.
     */
    class Plan<T>(
        val items: List<T>,
        val folder: String,
        val totalBytes: Long,
        val freeBytes: Long?,
        val forbidden: Int,
        val unfinished: Int,
        /** The newest that fit in [freeBytes], for "Save the ones that fit"; [items] when all fit. */
        val fitting: List<T>,
        val fittingBytes: Long,
    ) {
        val fits: Boolean get() = freeBytes == null || totalBytes <= freeBytes

        /** "3 videos, 4.2 GB". */
        val summary: String get() = L.downloadsSaveToFolderConfirmSummary(count = items.size, size = L.messages.formatter.bytes(totalBytes))

        /** "1.1 GB free in this folder", "Needs 4.2 GB, 1.1 GB free in this folder.", or that it does not say. */
        val spaceLine: String
            get() = when {
                freeBytes == null -> L.downloadsSaveToFolderFreeUnknown
                fits -> L.downloadsSaveToFolderFree(size = L.messages.formatter.bytes(freeBytes))
                else -> L.downloadsSaveToFolderNeeds(needed = L.messages.formatter.bytes(totalBytes), free = L.messages.formatter.bytes(freeBytes))
            }

        /** The skipped, said before the copy as well as after. */
        val skippedLines: List<String>
            get() = buildList {
                if (forbidden > 0) add(L.downloadsSaveToFolderProtected(count = forbidden))
                if (unfinished > 0) add(L.downloadsSaveToFolderUnfinished(count = unfinished))
            }
    }

    /** Builds the [Plan] for [items], each with its [size] and [newest] (when it was downloaded). */
    fun <T> plan(
        items: List<T>,
        folder: String,
        freeBytes: Long?,
        forbidden: Int,
        unfinished: Int,
        size: (T) -> Long,
        newest: (T) -> Long,
    ): Plan<T> {
        val total = items.sumOf { size(it).coerceAtLeast(0) }
        val fitting = if (freeBytes == null || total <= freeBytes) items else fitting(items, size, newest, freeBytes)
        return Plan(items, folder, total, freeBytes, forbidden, unfinished, fitting, fitting.sumOf { size(it).coerceAtLeast(0) })
    }

    /**
     * The videos that fit in [free] bytes, the newest first: each in turn while there is room for
     * it, so a large one that does not fit leaves room for smaller, older ones after it. Returned
     * in [items]' own order.
     */
    fun <T> fitting(items: List<T>, size: (T) -> Long, newest: (T) -> Long, free: Long): List<T> {
        var left = free
        val chosen = HashSet<Int>()
        items.indices.sortedByDescending { newest(items[it]) }.forEach { index ->
            val bytes = size(items[index]).coerceAtLeast(0)
            if (bytes <= left) {
                chosen += index
                left -= bytes
            }
        }
        return items.filterIndexed { index, _ -> index in chosen }
    }

    /** One video that would not copy, and why, in the viewer's words. */
    data class Failure(val title: String, val reason: String)

    /** The largest file a FAT32 drive holds: one byte under 4 GiB. */
    const val FAT32_MAX_BYTES: Long = 4L * 1024 * 1024 * 1024 - 1

    /** What a batch did. */
    data class Outcome(
        val folder: String,
        val copied: Int,
        val forbidden: Int = 0,
        val unfinished: Int = 0,
        val failed: List<Failure> = emptyList(),
        val stop: Stop? = null,
        /** Videos the batch never reached because it stopped. */
        val notReached: Int = 0,
        /** The titles of the copies that made it, for a batch that stopped part way. */
        val saved: List<String> = emptyList(),
        /**
         * The keys of the copies checked to be whole (their size in the folder matches the
         * original), which a Move may delete the original of. A copy whose size the folder would
         * not say is saved but not here, so a Move leaves its original alone.
         */
        val verifiedKeys: List<String> = emptyList(),
        /** For a Move: how many originals went, and how many could not be removed. */
        val moved: Int = 0,
        val notMoved: Int = 0,
    ) {
        /** Everything went: a toast says so, where anything else gets the dialog with its lines. */
        val clean: Boolean
            get() = copied > 0 && forbidden == 0 && unfinished == 0 && failed.isEmpty() && stop == null && notMoved == 0

        /** The one line on top: how many were saved and where, or that nothing was. */
        val headline: String get() = if (copied > 0) L.downloadsSaveToFolderSaved(count = copied, folder = folder) else L.downloadsSaveToFolderNone

        /** The lines under it: why it stopped and what made it, the skipped, the failed by name, the moved. */
        val lines: List<String>
            get() = buildList {
                when (stop) {
                    Stop.NoSpace -> add(L.downloadsSaveToFolderNoSpace)
                    Stop.FolderLost -> add(L.downloadsSaveToFolderLost)
                    Stop.ReadOnly -> add(L.downloadsSaveToFolderReadOnly)
                    Stop.Cancelled -> add(L.downloadsSaveToFolderCancelled)
                    null -> Unit
                }
                if (stop != null && saved.isNotEmpty()) add(L.downloadsSaveToFolderSavedList(titles = saved.joinToString(", ")))
                if (notReached > 0) add(L.downloadsSaveToFolderNotReached(count = notReached))
                if (forbidden > 0) add(L.downloadsSaveToFolderProtected(count = forbidden))
                if (unfinished > 0) add(L.downloadsSaveToFolderUnfinished(count = unfinished))
                failed.forEach { add(L.downloadsSaveToFolderFailed(title = it.title, reason = it.reason)) }
                if (moved > 0) add(L.downloadsSaveToFolderMoved(count = moved))
                if (notMoved > 0) add(L.downloadsSaveToFolderKept(count = notMoved))
            }
    }

    /**
     * Whether an error's words mean the drive is full: ENOSPC on Linux, macOS and Android, and
     * Windows' own sentence for it.
     */
    fun isNoSpace(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return NO_SPACE.any { it in text }
    }

    /** Whether an error's words mean the file is larger than the drive takes (EFBIG, FAT32's 4 GB). */
    fun isTooLarge(message: String?): Boolean {
        val text = message?.lowercase() ?: return false
        return TOO_LARGE.any { it in text }
    }

    /** Whether a file system, by the name the OS gives its type, is FAT (vfat, msdos, FAT32). */
    fun isFat(type: String?): Boolean {
        val t = type?.lowercase()?.trim() ?: return false
        return t == "vfat" || t == "msdos" || t == "fat" || t == "fat32" || t == "fat16"
    }

    private fun sanitise(text: String): String = buildString {
        for (c in text) append(if (c < ' ' || c == '\u007f' || c in FORBIDDEN) ' ' else c)
    }.replace(SPACES, " ").trim().trimStart('.', ' ')

    private const val FORBIDDEN = "\\/:*?\"<>|"
    private val SPACES = Regex("\\s+")
    private const val EXTENSION_MAX = 8
    private const val DEFAULT_EXTENSION = "mp4"
    private const val FALLBACK_BASE = "video"
    private val NO_SPACE = listOf("enospc", "no space left", "not enough space", "disk full", "disk quota exceeded")
    private val TOO_LARGE = listOf("efbig", "file too large", "file is too large")

    /** Names Windows will not give a file, whatever follows the dot. */
    private val RESERVED = setOf("CON", "PRN", "AUX", "NUL") + (1..9).flatMap { listOf("COM$it", "LPT$it") }
}
