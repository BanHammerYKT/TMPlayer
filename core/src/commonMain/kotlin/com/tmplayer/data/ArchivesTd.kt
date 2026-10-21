package com.tmplayer.data

import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageDocument

/**
 * The archive in [message], or null when it holds anything else. Only documents: a video
 * Telegram sent as a video is never an archive, whatever it is called. The rest of [Archives] is
 * pure and shared with the browser build.
 */
internal fun archiveOf(message: Message): ArchiveFile? {
    val document = (message.content as? MessageDocument)?.document ?: return null
    val name = document.fileName.orEmpty()
    val kind = Archives.kindOf(name, document.mimeType.orEmpty()) ?: return null
    return ArchiveFile(
        chatId = message.chatId,
        messageId = message.id,
        fileId = document.document.id,
        fileName = name,
        sizeBytes = document.document.size.takeIf { it > 0 } ?: document.document.expectedSize,
        date = message.date,
        kind = kind,
        volume = Archives.isVolume(name),
    )
}
