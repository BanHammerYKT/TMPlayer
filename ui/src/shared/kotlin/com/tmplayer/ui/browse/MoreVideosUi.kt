package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import com.tmplayer.data.ChatGate
import com.tmplayer.data.ChatVideoNotes
import com.tmplayer.data.Failures
import com.tmplayer.data.LinkRefusal
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PostComments
import com.tmplayer.data.Td
import com.tmplayer.data.VideoPage
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.SlowAnswer
import dev.g000sha256.tdl.TdlClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Videos read a page at a time from somewhere other than a chat's own video search (CP81): a
 * chat's round videos, the comments under a post. A failed or slow page says so, and
 * [loadMore] asks again.
 */
class PagedVideos(
    private val scope: CoroutineScope,
    private val fetch: suspend (td: TdlClient, from: Long) -> VideoPage,
) {
    data class State(
        val items: List<MediaItem> = emptyList(),
        val loading: Boolean = true,
        val done: Boolean = false,
        val failure: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    private var next = 0L
    private var job: Job? = null

    init {
        loadMore()
    }

    fun loadMore() {
        if (job?.isActive == true || _state.value.done) return
        _state.update { it.copy(loading = true, failure = null) }
        job = scope.launch {
            try {
                val page = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                    val session = Td.awaitConnectedSessionOrNull() ?: return@withTimeoutOrNull null
                    fetch(session.client, next)
                } ?: run {
                    _state.update { it.copy(loading = false, failure = SlowAnswer.MESSAGE) }
                    return@launch
                }
                next = page.next
                _state.update { state ->
                    State(
                        items = (state.items + page.items).distinctBy { it.id }.sortedByDescending { it.messageId },
                        loading = false,
                        done = page.done,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: LinkRefusal) {
                _state.update { it.copy(loading = false, done = true, failure = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, failure = Failures.humanise(e)) }
            }
        }
    }

    companion object {
        private const val PAGE_TIMEOUT_MS = 25_000L

        /** A chat's round video messages. */
        fun roundVideos(scope: CoroutineScope, chatId: Long) = PagedVideos(scope) { td, from ->
            ChatVideoNotes.page(td, chatId, from)
        }

        /**
         * The videos in the comments under a channel post. The comments' group is a chat of its
         * own, so the kids lock has to allow it too, or nothing is listed and that is said.
         */
        fun comments(scope: CoroutineScope, chatId: Long, messageId: Long) = PagedVideos(scope) { td, from ->
            val thread = PostComments.thread(td, chatId, messageId) ?: throw LinkRefusal(L.detailCommentsNone)
            if (!PostComments.allowed(ChatGate.awaitReady(), chatId, thread.chatId)) throw LinkRefusal(L.linkKidsLock)
            PostComments.page(td, chatId, messageId, thread.chatId, from)
        }
    }
}

/** How many comments [item]'s post has, for the detail page's "Videos in the comments"; 0 until known. */
@Composable
fun rememberCommentCount(item: MediaItem): Int {
    val count by produceState(initialValue = 0, item.chatId, item.messageId) {
        val session = Td.awaitConnectedSessionOrNull() ?: return@produceState
        value = runCatching { PostComments.count(session.client, item.chatId, item.messageId) }.getOrDefault(0)
    }
    return count
}

