package com.tmplayer.data

import com.tmplayer.platform.concurrentMap
import com.tmplayer.player.StreamStats

/**
 * Choosing among the upload and Telegram's copies of it: which there are, what to call them, and
 * which one to play when Save mobile data is on or the line is slow. Pure, so it is tested alone.
 */
object TelegramQualities {

    /** The tallest copy Save mobile data plays on mobile data, where a lighter one exists. */
    const val SAVE_DATA_HEIGHT = 720

    /**
     * How much faster than a copy's own rate the line has to be for that copy to play without
     * stopping: room for the line to dip, and for the read ahead the player keeps.
     */
    const val HEADROOM = 1.3

    /** [fromTdlib]'s rules over copies already read, for a test. */
    fun arrange(copies: List<TelegramQuality>, uploadHeight: Int, uploadSize: Long): List<TelegramQuality> =
        copies
            .filter { it.height > 0 }
            .filter { uploadHeight <= 0 || it.height < uploadHeight }
            .filter { uploadSize <= 0 || it.sizeBytes <= 0 || it.sizeBytes < uploadSize }
            .groupBy { label(it.height) }
            .map { (_, same) -> same.sortedWith(compareBy<TelegramQuality> { codecRank(it.codec) }.thenBy { it.sizeBytes }).first() }
            .sortedByDescending { it.height }

    /** H.264 plays on everything; HEVC on most; AV1 on few of the boxes people plug into a TV. */
    private fun codecRank(codec: String): Int = when (codec.lowercase()) {
        "h264", "avc", "avc1" -> 0
        "h265", "hevc" -> 1
        else -> 2
    }

    /** "720p", "1080p", "4K": a height named the way a viewer knows it, a 718 read as 720. */
    fun label(height: Int): String {
        if (height >= 2000) return "4K"
        val named = STANDARD_HEIGHTS.minByOrNull { kotlin.math.abs(it - height) }
        val near = named != null && kotlin.math.abs(named - height) <= named * 0.06
        return "${if (near) named else height}p"
    }

    private val STANDARD_HEIGHTS = listOf(144, 240, 360, 480, 540, 720, 1080, 1440)

    /** The copy [item] is playing in place of its upload, or null when it plays the upload. */
    fun playing(item: MediaItem): TelegramQuality? =
        if (item.playingQuality == 0) null else item.qualities.firstOrNull { it.fileId == item.fileId }
            ?: item.qualities.firstOrNull { it.height == item.playingQuality }

    /**
     * [item] playing Telegram's [quality] instead of the upload: the same message, its own file
     * and size, the upload's kept to go back to ([upload]).
     */
    fun withQuality(item: MediaItem, quality: TelegramQuality): MediaItem {
        val base = upload(item)
        return base.copy(
            fileId = quality.fileId,
            sizeBytes = quality.sizeBytes.takeIf { it > 0 } ?: base.sizeBytes,
            width = quality.width,
            height = quality.height,
            mimeType = "video/mp4",
            onDevice = false,
            locality = MediaItem.Locality.Remote,
            playingQuality = quality.height,
            uploadFileId = base.fileId,
            uploadSizeBytes = base.sizeBytes,
        )
    }

    /**
     * [item] as the upload again. The width and height are Telegram's copy's until the message is
     * read again; nothing decides anything by them once the upload plays.
     */
    fun upload(item: MediaItem): MediaItem {
        if (item.playingQuality == 0 || item.uploadFileId == 0) return item
        return item.copy(
            fileId = item.uploadFileId,
            sizeBytes = item.uploadSizeBytes.takeIf { it > 0 } ?: item.sizeBytes,
            width = 0,
            height = 0,
            playingQuality = 0,
            uploadFileId = 0,
            uploadSizeBytes = 0,
        )
    }

    /**
     * The file to play for [fresh], a message just read, when the copy wanted is Telegram's
     * [height] (0 for the upload): that copy's file if the message still has it, else the upload's.
     */
    fun fileFor(fresh: MediaItem, height: Int): Int =
        if (height == 0) fresh.fileId else fresh.qualities.firstOrNull { it.height == height }?.fileId ?: fresh.fileId

