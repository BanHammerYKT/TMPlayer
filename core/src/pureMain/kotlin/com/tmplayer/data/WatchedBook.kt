package com.tmplayer.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The Watched list over any [KeyValueStore], with the keys and the line format WatchedStore keeps
 * in `watched.preferences_pb` (`w_<chat>_<message>`, WatchedRecord's encoding), so the browser
 * build and the apps read each other's lists, a backup included.
 *
 * WatchedStore on the JVM is the same rules over DataStore directly; the two are held to the
 * same answers by WatchedBookTest.
 */
class WatchedBook(
    private val kv: KeyValueStore,
    /** Told the key of each video the player saw through to its end. */
    private val onFinished: (String) -> Unit = {},
) {

    /** Watch later, in the same file under its own prefix. */
    val watchLater: WatchLaterBook = WatchLaterBook(kv)

    private fun <T> read(transform: (Map<String, Any>) -> T): Flow<T> = kv.data.map(transform).distinctUntilChanged()

    /** Every watched video keyed by [ProgressKeys.of], for the ticks on a grid. */
    val watched: Flow<Map<String, WatchedRecord>> = read { entries -> decodeAll(entries).associateBy { it.key } }

    /** The "Watched" list, most recently finished first, without the chats the kids lock hides. */
    val history: Flow<List<WatchedRecord>> = read { entries -> decodeAll(entries).sortedByDescending { it.watchedAt } }
        .combine(ChatGate.allowed) { records, allowed -> KidsLock.filter(records, allowed) { it.chatId } }

    suspend fun isWatched(chatId: Long, messageId: Long): Boolean = kv.snapshot()[keyOf(chatId, messageId)] != null

    /**
     * Records [record] as watched; a manual mark is never downgraded to an automatic one. The video
     * and its [copies] leave Watch later in the same edit.
     */
    suspend fun markWatched(record: WatchedRecord, copies: List<MediaItem> = emptyList()) {
        kv.edit { entries ->
            val key = keyOf(record.chatId, record.messageId)
            val before = WatchedRecord.decode(record.key, entries[key] as? String)
            val kept = if (before?.manual == true && !record.manual) record.copy(manual = true) else record
            entries[key] = kept.encode()
            entries.remove(WatchLaterBook.keyOf(record.chatId, record.messageId))
            for (copy in copies) entries.remove(WatchLaterBook.keyOf(copy.chatId, copy.messageId))
            evictOldest(entries)
        }
        if (!record.manual) onFinished(record.key)
    }

    /** Takes the mark off this video and every one of [copies]. */
    suspend fun markUnwatched(chatId: Long, messageId: Long, copies: List<MediaItem> = emptyList()) {
        kv.edit { entries ->
            entries.remove(keyOf(chatId, messageId))
            for (copy in copies) entries.remove(keyOf(copy.chatId, copy.messageId))
        }
    }

    /** Empties the list; Watch later stays. */
    suspend fun clear() {
        kv.edit { entries -> entries.keys.filter { it.startsWith(PREFIX) }.forEach(entries::remove) }
    }

    private fun decodeAll(entries: Map<String, Any>): List<WatchedRecord> = buildList {
        for ((name, value) in entries) {
            if (!name.startsWith(PREFIX)) continue
            WatchedRecord.decode(name.removePrefix(PREFIX), value as? String)?.let(::add)
        }
    }

    private fun evictOldest(entries: MutableMap<String, Any>) {
        val all = decodeAll(entries)
        if (all.size <= MAX_WATCHED) return
        all.sortedBy { it.watchedAt }.take(all.size - MAX_WATCHED).forEach { entries.remove(keyOf(it.chatId, it.messageId)) }
    }

    companion object {
        /** As WatchedStore.MAX_WATCHED. */
        const val MAX_WATCHED = 5_000
        private const val PREFIX = "w_"

        fun keyOf(chatId: Long, messageId: Long): String = PREFIX + ProgressKeys.of(chatId, messageId)
    }
}

/** Watch later over any [KeyValueStore], as WatchLaterStore keeps it (`l_<chat>_<message>`). */
class WatchLaterBook(private val kv: KeyValueStore) {

    private fun <T> read(transform: (Map<String, Any>) -> T): Flow<T> = kv.data.map(transform).distinctUntilChanged()

    /** The list, most recently added first, without the chats the kids lock hides. */
    val items: Flow<List<WatchLaterRecord>> = read { entries -> decodeAll(entries).sortedByDescending { it.addedAt } }
        .combine(ChatGate.allowed) { records, allowed -> KidsLock.filter(records, allowed) { it.chatId } }

    /** Every saved video's progress key. */
    val keys: Flow<Set<String>> = read { entries ->
        entries.keys.mapNotNullTo(HashSet()) { it.takeIf { name -> name.startsWith(PREFIX) }?.removePrefix(PREFIX) }
    }

    suspend fun contains(chatId: Long, messageId: Long): Boolean = kv.snapshot()[keyOf(chatId, messageId)] != null

    /** Adds [record], or moves it to the top with the new time. */
    suspend fun add(record: WatchLaterRecord) {
        kv.edit { entries ->
            entries[keyOf(record.chatId, record.messageId)] = record.encode()
            evictOldest(entries)
        }
    }

    suspend fun remove(chatId: Long, messageId: Long) {
        kv.edit { it.remove(keyOf(chatId, messageId)) }
    }

    suspend fun clear() {
        kv.edit { entries -> entries.keys.filter { it.startsWith(PREFIX) }.forEach(entries::remove) }
    }

    private fun decodeAll(entries: Map<String, Any>): List<WatchLaterRecord> = buildList {
        for ((name, value) in entries) {
            if (!name.startsWith(PREFIX)) continue
            WatchLaterRecord.decode(name.removePrefix(PREFIX), value as? String)?.let(::add)
        }
    }

    private fun evictOldest(entries: MutableMap<String, Any>) {
        val all = decodeAll(entries)
        if (all.size <= MAX_WATCH_LATER) return
        all.sortedBy { it.addedAt }.take(all.size - MAX_WATCH_LATER).forEach { entries.remove(keyOf(it.chatId, it.messageId)) }
    }

    companion object {
        const val MAX_WATCH_LATER = 1_000
        private const val PREFIX = "l_"

        fun keyOf(chatId: Long, messageId: Long): String = PREFIX + ProgressKeys.of(chatId, messageId)
    }
}
