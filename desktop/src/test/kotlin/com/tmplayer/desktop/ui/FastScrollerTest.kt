package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.components.FAST_SCROLLER_TAG
import com.tmplayer.ui.components.FastScroller
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

/**
 * The hold to scroll handle on a long grid: it comes out when the grid moves, a mouse drag takes
 * the grid to the place the handle stands at on its track, and it draws at rest, held, and held
 * with its bubble, light and dark, left to right and right to left. With TMPLAYER_SHOTS=<dir> the
 * frames are written there as `fast-*.png`.
 */
@OptIn(ExperimentalTestApi::class)
class FastScrollerTest {

    @Test
    fun `dragging the handle half way down takes the grid half way through`() = runComposeUiTest {
        val state = LazyGridState()
        var density = Density(1f)
        mainClock.autoAdvance = false
        setContent {
            density = LocalDensity.current
            Grid(state, dark = true, labels = true)
        }
        mainClock.advanceTimeBy(600)
        // A track of the box less 8 dp at each end, less the handle's 48 dp: half of it is 328 dp.
        val half = with(density) { ((HEIGHT - 16 - 48) / 2f).dp.toPx() }
        onNodeWithTag(FAST_SCROLLER_TAG).performMouseInput {
            moveTo(center)
            press()
        }
        mainClock.advanceTimeBy(100)
        onNodeWithTag(FAST_SCROLLER_TAG).performMouseInput { moveBy(Offset(0f, half)) }
        mainClock.advanceTimeBy(200)
        // 250 rows of 100 dp with 7.2 on screen: half way is row 121, whose first item is 484.
        val row = state.firstVisibleItemIndex / COLUMNS
        assertTrue("row $row", row in 120..122)
        onNodeWithTag(FAST_SCROLLER_TAG).performMouseInput { release() }
        mainClock.advanceTimeBy(200)
        assertEquals(row, state.firstVisibleItemIndex / COLUMNS)
    }

    @Test
    fun `dragging it to the foot reaches the very end`() = runComposeUiTest {
        val state = LazyGridState()
        mainClock.autoAdvance = false
        setContent { Grid(state, dark = false, labels = false) }
        mainClock.advanceTimeBy(600)
        onNodeWithTag(FAST_SCROLLER_TAG).performMouseInput {
            moveTo(center)
            press()
            moveBy(Offset(0f, 5_000f))
        }
        mainClock.advanceTimeBy(300)
        assertTrue(!state.canScrollForward)
        assertEquals(ITEMS - 1, state.layoutInfo.visibleItemsInfo.last().index)
    }

    @Test
    fun `the handle at rest, held and with its bubble`() {
        for (dark in listOf(false, true)) {
            for (rtl in listOf(false, true)) {
                val name = (if (dark) "dark" else "light") + if (rtl) "-rtl" else ""
                runComposeUiTest {
                    val state = LazyGridState(firstVisibleItemIndex = 300)
                    mainClock.autoAdvance = false
                    setContent { Grid(state, dark, labels = true, rtl = rtl) }
                    mainClock.advanceTimeBy(600)
                    save("fast-rest-$name.png", onAllNodes(isRoot())[0].captureToImage())
                    onNodeWithTag(FAST_SCROLLER_TAG).performMouseInput {
                        moveTo(center)
                        press()
                    }
                    mainClock.advanceTimeBy(300)
                    save("fast-held-$name.png", onAllNodes(isRoot())[0].captureToImage())
                    onNodeWithTag(FAST_SCROLLER_TAG).performMouseInput { moveBy(Offset(0f, 120f)) }
                    mainClock.advanceTimeBy(300)
                    save("fast-bubble-$name.png", onAllNodes(isRoot())[0].captureToImage())
                    onNodeWithTag(FAST_SCROLLER_TAG).performMouseInput {
                        release()
                        // The pointer leaves the edge, which would otherwise keep it out.
                        moveTo(Offset(-300f, 0f))
                    }
                    // Let go, it stays a moment and then goes.
                    mainClock.advanceTimeBy(2_500)
                    assertTrue(onAllNodes(androidx.compose.ui.test.hasTestTag(FAST_SCROLLER_TAG)).fetchSemanticsNodes().isEmpty())
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Grid(state: LazyGridState, dark: Boolean, labels: Boolean, rtl: Boolean = false) {
        TmMaterialTheme(dark = dark) {
            CompositionLocalProvider(LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                Box(Modifier.size(WIDTH.dp, HEIGHT.dp).background(Tone.background)) {
                    LazyVerticalGrid(GridCells.Fixed(COLUMNS), state = state, modifier = Modifier.fillMaxSize()) {
                        items(ITEMS, key = { it }) { i ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(100.dp)
                                    .padding(6.dp)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text("${i + 1}", color = Tone.muted)
                            }
                        }
                    }
                    // A grid of a long season: the bubble names the episode at the top.
                    FastScroller(state, label = if (labels) ({ key -> "S${(key as Int) / 100 + 1} E${key % 100 + 1}" }) else null)
                    // The grid moves once, which is what brings the handle out.
                    LaunchedEffect(Unit) {
                        state.scroll {
                            scrollBy(1f)
                            kotlinx.coroutines.delay(200)
                        }
                    }
                }
            }
        }
    }

    private fun ComposeUiTest.save(name: String, image: ImageBitmap) {
        assertTrue(image.width > 0)
        val dir = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        ImageIO.write(image.toAwtImage(), "png", File(dir).apply { mkdirs() }.resolve(name))
    }

    private companion object {
        const val WIDTH = 420
        const val HEIGHT = 720
        const val COLUMNS = 4
        const val ITEMS = 1000
    }
}