    /** Bytes a second the video needs to play as it arrives, from its size and length; 0 unknown. */
    fun rate(sizeBytes: Long, durationSec: Int): Long =
        if (sizeBytes <= 0 || durationSec <= 0) 0 else sizeBytes / durationSec

    /**
     * Why a lighter copy was picked for the viewer, for the line that says so. [Software]: the
     * upload's format has no hardware decoder on this device, which plays it in software and
     * cannot keep up ([SoftwareDecode]).
     */
    enum class Why { SaveData, SlowLine, Software }

    data class Choice(val quality: TelegramQuality, val why: Why)

    /**
     * Whether [choose] could pick a lighter copy for a video of [sizeBytes] and [durationSec] at
     * all, before its copies are known: with [saveData], or on a line measured too slow for it.
     * False lets a player skip reading the message again for a choice that cannot happen.
     */
    fun mightChoose(sizeBytes: Long, durationSec: Int, saveData: Boolean, lineBytesPerSec: Long?): Boolean {
        if (saveData) return true
        val line = lineBytesPerSec?.takeIf { it >= StreamStats.MIN_MEANINGFUL_SPEED } ?: return false
        val need = rate(sizeBytes, durationSec)
        return need > 0 && line < need * HEADROOM
    }

    /**
     * The copy to play for [item] as it opens, or null for the upload.
     *
     * Only for a video streaming the upload (a copy already on this device plays as it is, and a
     * copy the viewer chose stays chosen). With [saveData] (Save mobile data, on mobile data now)
     * the best copy no taller than [SAVE_DATA_HEIGHT], else the lightest. Otherwise, where the line
     * is known ([lineBytesPerSec], measured on this run) and too slow for the upload with
     * [HEADROOM], the best copy the line carries, else the lightest. A line fast enough, or not
     * measured yet, plays the upload.
     */
    fun choose(item: MediaItem, saveData: Boolean, lineBytesPerSec: Long?): Choice? {
        if (item.qualities.isEmpty() || item.playingQuality != 0 || item.onDevice || item.isJoined) return null
        val lightest = item.qualities.minByOrNull { it.height } ?: return null
        if (saveData) {
            val pick = item.qualities.filter { it.height <= SAVE_DATA_HEIGHT }.maxByOrNull { it.height } ?: lightest
            return Choice(pick, Why.SaveData)
        }
        val line = lineBytesPerSec?.takeIf { it >= StreamStats.MIN_MEANINGFUL_SPEED } ?: return null
        val need = rate(item.sizeBytes, item.durationSec)
        if (need <= 0 || line >= need * HEADROOM) return null
        val fits = item.qualities.filter { q ->
            val r = rate(q.sizeBytes, item.durationSec)
            r > 0 && line >= r * HEADROOM
        }.maxByOrNull { it.height }
        return Choice(fits ?: lightest, Why.SlowLine)
    }

    /**
     * The copy to play in place of an upload this device would decode in software and too slowly
     * ([SoftwareDecode.heavy]), or null when none of [qualities] would do better: the tallest one
     * [inHardware] says this device decodes in hardware, H.264 before another codec at the same
     * height. With [saveData] (Save mobile data in force) only copies no taller than
     * [SAVE_DATA_HEIGHT] are looked at while there are any, as [choose] would.
     */
    fun forSoftwareDecode(
        qualities: List<TelegramQuality>,
        saveData: Boolean,
        inHardware: (TelegramQuality) -> Boolean,
    ): TelegramQuality? {
        val playable = qualities.filter { it.height > 0 && inHardware(it) }
        val pool = if (saveData) playable.filter { it.height <= SAVE_DATA_HEIGHT }.ifEmpty { playable } else playable
        return pool.sortedWith(compareByDescending<TelegramQuality> { it.height }.thenBy { codecRank(it.codec) }).firstOrNull()
    }

