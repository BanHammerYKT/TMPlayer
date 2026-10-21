package com.tmplayer.data

/**
 * A video the viewer has finished, or said they have.
 *
 * Carries the same description a [ResumeRecord] does, so the "Watched" list can open the player
 * without the chat it came from being loaded.
 */
data class WatchedRecord(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val title: String,
    val chatTitle: String,
    val sizeBytes: Long,
    val durationSec: Int,
    val watchedAt: Long,
    /** True when the viewer pressed "Mark as watched", false when the player saw the end. */
    val manual: Boolean,
    /** The file's name and its caption's first line, as [ResumeRecord.fileName] keeps them; null on an older line. */
    val fileName: String? = null,
    val caption: String? = null,
) {
    val key: String get() = ProgressKeys.of(chatId, messageId)

    fun toMediaItem(): MediaItem = MediaItem(
        chatId = chatId,
        messageId = messageId,
        fileId = fileId,
        title = title,
        sizeBytes = sizeBytes,
        durationSec = durationSec,
        mimeType = "",
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 0,
        fileName = fileName ?: title,
        caption = caption.orEmpty(),
    )

    internal fun encode(): String = listOf(
        watchedAt.toString(),
        if (manual) "m" else "a",
        fileId.toString(),
        title.replace(SEP, ' '),
        chatTitle.replace(SEP, ' '),
        sizeBytes.toString(),
        durationSec.toString(),
        // Appended: a build that reads seven fields reads this line as it always did.
        fileName.orEmpty().replace(SEP, ' '),
        caption.orEmpty().replace(SEP, ' '),
    ).joinToString(SEP.toString())

    companion object {
        /** Unit separator, for the reason [ResumeRecord] uses it: titles contain everything else. */
        private const val SEP = '\u001F'
        private const val FIELDS = 7

        fun of(item: MediaItem, chatTitle: String, watchedAt: Long, manual: Boolean) = WatchedRecord(
            chatId = item.chatId,
            messageId = item.messageId,
            fileId = item.fileId,
            title = item.title,
            chatTitle = chatTitle,
            sizeBytes = item.sizeBytes,
            durationSec = item.durationSec,
            watchedAt = watchedAt,
            manual = manual,
            fileName = item.fileName.ifBlank { null },
            caption = item.caption.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() },
        )

        /** Rebuilds a record, or null for a line this build cannot trust. */
        internal fun decode(ids: String, encoded: String?): WatchedRecord? {
            if (encoded.isNullOrEmpty()) return null
            val idParts = ids.split('_')
            if (idParts.size != 2) return null
            val chatId = idParts[0].toLongOrNull() ?: return null
            val messageId = idParts[1].toLongOrNull() ?: return null
            val parts = encoded.split(SEP)
            // A later build may append fields; everything up to here still reads the same.
            if (parts.size < FIELDS) return null
            return WatchedRecord(
                chatId = chatId,
                messageId = messageId,
                watchedAt = parts[0].toLongOrNull() ?: return null,
                manual = parts[1] == "m",
                fileId = parts[2].toIntOrNull() ?: 0,
                title = parts[3],
                chatTitle = parts[4],
                sizeBytes = parts[5].toLongOrNull() ?: 0L,
                durationSec = parts[6].toIntOrNull() ?: 0,
                fileName = parts.getOrNull(7)?.takeIf { it.isNotBlank() },
                caption = parts.getOrNull(8)?.takeIf { it.isNotBlank() },
            )
        }
    }
}
