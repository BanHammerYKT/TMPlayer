package com.tmplayer.data

import com.tmplayer.i18n.L
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Keeping a video without downloading it (CP82): forwarding its post to Saved Messages or to
 * another chat. Telegram copies nothing; the post is shared, so it takes no room on the device.
 * A chat that restricts saving content restricts forwarding too, and that is said, not hidden.
 */
object KeepVideo {

    /** Whether a post may be forwarded, as Telegram answers for it. */
    enum class Forwarding { Allowed, Protected, NotForwardable }

    /**
     * The rule: [canBeSaved] false (the chat or the post restricts saving) is [Forwarding.Protected]
     * whatever else is known; otherwise Telegram's own `canBeForwarded`, unknown counting as allowed
     * until it is asked at the press.
     */
    fun verdict(canBeSaved: Boolean, canBeForwarded: Boolean?, protectedContent: Boolean = false): Forwarding = when {
        !canBeSaved || protectedContent -> Forwarding.Protected
        canBeForwarded == false -> Forwarding.NotForwardable
        else -> Forwarding.Allowed
    }

    /** Why a forward is refused, in words. */
    fun reason(forwarding: Forwarding): String? = when (forwarding) {
        Forwarding.Allowed -> null
        Forwarding.Protected -> L.keepProtected
        Forwarding.NotForwardable -> L.keepNotForwardable
    }

    /**
     * The posts that make the tile: every byte piece of a cut video, every part of a film in
     * parts, or the one post.
     */
    fun messagesOf(item: MediaItem): List<Long> = when {
        item.pieces.isNotEmpty() -> item.pieces.map { it.messageId }
        item.parts.size > 1 -> item.parts.map { it.messageId }
        else -> listOf(item.messageId)
    }.distinct().sorted()

    /** Telegram's answer for [item]'s post. */
    suspend fun check(td: TdlClient, item: MediaItem): Forwarding = withContext(Dispatchers.IO) {
        if (!item.canBeSaved) return@withContext Forwarding.Protected
        val properties = td.getMessageProperties(item.chatId, item.messageId).valueOrNull
        verdict(
            canBeSaved = properties?.canBeSaved ?: item.canBeSaved,
            canBeForwarded = properties?.canBeForwarded,
            protectedContent = properties?.hasProtectedContentByOtherUser == true,
        )
    }

    /** Forwards [item] to [toChatId]; null when it went, or why it did not. */
    suspend fun forward(td: TdlClient, item: MediaItem, toChatId: Long): String? = withContext(Dispatchers.IO) {
        if (!KidsLock.allows(ChatGate.awaitReady(), toChatId) && toChatId != Td.myId()) return@withContext L.linkKidsLock
        reason(check(td, item))?.let { return@withContext it }
        // Not as a copy and with the caption: a forward says where it came from, as Telegram's own does.
        val answer = td.forwardMessages(toChatId, null, item.chatId, messagesOf(item).toLongArray(), null, false, false)
        when (answer) {
            is TdlResult.Success -> if (answer.result.messages.isEmpty()) L.keepFailed(Failures.DEFAULT) else null
            is TdlResult.Failure -> forwardFailure(answer.message)
        }
    }

    /** Forwards [item] to the account's own Saved Messages. */
    suspend fun toSaved(td: TdlClient, item: MediaItem): String? = withContext(Dispatchers.IO) {
        val myId = Td.myId().takeIf { it != 0L } ?: return@withContext Failures.OFFLINE
        val saved = td.createPrivateChat(myId, false).valueOrNull?.id ?: myId
        forward(td, item, saved)
    }

    /** A forward's error, said. */
    fun forwardFailure(message: String?): String {
        val raw = message.orEmpty()
        return when {
            raw.contains("CHAT_FORWARDS_RESTRICTED") || raw.contains("protected", ignoreCase = true) -> L.keepProtected // i18n-ok: TDLib text, matched
            raw.contains("CHAT_WRITE_FORBIDDEN") || raw.contains("CHAT_SEND") || raw.contains("rights", ignoreCase = true) -> L.keepCannotPost // i18n-ok: TDLib text, matched
            raw.contains("can't be forwarded", ignoreCase = true) -> L.keepNotForwardable // i18n-ok: TDLib text, matched
            else -> L.keepFailed(Failures.humanise(raw))
        }
    }

    /**
     * The chats a video can be forwarded to from the picker: Saved Messages first, then people
     * and groups, never a channel (only its admins post there, and the list does not know who),
     * and only the chats the kids lock allows.
     */
    fun targets(chats: List<ChatSummary>, kidsAllowed: Set<Long>?, query: String = ""): List<ChatSummary> {
        val q = query.trim()
        return KidsLock.chats(chats, kidsAllowed)
            .filter { it.kind != ChatKind.Channel }
            .filter { q.isEmpty() || it.title.contains(q, ignoreCase = true) }
            .sortedBy { if (it.kind == ChatKind.Saved) 0 else 1 }
    }

    /**
     * Whether a finished download may be copied out to the phone's gallery or a folder: only
     * where it could be saved at all (CP04's gate), and only once it is whole in Downloads.
     */
    fun mayExport(canBeSaved: Boolean, inDownloads: Boolean): Boolean = KeepRules.mayExport(canBeSaved, inDownloads)
}