    /** Media3's sample MIME type for a copy's codec as TDLib names it, or null for one not known here. */
    fun mimeOf(codec: String): String? = when (codec.lowercase()) {
        "h264", "avc", "avc1" -> SoftwareDecode.MIME_H264
        "h265", "hevc", "hvc1", "hev1" -> SoftwareDecode.MIME_HEVC
        "av1", "av01" -> SoftwareDecode.MIME_AV1
        "vp9", "vp09" -> SoftwareDecode.MIME_VP9
        else -> null
    }

    /**
     * The next copy down from what [item] plays, for "Play Telegram's 480p" on the sheet that says
     * the video stopped arriving; null when nothing lighter is left.
     */
    fun lighter(item: MediaItem): TelegramQuality? {
        val now = playing(item)?.height ?: Int.MAX_VALUE
        return item.qualities.filter { it.height < now }.maxByOrNull { it.height }
    }
}

/**
 * Where a video opened from a post starts (CP80): a t.me link's `?t=` first, since it was asked
 * for in so many words; then the viewer's own saved place; then the moment the poster chose
 * (`MessageVideo.startTimestamp`); else the start. A moment past the end is no moment.
 */
object PostStart {

    fun startMs(linkSec: Int, savedMs: Long, postSec: Int, durationSec: Int): Long {
        fun fits(sec: Int) = sec > 0 && (durationSec <= 0 || sec < durationSec)
        return when {
            fits(linkSec) -> linkSec * 1000L
            savedMs > 0 -> savedMs
            fits(postSec) -> postSec * 1000L
            else -> 0L
        }
    }

    /**
     * The `t` of a t.me link in seconds, or null: `t=90`, `t=90s`, `t=1m30s`, `t=1h2m3s` and
     * `t=1:30` are all ones people write. TDLib reads the same for a link it resolves
     * (`MessageLinkInfo.mediaTimestamp`); this is for a link it is not asked about.
     */
    fun fromLink(url: String): Int? {
        val query = url.substringAfter('?', "").substringBefore('#')
        val value = query.split('&').firstOrNull { it.startsWith("t=") }?.removePrefix("t=") ?: return null
        return parse(value)
    }

    fun parse(value: String): Int? {
        val v = value.trim().lowercase()
        if (v.isEmpty()) return null
        v.toIntOrNull()?.let { return it.takeIf { n -> n >= 0 } }
        if (':' in v) {
            val parts = v.split(':').map { it.toIntOrNull() ?: return null }
            if (parts.size > 3 || parts.any { it < 0 }) return null
            return parts.fold(0) { acc, n -> acc * 60 + n }
        }
        val match = UNITS.matchEntire(v) ?: return null
        val (h, m, s) = match.destructured
        if (h.isEmpty() && m.isEmpty() && s.isEmpty()) return null
        return (h.toIntOrNull() ?: 0) * 3600 + (m.toIntOrNull() ?: 0) * 60 + (s.toIntOrNull() ?: 0)
    }

    private val UNITS = Regex("""(?:(\d+)h)?(?:(\d+)m)?(?:(\d+)s)?""")
}

/**
 * Which of Telegram's copies the viewer chose for a video before it opens (the detail page's
 * quality line), handed to the player the way [BookmarkJump] hands a moment: the height of the
 * copy, or 0 for the upload as it was posted, which also keeps the player from choosing a lighter
 * one by itself. Taken once.
 */
object QualityWish {
    private val pending = concurrentMap<String, Int>()

    fun request(chatId: Long, messageId: Long, height: Int) {
        pending[ProgressKeys.of(chatId, messageId)] = height.coerceAtLeast(0)
    }

    /** The choice standing for this video, without taking it, for the page that made it. */
    fun peek(chatId: Long, messageId: Long): Int? = pending[ProgressKeys.of(chatId, messageId)]

    fun take(chatId: Long, messageId: Long): Int? = pending.remove(ProgressKeys.of(chatId, messageId))
}
