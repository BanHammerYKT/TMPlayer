// What the JVM tests copied into jsTest (core/js-test-sources.txt) name outside the pure half: the
// JDK's clock and String.format, and MediaMapper's pure helpers, which MediaMapper (TDLib side)
// only hands on to MediaText. Test code only: WebRules calls MediaText itself.
package com.tmplayer.data

internal object System {
    fun nanoTime(): Long = com.tmplayer.platform.monotonicMillis() * 1_000_000
    fun currentTimeMillis(): Long = com.tmplayer.platform.nowMillis()
}

/** `String.format` as far as the tests use it: `%d` with an optional zero padded width. */
internal fun String.format(vararg args: Any?): String {
    var next = 0
    return Regex("""%(0?)(\d*)d""").replace(this) { m ->
        val text = args[next++].toString()
        val width = m.groupValues[2].toIntOrNull() ?: 0
        if (m.groupValues[1] == "0") text.padStart(width, '0') else text.padStart(width)
    }
}

internal object MediaMapper {
    fun looksLikeVideo(fileName: String, mimeType: String): Boolean = MediaText.looksLikeVideo(fileName, mimeType)
    fun displayTitle(fileName: String?, caption: String?, fallback: String): String = MediaText.displayTitle(fileName, caption, fallback)
    fun isGenericName(fileName: String): Boolean = MediaText.isGenericName(fileName)
    fun captionOf(text: String?): String = MediaText.captionOf(text)
    fun shareAlbumCaptions(items: List<MediaItem>): List<MediaItem> = MediaText.shareAlbumCaptions(items)
    fun captionLine(item: MediaItem, repeated: Set<String> = emptySet()): String? = MediaText.captionLine(item, repeated)
    fun repeatedCaptionLines(items: List<MediaItem>): Set<String> = MediaText.repeatedCaptionLines(items)
    fun qualityTags(fileName: String?): List<String> = MediaText.qualityTags(fileName)
}
