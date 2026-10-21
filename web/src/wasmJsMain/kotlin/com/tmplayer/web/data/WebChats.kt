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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Which of Telegram's chat lists: the main one, the archive, or a folder. */
sealed interface ChatListKey {
    data object Main : ChatListKey
    data object Archive : ChatListKey
    data class Folder(val id: Int) : ChatListKey
}

enum class ChatKind { Person, Bot, Group, Channel, Saved }

/** One chat as the lists draw it. */
data class WebChat(
    val id: Long,
    val title: String,
    val kind: ChatKind,
    val photoFileId: Int,
    val photoMini: String,
    val unread: Int,
    val muted: Boolean,
    /** Order per list (TDLib's int64 order, 0 when not in it) and whether it is pinned there. */
    val orders: Map<ChatListKey, Long>,
    val pinned: Set<ChatListKey>,
    val protectedContent: Boolean,
    val lastDate: Int,
)

data class WebFolder(val id: Int, val title: String)

/**
 * The account's chats, kept up to date from TDLib's updates the way Telegram's own clients do:
 * every chat arrives once as updateNewChat, then each change as its own update, and a list is the
 * chats with a position in it, sorted by TDLib's order.
 */
class WebChats(private val td: WebTd, private val scope: CoroutineScope, private val files: WebFiles) {
    private val chats = HashMap<Long, WebChat>()
    private val _version = MutableStateFlow(0)

    /** Bumped on every change, for lists to recompute from. */
    val version: StateFlow<Int> = _version.asStateFlow()

    private val _folders = MutableStateFlow<List<WebFolder>>(emptyList())
    val folders: StateFlow<List<WebFolder>> = _folders.asStateFlow()

    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var myId = 0L
    private var bump: Job? = null

    init {
        td.onUpdate(::onUpdate)
    }

    fun onSignedIn() {
        scope.launch {
            td.tryCall("getMe")?.let { myId = it.long("id") }
            load(ChatListKey.Main)
        }
    }

    fun clear() {
        chats.clear()
        _folders.value = emptyList()
        _loading.value = true
        changed()
    }

    /** Asks TDLib for the chats of [list] until it has them all (a 404 says there are no more). */
    suspend fun load(list: ChatListKey) {
        if (list == ChatListKey.Main) _loading.value = true
        repeat(20) {
            val more = td.tryCall("loadChats") {
                putJsonObject("chat_list") { listJson(list) }
                put("limit", 100)
            }
            if (more == null) {
                if (list == ChatListKey.Main) _loading.value = false
                return
            }
        }
        if (list == ChatListKey.Main) _loading.value = false
    }

    private fun kotlinx.serialization.json.JsonObjectBuilder.listJson(list: ChatListKey) {
        when (list) {
            ChatListKey.Main -> put("@type", "chatListMain")
            ChatListKey.Archive -> put("@type", "chatListArchive")
            is ChatListKey.Folder -> {
                put("@type", "chatListFolder")
                put("chat_folder_id", list.id)
            }
        }
    }

    fun chat(id: Long): WebChat? = chats[id]

    /** The chats in [list], pinned first, then by TDLib's order. */
    fun list(list: ChatListKey): List<WebChat> =
        chats.values.filter { (it.orders[list] ?: 0L) != 0L }
            .sortedWith(compareByDescending<WebChat> { list in it.pinned }.thenByDescending { it.orders[list] ?: 0L })

    private fun keyOf(o: JsonObject?): ChatListKey? = when (o?.type) {
        "chatListMain" -> ChatListKey.Main
        "chatListArchive" -> ChatListKey.Archive
        "chatListFolder" -> ChatListKey.Folder(o.int("chat_folder_id"))
        else -> null
    }

    private fun changed() {
        // Updates come in bursts of hundreds at sign in; one recomposition for the lot.
        if (bump?.isActive == true) return
        bump = scope.launch {
            delay(50)
            _version.value++
        }
    }

    private fun applyPositions(chat: WebChat, positions: List<JsonObject>): WebChat {
        val orders = chat.orders.toMutableMap()
        val pinned = chat.pinned.toMutableSet()
        for (p in positions) {
            val key = keyOf(p.obj("list")) ?: continue
            val order = p.long("order")
            if (order == 0L) orders.remove(key) else orders[key] = order
            if (p.bool("is_pinned")) pinned += key else pinned -= key
        }
        return chat.copy(orders = orders, pinned = pinned)
    }

    private fun kindOf(o: JsonObject, id: Long): ChatKind {
        val t = o.obj("type")
        return when (t?.type) {
            "chatTypePrivate", "chatTypeSecret" -> if (id == myId || t.long("user_id") == myId && myId != 0L) ChatKind.Saved else ChatKind.Person
            "chatTypeBasicGroup" -> ChatKind.Group
            "chatTypeSupergroup" -> if (t.bool("is_channel")) ChatKind.Channel else ChatKind.Group
            else -> ChatKind.Person
        }
    }

    private fun onUpdate(u: JsonObject) {
        when (u.type) {
            "updateNewChat" -> {
                val c = u.obj("chat") ?: return
                val id = c.long("id")
                val base = WebChat(
                    id = id,
                    title = c.str("title"),
                    kind = kindOf(c, id),
                    photoFileId = c.at("photo", "small")?.int("id") ?: 0,
                    photoMini = c.at("photo", "minithumbnail")?.str("data").orEmpty(),
                    unread = c.int("unread_count"),
                    muted = (c.at("notification_settings")?.int("mute_for") ?: 0) > 0,
                    orders = emptyMap(),
                    pinned = emptySet(),
                    protectedContent = c.bool("has_protected_content"),
                    lastDate = c.at("last_message")?.int("date") ?: 0,
                )
                chats[id] = applyPositions(base, c.objects("positions"))
                changed()
            }
            "updateChatTitle" -> edit(u) { copy(title = u.str("title")) }
            "updateChatPhoto" -> edit(u) {
                copy(photoFileId = u.at("photo", "small")?.int("id") ?: 0, photoMini = u.at("photo", "minithumbnail")?.str("data").orEmpty())
            }
            "updateChatPosition" -> edit(u) { applyPositions(this, listOfNotNull(u.obj("position"))) }
            "updateChatLastMessage" -> edit(u) {
                applyPositions(this, u.objects("positions")).copy(lastDate = u.at("last_message")?.int("date") ?: lastDate)
            }
            "updateChatReadInbox" -> edit(u) { copy(unread = u.int("unread_count")) }
            "updateChatNotificationSettings" -> edit(u) { copy(muted = (u.at("notification_settings")?.int("mute_for") ?: 0) > 0) }
            "updateChatHasProtectedContent" -> edit(u) { copy(protectedContent = u.bool("has_protected_content")) }
            "updateChatFolders" -> {
                _folders.value = u.objects("chat_folders").map { f ->
                    // TDLib 1.8.40 and on give the name as formatted text; older ones as a string.
                    val title = f.at("name", "text")?.str("text")?.takeIf { it.isNotEmpty() } ?: f.str("title")
                    WebFolder(f.int("id"), title)
                }
            }
        }
    }

    private inline fun edit(u: JsonObject, change: WebChat.() -> WebChat) {
        val id = u.long("chat_id")
        val chat = chats[id] ?: return
        chats[id] = chat.change()
        changed()
    }
}
