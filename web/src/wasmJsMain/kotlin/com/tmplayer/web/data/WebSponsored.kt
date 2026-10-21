package com.tmplayer.web.data

import com.tmplayer.i18n.L
import com.tmplayer.web.td.WebTd
import com.tmplayer.web.td.at
import com.tmplayer.web.td.bool
import com.tmplayer.web.td.int
import com.tmplayer.web.td.long
import com.tmplayer.web.td.obj
import com.tmplayer.web.td.objects
import com.tmplayer.web.td.putLong
import com.tmplayer.web.td.str
import com.tmplayer.web.td.type
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** A sponsored message Telegram shows in a channel, as the apps' SponsoredItem. */
data class WebSponsored(
    val chatId: Long,
    val messageId: Long,
    val label: String,
    val title: String,
    val text: String,
    val buttonText: String,
    val sponsorInfo: String,
    val additionalInfo: String,
    val url: String,
    val thumbnailFileId: Int,
    val miniThumbnail: String,
)

/**
 * Telegram's sponsored messages in channels, kept shown as its terms ask (CLAUDE.md, "Telegram's
 * terms"): loaded with the channel, placed among the videos the way Telegram spaces them, counted
 * as viewed when seen, and a press goes through Telegram before the link opens. The same calls as
 * the apps' SponsoredMessageRepository.
 */
class WebSponsoredMessages(private val td: WebTd) {

    data class Batch(val items: List<WebSponsored>, val between: Int)

    private val viewed = HashSet<String>()

    suspend fun load(chatId: Long): Batch {
        val r = td.tryCall("getChatSponsoredMessages") { putLong("chat_id", chatId) } ?: return Batch(emptyList(), 0)
        return Batch(r.objects("messages").map { map(chatId, it) }, r.int("messages_between").coerceAtLeast(0))
    }

    private fun map(chatId: Long, m: JsonObject): WebSponsored {
        val content = m.obj("content")
        val text = when (content?.type) {
            "messageText" -> content.at("text")?.str("text")
            else -> content?.at("caption")?.str("text")
        }.orEmpty()
        val media = content?.obj("video") ?: content?.obj("animation")
        val thumbId = media?.at("thumbnail", "file")?.int("id")
            ?: content?.at("photo")?.objects("sizes")?.maxByOrNull { it.int("width") * it.int("height") }?.at("photo")?.int("id")
            ?: 0
        val mini = media?.obj("minithumbnail")?.str("data") ?: content?.at("photo", "minithumbnail")?.str("data").orEmpty()
        return WebSponsored(
            chatId = chatId,
            messageId = m.long("message_id"),
            label = if (m.bool("is_recommended")) L.sponsoredRecommended else L.sponsoredLabel,
            title = m.str("title"),
            text = text,
            buttonText = m.str("button_text"),
            sponsorInfo = m.obj("sponsor")?.str("info").orEmpty(),
            additionalInfo = m.str("additional_info"),
            url = m.obj("sponsor")?.str("url").orEmpty(),
            thumbnailFileId = thumbId,
            miniThumbnail = mini,
        )
    }

    /** Counts the message as seen, once, which is how Telegram counts its impressions. */
    suspend fun markViewed(item: WebSponsored) {
        if (!viewed.add("${item.chatId}:${item.messageId}")) return
        td.tryCall("viewMessages") {
            putLong("chat_id", item.chatId)
            putJsonArray("message_ids") { add(item.messageId) }
            putJsonObject("source") { put("@type", "messageSourceChatHistory") }
            put("force_read", false)
        }
    }

    suspend fun click(item: WebSponsored, media: Boolean) {
        td.tryCall("clickChatSponsoredMessage") {
            putLong("chat_id", item.chatId)
            putLong("message_id", item.messageId)
            put("is_media_click", media)
            put("from_fullscreen", false)
        }
    }
}
