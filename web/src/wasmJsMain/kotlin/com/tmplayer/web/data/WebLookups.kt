package com.tmplayer.web.data

import com.tmplayer.web.td.WebTd
import com.tmplayer.web.td.long
import com.tmplayer.web.td.objects
import com.tmplayer.web.td.putLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Single videos by their key, and searches across every chat. */
class WebLookups(private val td: WebTd, private val chats: WebChats) {
    private val cache = HashMap<String, WebVideo?>()

    /** The video posted as [key] (`chatId:messageId`), or null when it is gone. */
    suspend fun video(key: String, untitled: String): WebVideo? {
        if (key in cache) return cache[key]
        val chatId = key.substringBefore(':').toLongOrNull() ?: return null
        val messageId = key.substringAfter(':').toLongOrNull() ?: return null
        val message = td.tryCall("getMessage") {
            putLong("chat_id", chatId)
            putLong("message_id", messageId)
        }
        val video = message?.let { WebVideoMapper.fromMessage(it, chats.chat(chatId)?.protectedContent ?: false, untitled) }
        cache[key] = video
        return video
    }

    /**
     * Videos whose name or caption matches [query] in every chat, newest first: Telegram's own
     * search with the video filter, then the document one, merged.
     */
    suspend fun search(query: String, untitled: String, limit: Int = 50): List<WebVideo> {
        if (query.isBlank()) return emptyList()
        suspend fun find(filter: String): List<JsonObject> = td.tryCall("searchMessages") {
            putJsonObject("chat_list") { put("@type", "chatListMain") }
            put("query", query.trim())
            put("offset", "")
            put("limit", limit)
            putJsonObject("filter") { put("@type", filter) }
            put("min_date", 0)
            put("max_date", 0)
        }?.objects("messages").orEmpty()
        val messages = find("searchMessagesFilterVideo") + find("searchMessagesFilterDocument")
        return messages
            .mapNotNull { m -> WebVideoMapper.fromMessage(m, chats.chat(m.long("chat_id"))?.protectedContent ?: false, untitled) }
            .distinctBy { it.key }
            .sortedByDescending { it.date }
    }
}
