package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.ChatSummary
import com.tmplayer.data.Td
import com.tmplayer.data.Usernames
import kotlinx.coroutines.delay

/**
 * The chat whose public username [query] spells (`@name`, `name` or a t.me address), looked up
 * on Telegram's servers once the typing has paused, so Search finds a person, group or channel
 * by username even when it is not in the chat list or its title says something else. Null while
 * the query cannot be a username, while it is being asked, and when nobody has that name.
 */
@Composable
fun rememberUsernameChat(query: String): ChatSummary? {
    // Keyed by the name itself, so "@name", "name" and "name " are one lookup, not three.
    val name = remember(query) { Usernames.clean(query) }
    val found by produceState<ChatSummary?>(initialValue = null, name) {
        // Unlike a list of results, a chat found for another name is not kept up meanwhile: it
        // would offer to join "@foo" while the field says "@foob".
        value = null
        if (name == null) return@produceState
        delay(com.tmplayer.data.SearchSettle.MS)
        val session = Td.awaitConnectedSessionOrNull() ?: return@produceState
        value = runCatching { ChatRepository(session.client).findByUsername(name) }.getOrNull()
    }
    return found
}

/** [local] title matches with the username's chat in front, once, when it is not already among them. */
fun withUsernameChat(local: List<ChatSummary>, found: ChatSummary?): List<ChatSummary> =
    if (found == null || local.any { it.id == found.id }) local else listOf(found) + local

/**
 * What Search offers for a link or a username typed or pasted into it (CP79): "Open this post"
 * for a post link, "Preview and join" for an invite, and "Join" for a public group or channel
 * found by its @username that the account is not in. Pressed, it goes to [LinkRequests].
 */
data class LinkOffer(val label: String, val detail: String, val link: com.tmplayer.data.TgLink)

@Composable
fun rememberLinkOffer(query: String, found: ChatSummary?): LinkOffer? {
    val link = remember(query) { com.tmplayer.data.TelegramLinks.parse(query) }
    val joinable by produceState(initialValue = false, found?.id) {
        value = false
        val chat = found ?: return@produceState
        if (chat.kind != com.tmplayer.data.ChatKind.Channel && chat.kind != com.tmplayer.data.ChatKind.Group) return@produceState
        val session = Td.awaitConnectedSessionOrNull() ?: return@produceState
        value = runCatching { ChatRepository(session.client).chatInfo(chat.id)?.canJoin == true }.getOrDefault(false)
    }
    val strings = com.tmplayer.ui.i18n.LocalStrings.current
    return when (link) {
        is com.tmplayer.data.TgLink.Post -> LinkOffer(strings.linkOpenPost, strings.linkOpenPostDetail, link)
        is com.tmplayer.data.TgLink.Invite -> LinkOffer(strings.linkPreviewJoin, strings.linkPreviewJoinDetail, link)
        else -> {
            val name = Usernames.clean(query)
            if (found != null && joinable && name != null) {
                LinkOffer(strings.linkJoinNamed(found.title), strings.linkJoinDetail, com.tmplayer.data.TgLink.Public(name))
            } else {
                null
            }
        }
    }
}
