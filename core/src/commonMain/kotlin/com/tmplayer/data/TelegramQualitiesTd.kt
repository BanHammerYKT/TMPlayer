package com.tmplayer.data

/**
 * Telegram's copies as [MediaItem.qualities]: only the ones lighter than the upload (a copy as
 * big is no help to anybody), one per height (H.264 first, which every device decodes, then the
 * smaller file), best first.
 */
fun TelegramQualities.fromTdlib(
    alternatives: List<dev.g000sha256.tdl.dto.AlternativeVideo>,
    uploadHeight: Int,
    uploadSize: Long,
): List<TelegramQuality> = arrange(
    alternatives.mapNotNull { alt ->
        val file = alt.video
        if (file.id == 0) return@mapNotNull null
        TelegramQuality(
            fileId = file.id,
            width = alt.width,
            height = alt.height,
            codec = alt.codec.orEmpty(),
            sizeBytes = MediaMapper.fileSize(file.size, file.expectedSize),
        )
    },
    uploadHeight,
    uploadSize,
)
