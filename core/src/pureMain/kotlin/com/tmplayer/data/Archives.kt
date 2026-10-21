package com.tmplayer.data

import com.tmplayer.i18n.L

/**
 * A compressed file posted in a chat: a ZIP, a RAR, a 7z or one of their volumes. Listed under a
 * chat's videos as something that cannot be played here, with the reason, so a chat that posts its
 * videos packed in archives does not read as a chat with nothing in it.
 */
@androidx.compose.runtime.Immutable
data class ArchiveFile(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val fileName: String,
    val sizeBytes: Long,
    val date: Int,
    val kind: ArchiveKind,
    /** One volume of several (`name.part2.rar`, `name.7z.003`), which is never a whole archive. */
    val volume: Boolean = false,
) {
    /** Why it is not played, in a sentence. */
    val reason: String get() = Archives.reason(this)
}

/** Which kind of archive, as far as the name says. */
enum class ArchiveKind(val label: String) {
    Zip("ZIP"),
    Rar("RAR"),
    SevenZip("7z"),
    /** A tarball or a single compressed stream: `.tar`, `.gz`, `.tgz`, `.bz2`, `.xz`, `.zst`. */
    Other("archive"),
}

object Archives {

    /** The kind [fileName] (or, without a telling name, [mimeType]) is, or null for no archive. */
    fun kindOf(fileName: String, mimeType: String = ""): ArchiveKind? {
        val name = fileName.trim().lowercase()
        return when {
            ZIP.containsMatchIn(name) -> ArchiveKind.Zip
            RAR.containsMatchIn(name) -> ArchiveKind.Rar
            SEVEN_ZIP.containsMatchIn(name) -> ArchiveKind.SevenZip
            OTHER.containsMatchIn(name) -> ArchiveKind.Other
            mimeType.lowercase() in ZIP_TYPES -> ArchiveKind.Zip
            mimeType.lowercase() in RAR_TYPES -> ArchiveKind.Rar
            mimeType.lowercase() == "application/x-7z-compressed" -> ArchiveKind.SevenZip
            else -> null
        }
    }

    /** One numbered volume of a split archive rather than a whole one. */
    fun isVolume(fileName: String): Boolean = VOLUME.containsMatchIn(fileName.trim().lowercase())

    /**
     * The sentence a row carries. A volume needs all of its fellows, and every archive needs
     * unpacking before what is inside it can be played: TMPlayer streams a video straight from
     * Telegram, and a compressed file has to be read whole and unpacked first.
     */
    fun reason(file: ArchiveFile): String = when {
        file.volume -> L.gridArchiveVolumeReason(file.kind.label)
        else -> L.gridArchiveReason(file.kind.label)
    }

    /** Newest first and each message once, as a listing gathers pages of them. */
    fun merge(listed: List<ArchiveFile>, more: List<ArchiveFile>): List<ArchiveFile> =
        if (more.isEmpty()) listed else (listed + more).distinctBy { it.messageId }.sortedByDescending { it.messageId }

    private val ZIP = Regex("""\.zip$|\.zip\.\d{1,4}$|\.z\d{2}$""")
    private val RAR = Regex("""\.rar$|\.rar\.\d{1,4}$|\.r\d{2}$""")
    private val SEVEN_ZIP = Regex("""\.7z$|\.7z\.\d{1,4}$""")
    private val OTHER = Regex("""\.(tar|gz|tgz|bz2|xz|zst)$|\.(tar|gz|tgz|bz2|xz|zst)\.\d{1,4}$""")
    private val VOLUME = Regex("""\.(7z|zip|rar|tar|gz|tgz|bz2|xz|zst)\.\d{1,4}$|\.part\d{1,4}\.rar$|\.[rz]\d{2}$""")
    private val ZIP_TYPES = setOf("application/zip", "application/x-zip-compressed")
    private val RAR_TYPES = setOf("application/x-rar-compressed", "application/vnd.rar", "application/x-rar")
}
