package com.tmplayer.web.data

import com.tmplayer.web.td.WebTd
import com.tmplayer.web.td.at
import com.tmplayer.web.td.bool
import com.tmplayer.web.td.int
import com.tmplayer.web.td.long
import com.tmplayer.web.td.obj
import com.tmplayer.web.td.objects
import com.tmplayer.web.td.str
import com.tmplayer.web.td.type
import com.tmplayer.web.td.putLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * One playable thing in a chat, read off tdweb's JSON the way the apps' MediaMapper reads a
 * Message. Becomes :core's MediaItem once that is in the pure half, so the apps' grouping into
 * shows, copies and pieces applies here too.
 */
data class WebVideo(
    val chatId: Long,
    val messageId: Long,
    val fileId: Int,
    val sizeBytes: Long,
    val mimeType: String,
    val fileName: String,
    val title: String,
    val caption: String,
    val durationSec: Int,
    val width: Int,
    val height: Int,
    val thumbnailFileId: Int,
    val miniThumbnail: String,
    val date: Int,
    val canBeSaved: Boolean,
    val downloadedBytes: Long,
) {
    val key: String get() = "$chatId:$messageId"
}

object WebVideoMapper {
    private val VIDEO_EXTENSIONS = setOf(
        "mkv", "mp4", "m4v", "avi", "mov", "webm", "ts", "m2ts", "mts",
        "flv", "wmv", "mpg", "mpeg", "3gp", "ogv", "divx", "vob", "asf", "rmvb",
    )

    fun looksLikeVideo(name: String, mime: String): Boolean {
        if (mime.startsWith("video/")) return true
        val ext = name.substringAfterLast('.', "").lowercase()
        if (ext in VIDEO_EXTENSIONS) return true
        // A piece of a cut video: "Name.mkv.001".
        val inner = name.substringBeforeLast('.', "").substringAfterLast('.', "").lowercase()
        return ext.all { it.isDigit() } && ext.length in 2..4 && inner in VIDEO_EXTENSIONS
    }

    private fun size(file: JsonObject?): Long = file?.let { maxOf(it.long("size"), it.long("expected_size")) } ?: 0L

    private fun titleOf(name: String, caption: String, fallback: String): String {
        val firstLine = caption.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return when {
            name.isNotBlank() -> name.substringBeforeLast('.').replace('_', ' ').replace('.', ' ').trim().ifEmpty { name }
            firstLine.isNotEmpty() -> firstLine.take(120)
            else -> fallback
        }
    }

    /** The video in [message], or null when there is none, it is withheld, or it self-destructs. */
    fun fromMessage(message: JsonObject, chatProtected: Boolean, untitled: String): WebVideo? {
        if (message.obj("scheduling_state") != null || message.obj("sending_state") != null) return null
        if (message.obj("self_destruct_type") != null) return null
        // Withheld by Telegram on this platform or in this country: Telegram's restrictions stay on.
        val restriction = message.obj("restriction_info")?.str("restriction_reason") ?: message.str("restriction_reason")
        if (restriction.isNotEmpty()) return null
        val content = message.obj("content") ?: return null
        val caption = content.at("caption")?.str("text").orEmpty().trim()
        val canSave = message.bool("can_be_saved") && !chatProtected
        fun build(file: JsonObject?, name: String, mime: String, duration: Int, w: Int, h: Int, thumb: JsonObject?, mini: JsonObject?): WebVideo? {
            val id = file?.int("id") ?: 0
            if (id == 0) return null
            return WebVideo(
                chatId = message.long("chat_id"),
                messageId = message.long("id"),
                fileId = id,
                sizeBytes = size(file),
                mimeType = mime,
                fileName = name,
                title = titleOf(name, caption, untitled),
                caption = caption,
                durationSec = duration,
                width = w,
                height = h,
                thumbnailFileId = thumb?.obj("file")?.int("id") ?: 0,
                miniThumbnail = mini?.str("data").orEmpty(),
                date = message.int("date"),
                canBeSaved = canSave,
                downloadedBytes = file?.obj("local")?.long("downloaded_size") ?: 0L,
            )
        }
        return when (content.type) {
            "messageVideo" -> content.obj("video")?.let { v ->
                build(v.obj("video"), v.str("file_name"), v.str("mime_type"), v.int("duration"), v.int("width"), v.int("height"), v.obj("thumbnail"), v.obj("minithumbnail"))
            }
            "messageAnimation" -> content.obj("animation")?.let { v ->
                build(v.obj("animation"), v.str("file_name"), v.str("mime_type"), v.int("duration"), v.int("width"), v.int("height"), v.obj("thumbnail"), v.obj("minithumbnail"))
            }
            "messageDocument" -> content.obj("document")?.let { d ->
                val name = d.str("file_name")
                val mime = d.str("mime_type")
                if (!looksLikeVideo(name, mime)) null
                else build(d.obj("document"), name, mime, 0, 0, 0, d.obj("thumbnail"), d.obj("minithumbnail"))
            }
            "messageVideoNote" -> content.obj("video_note")?.let { v ->
                build(v.obj("video"), "", "video/mp4", v.int("duration"), v.int("length"), v.int("length"), v.obj("thumbnail"), v.obj("minithumbnail"))
            }
            else -> null
        }
    }
}

