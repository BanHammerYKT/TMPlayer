package com.tmplayer.ui.browse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SettingsStore
import kotlinx.coroutines.CancellationException

/**
 * The videos behind History's saved records (Continue watching, Watch later, Watched) as Telegram
 * describes them, for their pictures.
 *
 * A record keeps no thumbnail, so a card drawn from it alone has only its generated cover. Home
 * asks Telegram for the message ([HomeViewModel.requestArt]) and draws its real thumbnail, and
 * History does the same through here: one lookup per video, kept for the life of the process in
 * a small cache so a tab switched back to draws its pictures on the first frame. What Home has
 * already looked up goes into the same cache ([keep]), so History does not ask for it again.
 */
object RecordItems {

    /** Enough for a few screens of History; the oldest goes first past it. */
    private const val KEEP = 200

    private val cache = com.tmplayer.platform.LruMap<String, MediaItem>(KEEP)

    internal fun peek(key: String): MediaItem? = cache[key]

    /** A video Telegram has described elsewhere (Home's tiles), for History to draw without asking. */
    fun keep(item: MediaItem) {
        cache[SettingsStore.progressKey(item.chatId, item.messageId)] = item
    }

    internal suspend fun lookup(chatId: Long, messageId: Long, source: HomeSource = TdHomeSource): MediaItem? {
        val key = SettingsStore.progressKey(chatId, messageId)
        peek(key)?.let { return it }
        val item = try {
            source.mediaItem(chatId, messageId)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            null
        } ?: return null
        cache[key] = item
        return item
    }
}

/**
 * The video a saved record stands for, as Telegram describes it once it answers, with its
 * thumbnail; [fallback] (the record's own [MediaItem], which has none) until then, and for good
 * when Telegram has no answer. Draw it as Home draws its tiles: the online picture where allowed,
 * the thumbnail, and the generated cover only when there is no thumbnail.
 */
@Composable
fun rememberRecordItem(chatId: Long, messageId: Long, fallback: MediaItem): MediaItem {
    val key = SettingsStore.progressKey(chatId, messageId)
    val item by produceState(RecordItems.peek(key) ?: fallback, key) {
        if (RecordItems.peek(key) == null) RecordItems.lookup(chatId, messageId)?.let { value = it }
    }
    // The picture is Telegram's, but whether the file is on this device is the record's: the
    // cache keeps an item for the life of the process, and the watch cache moves on long before
    // that, so its "Cached" would outlive the file.
    return remember(item, fallback.locality) {
        if (item.locality == fallback.locality) item else item.withLocality(fallback.locality)
    }
}

/**
 * True for an item that cannot draw a picture of its own: no thumbnail file and no inline preview,
 * as a saved record's item (Continue watching, Watch later, Watched) has. A real message has
 * a positive id; a promo or test item without one is left alone.
 */
internal fun lacksArt(item: MediaItem): Boolean =
    item.thumbnailFileId <= 0 && (item.miniThumbnail?.isEmpty() ?: true) && item.messageId > 0

/**
 * [item] for its pictures: itself when it carries a thumbnail, else the video as Telegram describes
 * it ([rememberRecordItem]), so a page opened from a saved record's card shows the thumbnail that
 * card showed rather than the generated cover.
 */
@Composable
fun rememberArtItem(item: MediaItem): MediaItem =
    if (lacksArt(item)) rememberRecordItem(item.chatId, item.messageId, item) else item
