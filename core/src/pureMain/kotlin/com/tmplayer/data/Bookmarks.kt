package com.tmplayer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlin.math.abs

/**
 * A moment in one video the viewer wants to come back to, with an optional short note: the slide
 * a lecture turns to the answer, the scene a friend should see.
 *
 * [id] is the time it was made, which is unique enough within one video and keeps the order a
 * viewer made them in should two ever share a position.
 */
data class Bookmark(
    val chatId: Long,
    val messageId: Long,
    val id: Long,
    val positionMs: Long,
    val note: String,
) {
    val key: String get() = ProgressKeys.of(chatId, messageId)
}

/** How bookmarks are kept: one line per video, `b_<chat>_<message>`, as BookmarkStore writes them. */
object BookmarkCodec {
    const val MAX_PER_VIDEO = 200
    const val MAX_NOTE = 120
    const val SAME_MOMENT_MS = 1_000L
    const val PREFIX = "b_"

    /** Between bookmarks, and between a bookmark's fields: notes can hold anything else. */
    private const val RECORD = '\u001E'
    private const val FIELD = '\u001F'

    /** A note as stored: one line, trimmed, the separators out and cut to [MAX_NOTE]. */
    fun cleanNote(note: String): String = note
        .map { if (it == RECORD || it == FIELD || it.isISOControl()) ' ' else it }
        .joinToString("")
        .replace(SPACES, " ")
        .trim()
        .take(MAX_NOTE)
        .trim()

    private val SPACES = Regex("\\s+")

    fun encode(list: List<Bookmark>): String = list.joinToString(RECORD.toString()) {
        listOf(it.id.toString(), it.positionMs.toString(), it.note).joinToString(FIELD.toString())
    }

    /** Rebuilds a video's list, earliest first, skipping any record this build cannot read. */
    fun decode(chatId: Long, messageId: Long, encoded: String?): List<Bookmark> {
        if (encoded.isNullOrEmpty()) return emptyList()
        return encoded.split(RECORD).mapNotNull { record ->
            val parts = record.split(FIELD)
            // A later build may append fields; the first three still read the same.
            if (parts.size < 3) return@mapNotNull null
            Bookmark(
                chatId = chatId,
                messageId = messageId,
                id = parts[0].toLongOrNull() ?: return@mapNotNull null,
                positionMs = parts[1].toLongOrNull() ?: return@mapNotNull null,
                note = parts[2],
            )
        }.sortedWith(compareBy({ it.positionMs }, { it.id }))
    }

    fun keyOf(chatId: Long, messageId: Long): String = PREFIX + ProgressKeys.of(chatId, messageId)
}

/**
 * The bookmarks over any [KeyValueStore], with BookmarkStore's keys and rules, for the browser
 * build: [add] keeps one bookmark per moment and at most [BookmarkCodec.MAX_PER_VIDEO] a video.
 */
class BookmarkBook(private val kv: KeyValueStore) {

    fun bookmarks(chatId: Long, messageId: Long): Flow<List<Bookmark>> = kv.data
        .map { e -> BookmarkCodec.decode(chatId, messageId, e[BookmarkCodec.keyOf(chatId, messageId)] as? String) }
        .distinctUntilChanged()

    suspend fun bookmarksNow(chatId: Long, messageId: Long): List<Bookmark> = bookmarks(chatId, messageId).first()

    suspend fun add(chatId: Long, messageId: Long, positionMs: Long, note: String = "", now: Long = com.tmplayer.platform.nowMillis()): Bookmark {
        val clean = BookmarkCodec.cleanNote(note)
        var saved: Bookmark? = null
        kv.edit { e ->
            val key = BookmarkCodec.keyOf(chatId, messageId)
            val list = BookmarkCodec.decode(chatId, messageId, e[key] as? String).toMutableList()
            val near = list.indexOfFirst { abs(it.positionMs - positionMs) < BookmarkCodec.SAME_MOMENT_MS }
            if (near >= 0) {
                if (clean.isNotEmpty()) list[near] = list[near].copy(note = clean)
                saved = list[near]
            } else {
                val id = maxOf(now, (list.maxOfOrNull { it.id } ?: 0L) + 1)
                val made = Bookmark(chatId, messageId, id, positionMs.coerceAtLeast(0L), clean)
                list += made
                saved = made
                if (list.size > BookmarkCodec.MAX_PER_VIDEO) list.remove(list.minBy { it.id })
            }
            write(e, chatId, messageId, list)
        }
        return saved!!
    }

    suspend fun rename(chatId: Long, messageId: Long, id: Long, note: String) {
        val clean = BookmarkCodec.cleanNote(note)
        kv.edit { e ->
            val list = BookmarkCodec.decode(chatId, messageId, e[BookmarkCodec.keyOf(chatId, messageId)] as? String)
            write(e, chatId, messageId, list.map { if (it.id == id) it.copy(note = clean) else it })
        }
    }

    suspend fun delete(chatId: Long, messageId: Long, id: Long) {
        kv.edit { e ->
            val list = BookmarkCodec.decode(chatId, messageId, e[BookmarkCodec.keyOf(chatId, messageId)] as? String)
            write(e, chatId, messageId, list.filterNot { it.id == id })
        }
    }

    suspend fun clear() {
        kv.edit { e -> e.keys.filter { it.startsWith(BookmarkCodec.PREFIX) }.forEach(e::remove) }
    }

    private fun write(e: MutableMap<String, Any>, chatId: Long, messageId: Long, list: List<Bookmark>) {
        val key = BookmarkCodec.keyOf(chatId, messageId)
        if (list.isEmpty()) e.remove(key) else e[key] = BookmarkCodec.encode(list)
    }
}