/** Reading a chat's videos: Telegram's own video and document filters, merged newest first. */
class WebVideos(private val td: WebTd) {

    /** A search inside one chat: Telegram matches the caption as well as the file name. */
    suspend fun searchIn(chatId: Long, query: String, protectedChat: Boolean, untitled: String, limit: Int = 100): List<WebVideo> {
        suspend fun find(filter: String): List<JsonObject> = td.tryCall("searchChatMessages") {
            putLong("chat_id", chatId)
            put("query", query.trim())
            putLong("from_message_id", 0)
            put("offset", 0)
            put("limit", limit)
            putJsonObject("filter") { put("@type", filter) }
        }?.objects("messages").orEmpty()
        return (find("searchMessagesFilterVideo") + find("searchMessagesFilterDocument"))
            .mapNotNull { WebVideoMapper.fromMessage(it, protectedChat, untitled) }
            .distinctBy { it.key }
            .sortedByDescending { it.messageId }
    }


    /**
     * One page from [fromVideo] and [fromDocument] (each a message id, 0 for the newest; -1 when that
     * filter has nothing more), merged by date.
     */
    suspend fun page(chatId: Long, fromVideo: Long, fromDocument: Long, protectedChat: Boolean, untitled: String, limit: Int = 60): MergedPage {
        suspend fun search(filter: String, from: Long): JsonObject? {
            if (from < 0) return null
            return td.tryCall("searchChatMessages") {
                putLong("chat_id", chatId)
                put("query", "")
                putLong("from_message_id", from)
                put("offset", 0)
                put("limit", limit)
                putJsonObject("filter") { put("@type", filter) }
            }
        }
        val videos = search("searchMessagesFilterVideo", fromVideo)
        val documents = search("searchMessagesFilterDocument", fromDocument)
        val items = (videos?.objects("messages").orEmpty() + documents?.objects("messages").orEmpty())
            .mapNotNull { WebVideoMapper.fromMessage(it, protectedChat, untitled) }
            .distinctBy { it.key }
            .sortedByDescending { it.messageId }
        fun next(found: JsonObject?, from: Long): Long = when {
            from < 0 || found == null -> -1
            else -> found.long("next_from_message_id").takeIf { it != 0L } ?: -1
        }
        return MergedPage(
            items = items,
            nextVideo = next(videos, fromVideo),
            nextDocument = next(documents, fromDocument),
            total = (videos?.int("total_count") ?: 0) + (documents?.int("total_count") ?: 0),
        )
    }
}

data class MergedPage(val items: List<WebVideo>, val nextVideo: Long, val nextDocument: Long, val total: Int) {
    val done: Boolean get() = nextVideo < 0 && nextDocument < 0
}
