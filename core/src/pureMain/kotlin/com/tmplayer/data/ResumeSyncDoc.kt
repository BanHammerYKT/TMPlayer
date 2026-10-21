package com.tmplayer.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * What resume across devices keeps in the viewer's own Saved Messages: one text message, found by
 * [TAG] and edited in place, holding the Continue watching places of every device that has the
 * switch on, as a small JSON object under a sentence that says what the message is.
 *
 * Pure: no Telegram, no settings. [ResumeSync] reads and writes it.
 *
 * Merging is "the newest wins" per video. A video finished or removed on one device leaves a
 * [Tomb] for [TOMB_DAYS], so another device does not bring the place back from its older copy.
 */
object ResumeSyncDoc {

    /** The hashtag the message starts with, which is how it is found again. */
    const val TAG = "#TMPlayerSync"

    /** Telegram's text limit is 4096; the JSON is cut to fit under this, oldest places first. */
    const val MAX_CHARS = 3900

    /** At most this many places travel, the newest. Continue watching shows far fewer. */
    const val MAX_ENTRIES = 40

    /** How long a removal is remembered. */
    const val TOMB_DAYS = 30L

    data class Entry(
        val chatId: Long,
        val messageId: Long,
        val positionMs: Long,
        val durationMs: Long,
        val updatedAt: Long,
        val title: String,
        val chatTitle: String = "",
        val fileName: String? = null,
        val sizeBytes: Long = 0,
        val durationSec: Int = 0,
    ) {
        val key: String get() = "${chatId}_$messageId"
    }

    data class Tomb(val chatId: Long, val messageId: Long, val at: Long) {
        val key: String get() = "${chatId}_$messageId"
    }

    data class Doc(val entries: List<Entry> = emptyList(), val tombs: List<Tomb> = emptyList())

    fun entryOf(record: ResumeRecord): Entry = Entry(
        chatId = record.chatId,
        messageId = record.messageId,
        positionMs = record.positionMs,
        durationMs = record.durationMs,
        updatedAt = record.updatedAt,
        title = record.title.take(TITLE_MAX),
        chatTitle = record.chatTitle.take(TITLE_MAX),
        fileName = record.fileName?.take(TITLE_MAX),
        sizeBytes = record.sizeBytes,
        durationSec = record.durationSec,
    )

    /**
     * [a] and [b] as one: for each video the newest place, a removal newer than a place taking it
     * away, removals older than [TOMB_DAYS] forgotten, and at most [MAX_ENTRIES] places kept.
     */
    fun merge(a: Doc, b: Doc, nowMs: Long): Doc {
        val oldest = nowMs - TOMB_DAYS * DAY_MS
        val tombs = (a.tombs + b.tombs)
            .filter { it.at >= oldest }
            .groupBy { it.key }
            .map { (_, all) -> all.maxBy { it.at } }
        val tombAt = tombs.associate { it.key to it.at }
        val entries = (a.entries + b.entries)
            .groupBy { it.key }
            .map { (_, all) -> all.maxBy { it.updatedAt } }
            .filter { entry -> (tombAt[entry.key] ?: Long.MIN_VALUE) < entry.updatedAt }
            .sortedByDescending { it.updatedAt }
            .take(MAX_ENTRIES)
        // A removal with a newer place over it has done its job.
        val live = entries.associate { it.key to it.updatedAt }
        val keptTombs = tombs.filter { (live[it.key] ?: Long.MIN_VALUE) < it.at }.sortedByDescending { it.at }
        return Doc(entries, keptTombs)
    }

    /** The message's whole text: the tag, [intro], and the JSON, cut to [MAX_CHARS]. */
    fun encode(doc: Doc, intro: String): String {
        var entries = doc.entries.sortedByDescending { it.updatedAt }
        var tombs = doc.tombs.sortedByDescending { it.at }
        while (true) {
            val text = "$TAG\n$intro\n\n" + json(Doc(entries, tombs))
            if (text.length <= MAX_CHARS || (entries.isEmpty() && tombs.isEmpty())) return text
            if (tombs.size > entries.size / 2 && tombs.isNotEmpty()) tombs = tombs.dropLast(1) else entries = entries.dropLast(1)
        }
    }

    /** The document in a message's [text], or null when it is not one of ours. */
    fun decode(text: String?): Doc? {
        if (text == null || !text.trimStart().startsWith(TAG)) return null
        val start = text.indexOf('{')
        if (start < 0) return Doc()
        return runCatching {
            val root = Json.parseToJsonElement(text.substring(start)).jsonObject
            if ((root.int("v") ?: 1) > VERSION) return null
            val entries = root["e"] as? JsonArray ?: JsonArray(emptyList())
            val tombs = root["x"] as? JsonArray ?: JsonArray(emptyList())
            Doc(
                entries = entries.mapNotNull { element ->
                    val o = element as? JsonObject ?: return@mapNotNull null
                    Entry(
                        chatId = o.long("c") ?: 0L,
                        messageId = o.long("m") ?: 0L,
                        positionMs = o.long("p") ?: 0L,
                        durationMs = o.long("d") ?: 0L,
                        updatedAt = o.long("u") ?: 0L,
                        title = o.text("t"),
                        chatTitle = o.text("ct"),
                        fileName = o.text("f").ifBlank { null },
                        sizeBytes = o.long("s") ?: 0L,
                        durationSec = o.int("ds") ?: 0,
                    ).takeIf { it.chatId != 0L && it.messageId != 0L && it.positionMs > 0 }
                },
                tombs = tombs.mapNotNull { element ->
                    val o = element as? JsonObject ?: return@mapNotNull null
                    Tomb(o.long("c") ?: 0L, o.long("m") ?: 0L, o.long("a") ?: 0L).takeIf { it.chatId != 0L && it.messageId != 0L }
                },
            )
        }.getOrNull()
    }

    private fun JsonObject.primitive(name: String) = (this[name] as? JsonElement)?.let { runCatching { it.jsonPrimitive }.getOrNull() }
    private fun JsonObject.long(name: String): Long? = primitive(name)?.let { it.longOrNull ?: it.content.toDoubleOrNull()?.toLong() }
    private fun JsonObject.int(name: String): Int? = primitive(name)?.let { it.intOrNull ?: it.content.toDoubleOrNull()?.toInt() }
    private fun JsonObject.text(name: String): String = primitive(name)?.takeIf { it.isString }?.content.orEmpty()

    private fun json(doc: Doc): String = buildJsonObject {
        put("v", VERSION)
        put("e", buildJsonArray {
            doc.entries.forEach { e ->
                add(buildJsonObject {
                    put("c", e.chatId)
                    put("m", e.messageId)
                    put("p", e.positionMs)
                    if (e.durationMs > 0) put("d", e.durationMs)
                    put("u", e.updatedAt)
                    put("t", e.title)
                    if (e.chatTitle.isNotBlank()) put("ct", e.chatTitle)
                    e.fileName?.takeIf { it.isNotBlank() && it != e.title }?.let { put("f", it) }
                    if (e.sizeBytes > 0) put("s", e.sizeBytes)
                    if (e.durationSec > 0) put("ds", e.durationSec)
                })
            }
        })
        if (doc.tombs.isNotEmpty()) {
            put("x", buildJsonArray {
                doc.tombs.forEach { t -> add(buildJsonObject { put("c", t.chatId); put("m", t.messageId); put("a", t.at) }) }
            })
        }
    }.toString()

    private const val VERSION = 1
    private const val TITLE_MAX = 60
    private const val DAY_MS = 24 * 60 * 60 * 1000L
}
