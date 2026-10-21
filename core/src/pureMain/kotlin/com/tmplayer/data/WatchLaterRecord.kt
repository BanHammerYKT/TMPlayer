package com.tmplayer.data

/**
 * A video the viewer put aside to watch later, without starring the chat it came from.
 *
 * Carries what a tile needs to be drawn and played without that chat being loaded, as a
 * [WatchedRecord] does. The picture is not kept: a thumbnail's file id belongs to the TDLib
 * instance that handed it out, so Home asks Telegram for the video again (see
 * [ChatRepository.mediaItem]), as it does for Continue watching.
 */
data class WatchLaterRecord(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val title: String,
    /** The file's own name, which can say more than a title taken from the caption. */
    val fileName: String,
    val chatTitle: String,
    val sizeBytes: Long,
    val durationSec: Int,
    val mimeType: String,
    val addedAt: Long,
) {
    val key: String get() = ProgressKeys.of(chatId, messageId)

    fun toMediaItem(): MediaItem = MediaItem(
        chatId = chatId,
        messageId = messageId,
        fileId = fileId,
        title = title,
        sizeBytes = sizeBytes,
        durationSec = durationSec,
        mimeType = mimeType,
        thumbnailFileId = 0,
        miniThumbnail = null,
        date = 0,
        fileName = fileName.ifEmpty { title },
    )

    internal fun encode(): String = listOf(
        addedAt.toString(),
        fileId.toString(),
        title.replace(SEP, ' '),
        fileName.replace(SEP, ' '),
        chatTitle.replace(SEP, ' '),
        sizeBytes.toString(),
        durationSec.toString(),
        mimeType.replace(SEP, ' '),
    ).joinToString(SEP.toString())

    companion object {
        /** Unit separator, for the reason [ResumeRecord] uses it: titles contain everything else. */
        private const val SEP = '\u001F'
        private const val FIELDS = 8

        fun of(item: MediaItem, chatTitle: String, addedAt: Long) = WatchLaterRecord(
            chatId = item.chatId,
            messageId = item.messageId,
            fileId = item.fileId,
            title = item.title,
            fileName = item.fileName,
            chatTitle = chatTitle,
            sizeBytes = item.sizeBytes,
            durationSec = item.durationSec,
            mimeType = item.mimeType,
            addedAt = addedAt,
        )

        /** Rebuilds a record, or null for a line this build cannot trust. */
        internal fun decode(ids: String, encoded: String?): WatchLaterRecord? {
            if (encoded.isNullOrEmpty()) return null
            val idParts = ids.split('_')
            if (idParts.size != 2) return null
            val chatId = idParts[0].toLongOrNull() ?: return null
            val messageId = idParts[1].toLongOrNull() ?: return null
            val parts = encoded.split(SEP)
            // A later build may append fields; everything up to here still reads the same.
            if (parts.size < FIELDS) return null
            return WatchLaterRecord(
                chatId = chatId,
                messageId = messageId,
                addedAt = parts[0].toLongOrNull() ?: return null,
                fileId = parts[1].toIntOrNull() ?: 0,
                title = parts[2],
                fileName = parts[3],
                chatTitle = parts[4],
                sizeBytes = parts[5].toLongOrNull() ?: 0L,
                durationSec = parts[6].toIntOrNull() ?: 0,
                mimeType = parts[7],
            )
        }
    }
}
