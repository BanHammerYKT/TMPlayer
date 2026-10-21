package com.tmplayer.ui.browse

import com.tmplayer.data.ChatGrid
import com.tmplayer.data.MediaCursors
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PageQueries
import com.tmplayer.data.QueryKey
import com.tmplayer.ui.components.UiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A chat opened again lists what the page cache kept from the last visit inside the view model's
 * constructor, with no TDLib at all: the first frame is the grid, never the skeleton. The size
 * limits are applied on reading, so a limit changed since is right from the kept copy.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatGridKeptTest {

    private val chatId = 4242L

    private fun video(id: Long, size: Long) = MediaItem(
        chatId = chatId, messageId = id, fileId = id.toInt(), title = "v$id", sizeBytes = size, durationSec = 60,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0, fileName = "v$id.mp4",
    )

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        PageQueries.cache.put(
            QueryKey.ChatMedia(chatId),
            ChatGrid(
                items = (300L downTo 1L).map { video(it, if (it % 3 == 0L) 10L else 500L) },
                cursors = MediaCursors(video = 1, document = 1, animation = 1),
                endReached = false,
                scrollIndex = 120,
            ),
        )
    }

    @After fun tearDown() {
        PageQueries.cache.remove { it.chatId == chatId }
        Dispatchers.resetMain()
    }

    @Test
    fun `a kept listing is drawn from the constructor, through the size limits as they are now`() {
        val started = System.nanoTime()
        val model = MediaListViewModel(chatId, minSizeBytes = 100, maxSizeBytes = Long.MAX_VALUE)
        val shown = model.state.value
        val tookMs = (System.nanoTime() - started) / 1_000_000.0
        println("[page-cache] second visit of a 300 video chat: Content in %.2f ms".format(tookMs))
        assertTrue("drawn at once, not Loading: $shown", shown is UiState.Content)
        val items = (shown as UiState.Content).value.items
        assertEquals("the size limit hides the small ones", 200, items.size)
        assertEquals(100, model.sizeHidden.value)
        assertEquals(120 to 0, model.restoredScroll)
        model.rememberScroll(140, 12)
        // Once the classes are loaded (the first construction above), as on any visit after the first.
        val again = System.nanoTime()
        val warm = MediaListViewModel(chatId, 0, Long.MAX_VALUE)
        println("[page-cache] warm second visit: Content in %.2f ms".format((System.nanoTime() - again) / 1_000_000.0))
        assertTrue(warm.state.value is UiState.Content)
        assertEquals(140 to 12, warm.restoredScroll)
    }
}
