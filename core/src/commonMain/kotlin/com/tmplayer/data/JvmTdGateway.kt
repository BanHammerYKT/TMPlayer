package com.tmplayer.data

import dev.g000sha256.tdl.dto.FormattedText
import dev.g000sha256.tdl.dto.InputMessageText
import dev.g000sha256.tdl.dto.LinkPreviewOptions
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageSendOptions
import dev.g000sha256.tdl.dto.MessageText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * [TdGateway] on Android and the desktop: a thin layer over the process's [Td] and a
 * [ChatRepository] on its current client. Nothing here decides anything; the rules stay where
 * they were.
 */
class JvmTdGateway(
    private val repository: () -> ChatRepository = { ChatRepository(Td.client) },
    private val sponsored: () -> SponsoredMessageRepository = { SponsoredMessageRepository(Td.client) },
) : TdGateway {

    override val auth: StateFlow<AuthState> get() = Td.auth
    override val signInStalled: StateFlow<Boolean> get() = Td.signInStalled
    override val connected: StateFlow<Boolean> get() = Td.connected
    override val link: StateFlow<TelegramLink> get() = Td.link
    override val folders: StateFlow<List<ChatFolderSummary>> get() = Td.folders

    override suspend fun chooseSignInMethod(method: SignInMethod) = Td.chooseSignInMethod(method)
    override suspend fun submitPhoneNumber(phoneNumber: String): String? = Td.submitPhoneNumber(phoneNumber)
    override suspend fun submitCode(code: String): String? = Td.submitCode(code)
    override suspend fun resendCode(): String? = Td.resendCode()
    override suspend fun submitEmailAddress(address: String): String? = Td.submitEmailAddress(address)
    override suspend fun submitEmailCode(code: String): String? = Td.submitEmailCode(code)
    override suspend fun submitPassword(password: String): String? = Td.submitPassword(password)
    override suspend fun restartSignIn(next: SignInMethod) = Td.restartSignIn(next)
    override suspend fun retryConnecting() = Td.retryConnecting()
    override suspend fun cancelPhoneEntry() = Td.cancelPhoneEntry()
    override suspend fun logOut() = Td.logOut()
    override suspend fun myId(): Long = Td.myId()

    override suspend fun syncChats(known: List<ChatSummary>): ChatSync = repository().syncChats(known = known)
    override suspend fun cachedChats(known: List<ChatSummary>): List<ChatSummary> = repository().cachedChats(known = known)
    override suspend fun summary(chatId: Long): ChatSummary? = repository().summary(chatId)
    override suspend fun chatInfo(chatId: Long): ChatInfo? = repository().chatInfo(chatId)
    override suspend fun savedMessages(): ChatSummary? = repository().savedMessages()
    override suspend fun findByUsername(text: String): ChatSummary? = repository().findByUsername(text)
    override suspend fun forumTopics(chatId: Long, countVideos: Boolean): List<ForumTopicSummary> =
        repository().forumTopics(chatId, countVideos)

    override suspend fun setPinned(chat: ChatSummary, pinned: Boolean): String? = repository().setPinned(chat, pinned).errorMessage
    override suspend fun setMuted(chatId: Long, muted: Boolean): String? = repository().setMuted(chatId, muted).errorMessage
    override suspend fun setArchived(chatId: Long, archived: Boolean): String? = repository().setArchived(chatId, archived).errorMessage
    override suspend fun markRead(chatId: Long): String? = repository().markRead(chatId).errorMessage

    override suspend fun mediaPage(chatId: Long, cursors: MediaCursors, query: String, pageSize: Int, topicId: Int): MediaPage =
        repository().mediaPage(chatId, cursors, query, pageSize, topicId)

    override suspend fun recentMedia(limit: Int): List<MediaItem> = repository().recentMedia(limit)

    override suspend fun searchAllChats(query: String, cursor: AllChatsCursor, limit: Int, filters: SearchFilters): AllChatsPage =
        repository().searchAllChats(query, cursor, limit, filters)

    override suspend fun mediaItem(chatId: Long, messageId: Long): MediaItem? = repository().mediaItem(chatId, messageId)

    override suspend fun fileState(fileId: Int): FileState = withContext(Dispatchers.IO) {
        Td.client.getFile(fileId).valueOrNull?.let(::stateOf) ?: FileState(fileId, 0, 0, complete = false, downloading = false)
    }

    override suspend fun download(fileId: Int, priority: Int, offset: Long, limit: Long, synchronous: Boolean): FileState =
        withContext(Dispatchers.IO) {
            val file = Td.client.downloadFile(fileId, priority.coerceIn(1, 32), offset, limit, synchronous).valueOrNull
            file?.let(::stateOf) ?: fileState(fileId)
        }

    override suspend fun cancelDownload(fileId: Int) = Td.cancelDownload(fileId)

    override suspend fun smallFile(fileId: Int, priority: Int): ByteArray? = withContext(Dispatchers.IO) {
        val file = Td.client.downloadFile(fileId, priority.coerceIn(1, 32), 0, 0, true).valueOrNull ?: return@withContext null
        file.local.path.takeIf { file.local.isDownloadingCompleted && it.isNotBlank() }
            ?.let { runCatching { File(it).readBytes() }.getOrNull() }
    }

    override suspend fun sponsoredMessages(chatId: Long): SponsoredBatch = sponsored().load(chatId)
    override suspend fun viewSponsored(chatId: Long, messageId: Long) = sponsored().markViewed(chatId, messageId)
    override suspend fun clickSponsored(chatId: Long, messageId: Long, media: Boolean) = sponsored().click(chatId, messageId, media)
    override suspend fun reportSponsored(chatId: Long, messageId: Long, optionId: ByteArray): SponsoredReportOutcome =
        sponsored().report(chatId, messageId, optionId)

    override suspend fun findSavedText(tag: String, knownId: Long): SavedText? = withContext(Dispatchers.IO) {
        val td = Td.client
        val saved = savedChatId() ?: return@withContext null
        fun ours(message: Message): SavedText? =
            (message.content as? MessageText)?.text?.text?.takeIf { it.trimStart().startsWith(tag) }?.let { SavedText(message.id, it) }
        if (knownId != 0L) td.getMessage(saved, knownId).valueOrNull?.let(::ours)?.let { return@withContext it }
        td.searchChatMessages(saved, null, tag, null, 0, 0, SEARCH_LIMIT, null).valueOrNull?.messages
            ?.firstNotNullOfOrNull(::ours)
            ?: td.getChatHistory(saved, 0, 0, HISTORY_LIMIT, false).valueOrNull?.messages?.filterNotNull()?.firstNotNullOfOrNull(::ours)
    }

    override suspend fun writeSavedText(id: Long, text: String): Long? = withContext(Dispatchers.IO) {
        val td = Td.client
        val saved = savedChatId() ?: return@withContext null
        val content = InputMessageText(FormattedText(text, emptyArray()), LinkPreviewOptions(true, "", false, false, false), false)
        if (id != 0L) return@withContext td.editMessageText(saved, id, null, content).valueOrNull?.id
        val options = MessageSendOptions(null, true, false, false, false, 0L, false, null, 0L, 0, false)
        td.sendMessage(saved, null, null, options, null, content).valueOrNull?.id?.takeIf { it != 0L }
    }

    private suspend fun savedChatId(): Long? {
        val me = Td.myId().takeIf { it != 0L } ?: return null
        return Td.client.createPrivateChat(me, false).valueOrNull?.id ?: me
    }

    private fun stateOf(file: dev.g000sha256.tdl.dto.File) = FileState(
        fileId = file.id,
        size = MediaText.fileSize(file.size, file.expectedSize),
        downloadedPrefix = file.local.downloadedPrefixSize,
        complete = file.local.isDownloadingCompleted,
        downloading = file.local.isDownloadingActive,
        localPath = file.local.path,
    )

    private companion object {
        const val SEARCH_LIMIT = 5
        const val HISTORY_LIMIT = 30
    }
}
