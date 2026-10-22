package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import com.tmplayer.data.DetailAction
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.ShelfEntry
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.browse.DetailRow
import com.tmplayer.ui.browse.LocalSeriesWatch
import com.tmplayer.ui.browse.MediaDetailScreen
import com.tmplayer.ui.browse.PLAY_PILL_TAG
import com.tmplayer.ui.browse.SeriesPanel
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.browse.WideDetailPanel
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.CompletableDeferred
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A title page comes together without moving Play: drawn while its metadata is still on its way
 * (the canned TMDB held back by a gate), then again once the match, the extras and the scores have
 * arrived, Play has the same bounds. Opened a second time, from the caches, the first frame
 * already has the extras in it, with Play in the same place again. For the phone held upright and
 * on its side, the desktop's large detail, and a show's page.
 */
@OptIn(ExperimentalTestApi::class)
class DetailNoShiftTest {

    private val bunny = MediaItem(
        chatId = 2, messageId = 500, fileId = 0, title = "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv", sizeBytes = 885L * 1024 * 1024,
        durationSec = 596, mimeType = "video/x-matroska", thumbnailFileId = 0, miniThumbnail = null, date = 1_790_000_000,
        fileName = "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv", width = 1920, height = 1080,
    )

    private val episodes = (1..4).map { n ->
        bunny.copy(chatId = 4, messageId = 600L + n, title = "Harbour.Lights.S01E%02d.1080p.WEB-DL.mkv".format(n), fileName = "Harbour.Lights.S01E%02d.1080p.WEB-DL.mkv".format(n))
    }

    private val rows = listOf(
        DetailRow(DetailAction.Play, "Play", TmIcons.Play, onSelect = {}),
        DetailRow(DetailAction.MarkWatched, "Mark as watched", TmIcons.Check, onSelect = {}),
        DetailRow(DetailAction.AddWatchLater, "Add to Watch later", TmIcons.Clock, onSelect = {}),
        DetailRow(DetailAction.Download, "Download", TmIcons.Download, onSelect = {}),
    )

    private var reduced = false

    @Before
    fun still() {
        // The page's own entrance (each block rising into place) is motion, not a shift: off, so
        // the bounds read are where the blocks rest.
        reduced = Motion.reduced
        Motion.reduced = true
    }

    @After
    fun restore() {
        Motion.reduced = reduced
        OnlineMetadata.current = null
    }

    @Test
    fun `a film's page upright keeps Play in place as its metadata arrives`() = noShift(412, 900, arrived = "Animation", shot = "film-tall") {
        MediaDetailScreen(bunny, "Film Club", null, false, rows, onClose = {})
    }

    @Test
    fun `a film's page on its side keeps Play in place as its metadata arrives`() = noShift(900, 412, arrived = "Animation", shot = "film-wide") {
        MediaDetailScreen(bunny, "Film Club", null, false, rows, onClose = {})
    }

    @Test
    fun `the desktop's large detail keeps Play in place as its metadata arrives`() = noShift(1000, 740, arrived = "Animation", shot = "film-desktop") {
        WideDetailPanel(bunny, "Film Club", null, false, rows, onClose = {})
    }

    @Test
    fun `a show's page keeps Play in place as its metadata arrives`() = noShift(412, 1000, arrived = "Drama", shot = "show-tall") {
        val show = (SeriesShelf.arrange(episodes).first() as ShelfEntry.Show).series
        SeriesPanel(show, SeriesWatch.None, onPlay = {}, onDownload = {}, chatTitle = "Weekend series")
    }

    /**
     * Draws [page] at [width] by [height] dp with the metadata held back, past the first frame's
     * hold, reads Play's bounds; lets the metadata through and waits for [arrived] (a word of the
     * extras' facts line) to be on the page, and reads them again; then closes and opens the page,
     * which now draws from the caches, and reads them on its first frame.
     */
    private fun noShift(width: Int, height: Int, arrived: String, shot: String, page: @Composable () -> Unit) = runComposeUiTest {
        val gate = CompletableDeferred<Unit>()
        MetaFixture.install("on", gate)
        var open by mutableStateOf(true)
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                TmMaterialTheme(dark = true) {
                    CompositionLocalProvider(LocalSeriesWatch provides SeriesWatch.None) {
                        Box(Modifier.size(width.dp, height.dp)) {
                            Surface(Modifier.size(width.dp, height.dp), color = Tone.background) { if (open) page() }
                        }
                    }
                }
            }
        }
        // Past the hold: the page draws with places held for what has not come.
        advance { onAllNodesWithTag(PLAY_PILL_TAG).fetchSemanticsNodes().isNotEmpty() }
        assertTrue("nothing had arrived yet", onAllNodesWithText(arrived, substring = true).fetchSemanticsNodes().isEmpty())
        val first = playBounds()
        save("noshift-$shot-waiting.png")

        gate.complete(Unit)
        advance { onAllNodesWithText(arrived, substring = true).fetchSemanticsNodes().isNotEmpty() }
        // A beat more for the scores and the fades.
        repeat(10) { step() }
        assertEquals("Play moved when the metadata arrived", first, playBounds())
        save("noshift-$shot-arrived.png")

        // Again, from the caches: complete on its first frame, and nothing moves after it.
        open = false
        mainClock.advanceTimeByFrame()
        open = true
        mainClock.advanceTimeByFrame()
        assertTrue("the second visit draws the extras at once", onAllNodesWithText(arrived, substring = true).fetchSemanticsNodes().isNotEmpty())
        val again = playBounds()
        repeat(10) { step() }
        assertEquals("Play moved on the second visit", again, playBounds())
    }

    /** With TMPLAYER_SHOTS=<dir>, the frame as it stands, written there. */
    private fun ComposeUiTest.save(name: String) {
        val dir = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        val image = onRoot().captureToImage().toAwtImage()
        javax.imageio.ImageIO.write(image, "png", java.io.File(dir).apply { mkdirs() }.resolve(name))
    }

    private fun ComposeUiTest.playBounds(): DpRect = onNodeWithTag(PLAY_PILL_TAG).getBoundsInRoot()

    /** The test clock and the real one both moved on a little: the lookups run on threads of their own. */
    private fun ComposeUiTest.step() {
        Thread.sleep(20)
        mainClock.advanceTimeBy(50)
    }

    private fun ComposeUiTest.advance(until: () -> Boolean) {
        repeat(200) {
            step()
            if (until()) return
        }
        throw AssertionError("timed out")
    }
}
