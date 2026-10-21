package com.tmplayer.data

import kotlinx.coroutines.flow.StateFlow

/**
 * What the screens need from Telegram, in the app's own models and nothing of TDLib's: signing in,
 * the chat list and its folders, a chat's videos page by page, files, sponsored messages, and the
 * Saved Messages text resume across devices keeps.
 *
 * Two implementations: [JvmTdGateway] on Android and the desktop, over tdl-coroutines and the
 * existing Td and ChatRepository; and the browser build's, over tdweb's JSON. Screens written
 * against this run on both.
 *
 * Calls that change the viewer's account return null on success and Telegram's message on
 * failure, the way Td's sign in calls already do.
 */
interface TdGateway {

    // ---- the connection and signing in --------------------------------------------------------

    /** What the sign in screen shows. */
    val auth: StateFlow<AuthState>

    /** True once [AuthState.Connecting] has lasted long enough to say why. */
    val signInStalled: StateFlow<Boolean>

    /** Whether requests are being answered. */
    val connected: StateFlow<Boolean>

    /** TDLib's own word for the line to Telegram. */
    val link: StateFlow<TelegramLink>

    /** The viewer's Telegram folders, in their order. */
    val folders: StateFlow<List<ChatFolderSummary>>

    suspend fun chooseSignInMethod(method: SignInMethod)
    suspend fun submitPhoneNumber(phoneNumber: String): String?
    suspend fun submitCode(code: String): String?
    suspend fun resendCode(): String?
    suspend fun submitEmailAddress(address: String): String?
    suspend fun submitEmailCode(code: String): String?
    suspend fun submitPassword(password: String): String?

    /** Back to the start of signing in, with [next] chosen already or the choice offered. */
    suspend fun restartSignIn(next: SignInMethod = SignInMethod.Undecided)

    /** Starts the connection again after [signInStalled]. */
    suspend fun retryConnecting()

    /** Leaves the phone number screen for the choice of method. */
    suspend fun cancelPhoneEntry()

    suspend fun logOut()

    /** The signed-in account's id, which is also its Saved Messages chat; 0 before signing in. */
    suspend fun myId(): Long

    // ---- chats ------------------------------------------------------------------------------

    /** Brings the chat list up to date with Telegram and returns it. */
    suspend fun syncChats(known: List<ChatSummary> = emptyList()): ChatSync

    /** The chat list as already known locally, with no network. */
    suspend fun cachedChats(known: List<ChatSummary> = emptyList()): List<ChatSummary>

    suspend fun summary(chatId: Long): ChatSummary?
    suspend fun chatInfo(chatId: Long): ChatInfo?
    suspend fun savedMessages(): ChatSummary?

    /** A public chat by `@username` or `t.me/name`, or null when there is none. */
    suspend fun findByUsername(text: String): ChatSummary?

    /** A group's forum topics, for a group with topics turned on; empty otherwise. */
    suspend fun forumTopics(chatId: Long, countVideos: Boolean = true): List<ForumTopicSummary>

    suspend fun setPinned(chat: ChatSummary, pinned: Boolean): String?
    suspend fun setMuted(chatId: Long, muted: Boolean): String?
    suspend fun setArchived(chatId: Long, archived: Boolean): String?
    suspend fun markRead(chatId: Long): String?

    // ---- videos -----------------------------------------------------------------------------

    /** One page of a chat's videos, from [cursors] on. */
    suspend fun mediaPage(
        chatId: Long,
        cursors: MediaCursors = MediaCursors(),
        query: String = "",
        pageSize: Int = 40,
        topicId: Int = 0,
    ): MediaPage

    /** The newest videos across the viewer's chats, for Home. */
    suspend fun recentMedia(limit: Int = 60): List<MediaItem>

    /** Search across every chat. */
    suspend fun searchAllChats(
        query: String,
        cursor: AllChatsCursor = AllChatsCursor(),
        limit: Int = AllChatsSearch.PAGE_SIZE,
        filters: SearchFilters = SearchFilters(),
    ): AllChatsPage

    /** One video by its message, or null when the message is gone or holds none. */
    suspend fun mediaItem(chatId: Long, messageId: Long): MediaItem?

    // ---- files ------------------------------------------------------------------------------

    /** Where a file stands on this device. */
    suspend fun fileState(fileId: Int): FileState

    /**
     * Asks for [limit] bytes of a file from [offset] (0 for the rest), at [priority] (1 to 32), and
     * with [synchronous] waits until they are there.
     */
    suspend fun download(fileId: Int, priority: Int = 16, offset: Long = 0, limit: Long = 0, synchronous: Boolean = false): FileState

    suspend fun cancelDownload(fileId: Int)

    /** A small file (a thumbnail, a chat photo) downloaded whole, as its bytes; null when it cannot be. */
    suspend fun smallFile(fileId: Int, priority: Int = 8): ByteArray?

    // ---- sponsored messages (shown in channels, as Telegram's terms require) ------------------

    suspend fun sponsoredMessages(chatId: Long): SponsoredBatch
    suspend fun viewSponsored(chatId: Long, messageId: Long)
    suspend fun clickSponsored(chatId: Long, messageId: Long, media: Boolean)
    suspend fun reportSponsored(chatId: Long, messageId: Long, optionId: ByteArray = byteArrayOf()): SponsoredReportOutcome

    // ---- Saved Messages text, for resume across devices ----------------------------------------

    /**
     * The newest text message in Saved Messages that starts with [tag] (or message [knownId] when it
     * still does), as its id and text; null when there is none.
     */
    suspend fun findSavedText(tag: String, knownId: Long = 0): SavedText?

    /** Writes [text] into message [id] in Saved Messages, or sends it when [id] is 0; the message's id, or null. */
    suspend fun writeSavedText(id: Long, text: String): Long?
}

/** Where one file stands: its size, how much of it is here from the start, and whether all of it is. */
data class FileState(
    val fileId: Int,
    val size: Long,
    /** Bytes on hand counting from 0, for streaming from the start. */
    val downloadedPrefix: Long,
    val complete: Boolean,
    val downloading: Boolean,
    /** Where it is on disk (the JVM) or in the browser's file system; "" when nowhere yet. */
    val localPath: String = "",
)

/** One message of Saved Messages' text. */
data class SavedText(val id: Long, val text: String)
