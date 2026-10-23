package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Td
import com.tmplayer.online.KnownMedia
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One video's page on a [DetailBackStack].
 *
 * @param page what the host shows (the video, with its chat's name where the host keeps one).
 * @param openedAs the video the page was opened as: the tile pressed on the page under it. A
 *   copy chosen later turns [page] but keeps this, so Back still finds that tile.
 * @param returnTo set when Back has just come down to this page from the one above it: the tile
 *   on this page the remote's focus goes back to. Null on a page opened afresh.
 */
@Immutable
data class DetailEntry<T>(val page: T, val openedAs: String, val returnTo: DetailReturn? = null)

/**
 * The way back to the tile a page was left by ("More like this", an episode), handed to the page
 * Back comes down to. The tile asks for the focus once its row is drawn ([claim]); the page's own
 * first focus (Play) waits a moment for that ([tookFocus]) and only then takes over.
 */
@Stable
class DetailReturn(val toId: String) {
    /** Settled, one way or the other: the tile took the focus, or the page gave up waiting. */
    var done by mutableStateOf(false)
        private set

    /** The tile took the focus, so the page leaves its own first focus and scroll alone. */
    var landed by mutableStateOf(false)
        private set

    /** The tile has the focus (or is in view, on a touch screen). False when the page gave up first. */
    fun claim(): Boolean {
        if (done) return false
        landed = true
        done = true
        return true
    }

    /** The page stops waiting for the tile. */
    fun giveUp() {
        done = true
    }
}

/** The return the page being drawn was come back to with, or null on a page opened afresh. */
val LocalDetailReturn = compositionLocalOf<DetailReturn?> { null }

/**
 * Waits a moment for the tile Back came down to to take the focus, and says whether it did. False
 * at once on a page opened afresh. Either way the wait is over afterwards, so a row that arrives
 * later does not pull the focus away from Play.
 */
suspend fun DetailReturn?.tookFocus(): Boolean {
    val back = this ?: return false
    if (!back.done) withTimeoutOrNull(RETURN_FOCUS_WAIT_MS) { snapshotFlow { back.done }.first { it } }
    back.giveUp()
    return back.landed
}

/** How long a page waits for the tile it was left by before Play takes the focus instead. */
const val RETURN_FOCUS_WAIT_MS = 1_500L

/**
 * The pages of videos opened one from another (a page's "More like this", a show's episode), so
 * Back walks back down through them before it closes the page, the way a streaming app's title
 * pages do. Each host (a chat's grid, Home, the desktop's sheet) keeps one.
 *
 * - [open] starts afresh from a tile in a list: the one page.
 * - [push] lays another video's page over the one showing. A video already on the stack is gone
 *   back to rather than stacked twice, and only the last [cap] pages are kept, so a long chain of
 *   "More like this" holds ten pages at most (the oldest drops off the bottom).
 * - [turn] changes the page showing in place, for a copy of the same film picked from its list.
 * - [back] takes the top page off; false when that was the last, so the page closes.
 *
 * Plain state; [rememberDetailBackStack] keeps the pages across the host's activity being made
 * anew.
 */
@Stable
class DetailBackStack<T : Any>(
    private val itemOf: (T) -> MediaItem,
    private val cap: Int = MAX_PAGES,
) {
    var entries: List<DetailEntry<T>> by mutableStateOf(emptyList())
        private set

    /** The page showing, or null when none is. */
    val top: DetailEntry<T>? get() = entries.lastOrNull()

    /** Whether Back has a page under this one to go to, rather than closing. */
    val canGoBack: Boolean get() = entries.size > 1

    fun open(page: T) {
        entries = listOf(DetailEntry(page, itemOf(page).id))
    }

    fun push(page: T) {
        val now = entries
        if (now.isEmpty()) return open(page)
        val item = itemOf(page)
        val at = now.indexOfFirst { sameVideo(itemOf(it.page), item) || it.openedAs == item.id }
        entries = when {
            // Already showing: a second press of the same tile, or the page's own film.
            at == now.lastIndex -> now
            // Further down: Back to it, as if each page above had been closed in turn.
            at >= 0 -> now.take(at) + now[at].copy(returnTo = DetailReturn(now[at + 1].openedAs))
            else -> (now + DetailEntry(page, item.id)).takeLast(cap)
        }
    }

    fun turn(page: T) {
        val showing = top ?: return open(page)
        entries = entries.dropLast(1) + showing.copy(page = page)
    }

    fun back(): Boolean {
        val now = entries
        if (now.size <= 1) {
            entries = emptyList()
            return false
        }
        val left = now.last()
        entries = now.dropLast(2) + now[now.size - 2].copy(returnTo = DetailReturn(left.openedAs))
        return true
    }

    fun clear() {
        entries = emptyList()
    }

    /** The pages put back as they were, for [rememberDetailBackStack]; ignored once a page is open. */
    fun restore(pages: List<DetailEntry<T>>) {
        if (entries.isEmpty()) entries = pages.takeLast(cap)
    }

    companion object {
        /** Pages kept at most: enough to walk back a long way, few enough to hold little. */
        const val MAX_PAGES = 10

        /** The same film: the same file, or one of the copies it was folded with. */
        fun sameVideo(a: MediaItem, b: MediaItem): Boolean =
            a.id == b.id || a.versions.any { it.id == b.id } || b.versions.any { it.id == a.id }
    }
}

/**
 * A [DetailBackStack] whose pages come back when the host's activity is made anew while the player
 * is up: a 1 GB TV reclaims the screen under the player, and so does Android's "Don't keep
 * activities", and Back from the video then landed on the list instead of the video's page
 * (2026-10-23). The pages are saved as their messages (with [labelOf], the chat's name where the
 * host shows one) and found again among the videos this session listed ([KnownMedia], with the
 * copies folded into them), or read again from Telegram ([Td.refreshMedia]) when the app itself
 * was stopped meanwhile.
 *
 * Nothing is restored over a page opened in the meantime, and a message that is gone is left out.
 */
@Composable
fun <T : Any> rememberDetailBackStack(
    vararg inputs: Any?,
    itemOf: (T) -> MediaItem,
    labelOf: (T) -> String = { "" },
    rebuild: (MediaItem, String) -> T,
): DetailBackStack<T> {
    val saved = rememberSaveable(*inputs) { mutableStateOf("") }
    val stack = remember(*inputs) { DetailBackStack(itemOf) }
    val label by rememberUpdatedState(labelOf)
    val make by rememberUpdatedState(rebuild)
    LaunchedEffect(stack) {
        val lines = saved.value.lines().filter { it.isNotBlank() }
        if (lines.isNotEmpty() && stack.entries.isEmpty()) {
            withTimeoutOrNull(DETAIL_RESTORE_WAIT_MS) { Td.connected.first { it } }
            val pages = lines.mapNotNull { line ->
                val parts = line.split(FIELD)
                val ids = parts.getOrNull(0)?.split(':') ?: return@mapNotNull null
                val chatId = ids.getOrNull(0)?.toLongOrNull() ?: return@mapNotNull null
                val messageId = ids.getOrNull(1)?.toLongOrNull() ?: return@mapNotNull null
                val id = "$chatId:$messageId"
                // As this session last listed it, with the copies folded into it, while the app
                // was not stopped meanwhile; otherwise as Telegram has the message.
                val item = KnownMedia.all().filter { it.id == id || it.versions.any { copy -> copy.id == id } }
                    .maxByOrNull { it.versions.size }
                    ?: runCatching { Td.refreshMedia(chatId, messageId) }.getOrNull()
                    ?: return@mapNotNull null
                DetailEntry(make(item, parts.getOrNull(2).orEmpty()), parts.getOrNull(1) ?: item.id)
            }
            stack.restore(pages)
        }
        snapshotFlow { stack.entries }.collect { entries ->
            saved.value = entries.joinToString("\n") { entry ->
                listOf(itemOf(entry.page).id, entry.openedAs, label(entry.page))
                    .joinToString(FIELD) { it.replace('\n', ' ').replace(FIELD, " ") }
            }
        }
    }
    return stack
}

/** Between a saved page's message, the tile it was opened as and its label. */
private const val FIELD = "\t"

/** How long a restored stack waits for Telegram before giving up on its pages. */
private const val DETAIL_RESTORE_WAIT_MS = 10_000L
