package com.tmplayer.desktop.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import com.tmplayer.data.MediaItem
import com.tmplayer.data.PlaybackWarmup
import com.tmplayer.ui.components.keepTopInSight
import com.tmplayer.ui.components.warmUpOnIntent
import com.tmplayer.ui.theme.Motion
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The tile and list modifiers that answer focus and the pointer, as modifier nodes. */
@OptIn(ExperimentalTestApi::class)
class IntentModifiersTest {

    private val warmed = mutableListOf<Long>()

    private fun video(message: Long) = MediaItem(
        chatId = 7, messageId = message, fileId = 0, title = "Video $message", sizeBytes = 1L,
        durationSec = 60, mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0,
    )

    @After
    fun tearDown() {
        PlaybackWarmup.warmer = null
        Motion.television = false
    }

    @Test
    fun `focus warms after the dwell and a press warms at once`() = runComposeUiTest {
        PlaybackWarmup.warmer = PlaybackWarmup.Warmer { warmed += it.messageId }
        val held = video(System.nanoTime())
        val pressed = video(System.nanoTime() + 1)
        mainClock.autoAdvance = false
        setContent {
            Box {
                Box(Modifier.size(50.dp).testTag("held").warmUpOnIntent(held).focusable())
                Box(Modifier.size(50.dp).testTag("pressed").warmUpOnIntent(pressed))
            }
        }
        onNodeWithTag("held").requestFocus()
        mainClock.advanceTimeBy(PlaybackWarmup.DWELL_MS / 2)
        assertEquals(emptyList<Long>(), warmed)
        mainClock.advanceTimeBy(PlaybackWarmup.DWELL_MS)
        assertEquals(listOf(held.messageId), warmed)

        onNodeWithTag("pressed").performMouseInput { click() }
        mainClock.advanceTimeBy(16)
        assertEquals(listOf(held.messageId, pressed.messageId), warmed)
    }

    @Test
    fun `focus on a first row that fits brings the list back to its top`() = topComesBack(television = false)

    @Test
    fun `on a television the list jumps back to its top`() = topComesBack(television = true)

    private fun topComesBack(television: Boolean) = runComposeUiTest {
        Motion.television = television
        val state = LazyListState()
        setContent {
            LazyColumn(Modifier.size(200.dp, 300.dp).keepTopInSight(state), state = state) {
                // A heading that takes no focus, then rows that do.
                item { Box(Modifier.fillMaxWidth().height(100.dp)) }
                items(20) { row -> Box(Modifier.fillMaxWidth().height(80.dp).testTag("row$row").focusable()) }
            }
        }
        runOnIdle { state.requestScrollToItem(0, 40) }
        waitForIdle()
        assertEquals(40, state.firstVisibleItemScrollOffset)
        onNodeWithTag("row0").requestFocus()
        waitForIdle()
        assertEquals(0, state.firstVisibleItemIndex)
        assertEquals(0, state.firstVisibleItemScrollOffset)
    }
}
