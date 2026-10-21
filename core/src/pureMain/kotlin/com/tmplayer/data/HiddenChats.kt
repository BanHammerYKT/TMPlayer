package com.tmplayer.data

import com.tmplayer.platform.Background
import kotlin.concurrent.Volatile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** A chat the viewer hid from TMPlayer's lists, with the name it had then, for Settings' list. */
data class HiddenChat(val id: Long, val title: String)

/** Where the hidden chats are kept: the apps' SettingsStore, or SettingsBook in the browser. */
interface HiddenChatsSource {
    val hiddenChats: Flow<List<HiddenChat>>
}

/**
 * The chats the viewer hid from a chat's menu: left out of Chats, Home (its rows, Movies and
 * Shows, and the videos of the chat in Continue watching and Watch later there) and the search
 * across chats, with their videos. Nothing changes in Telegram: the chat is still there, read
 * state and all, and Settings, Library, Hidden chats lists each one with Show. History keeps
 * what was watched in them, since that list is the viewer's own record.
 *
 * Stored as one text setting ([KEY]), a line per chat, so it travels in a backup like any other
 * choice. Process wide, as [ChatGate] is, so every list reads the same set without a store of its own.
 */
object HiddenChats {
    const val KEY = "hidden_chats"

    private val state = MutableStateFlow<Set<Long>>(emptySet())

    @Volatile
    private var following = false

    /** The hidden chats' ids, empty until the store is read and while none is hidden. */
    val ids: StateFlow<Set<Long>> = state.asStateFlow()

    /** Whether [chatId] is listed: true unless the viewer hid it. */
    fun shows(chatId: Long): Boolean = chatId !in state.value

    /** Keeps [ids] in step with [store] for the life of the process. */
    fun follow(store: HiddenChatsSource) {
        if (following) return
        following = true
        Background.scope.launch {
            store.hiddenChats.collect { chats -> state.value = chats.mapTo(HashSet()) { it.id } }
        }
    }

    /** Sets the ids directly. For tests, and for a process with no store to follow. */
    fun set(ids: Set<Long>) {
        state.value = ids
    }

    /** The stored text back to its chats, in the order they were hidden; lines that do not parse are skipped. */
    fun decode(raw: String?): List<HiddenChat> =
        raw.orEmpty().lineSequence().mapNotNull { line ->
            val tab = line.indexOf('\t')
            val id = (if (tab < 0) line else line.substring(0, tab)).trim().toLongOrNull() ?: return@mapNotNull null
            HiddenChat(id, if (tab < 0) "" else line.substring(tab + 1))
        }.distinctBy { it.id }.toList()

    fun encode(chats: List<HiddenChat>): String =
        chats.distinctBy { it.id }.joinToString("\n") { "${it.id}\t${it.title.replace(LINE_BREAKS, " ").trim()}" }

    /** [chats] with [chat] added at the end, or its name brought up to date if it is there already. */
    fun with(chats: List<HiddenChat>, chat: HiddenChat): List<HiddenChat> =
        if (chats.any { it.id == chat.id }) chats.map { if (it.id == chat.id) chat else it } else chats + chat

    /** [items] without the ones from hidden chats. */
    fun <T> filter(items: List<T>, hidden: Set<Long> = state.value, chatOf: (T) -> Long): List<T> =
        if (hidden.isEmpty()) items else items.filter { chatOf(it) !in hidden }

    private val LINE_BREAKS = Regex("[\\t\\r\\n]+")
}
