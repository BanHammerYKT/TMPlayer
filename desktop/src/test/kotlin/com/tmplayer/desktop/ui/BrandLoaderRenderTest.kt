package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import com.tmplayer.i18n.L
import com.tmplayer.ui.components.BrandLoader
import com.tmplayer.ui.components.BrandLoaderHandOff
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.TmSchemes
import androidx.compose.ui.graphics.toArgb
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The loader after the splash, on a desktop window: a small spinner in the middle of the ground,
 * and nothing else moving. With TMPLAYER_SHOTS=<dir> the
 * frames are written there as PNGs, at 1366 by 768 and 1920 by 1080 in both themes, plus a run of
 * frames through one whole loop for an animated preview.
 */
class BrandLoaderRenderTest {

    @Test
    fun `the spinner turns in the middle and leaves the ground alone`() {
        for (dark in listOf(true, false)) {
            val ground = TmSchemes.of(dark).background.toArgb()
            val w = 1366
            val h = 768
            val first = pixels(render(w, h, dark, sweepAt = 0.3f))
            val later = pixels(render(w, h, dark, sweepAt = 0.7f))
            var moved = 0
            for (i in first.indices) {
                if (first[i] == later[i]) continue
                moved++
                // Only the spinner moves, and it stands in the middle of the window.
                val x = i % w
                val y = i / w
                assertTrue("dark=$dark pixel ($x, $y) moved outside the middle", x in w / 2 - 80..w / 2 + 80 && y in h / 2 - 160..h / 2 + 160)
            }
            assertTrue("the spinner shows (dark=$dark)", moved > 50)
            assertEquals(ground, first[0])
            assertEquals(ground, first[first.lastIndex])
        }
    }

    @Test
    fun `the mark stands still`() {
        // One line, so nothing steps: the lines change on real time. Two frames well apart, after
        // one that lets anything still loading (a font on a slow runner) arrive.
        val oneLine = listOf(L.stateConnectingSession)
        val still = scene(1366, 768, dark = true, lines = oneLine) { scene ->
            frames(scene, listOf(700L))
            Thread.sleep(1_500)
            listOf(1_400L, 2_100L).map { scene.render(it * 1_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes }
        }
        assertTrue(still[0].contentEquals(still[1]))
    }

    @Test
    fun `the loader fades out over the screen under it, then leaves`() {
        val label = mutableStateOf<String?>(L.signinConnecting)
        val under = Color(0xFFFF00FF.toInt())
        ImageComposeScene(400, 300, Density(1f)) {
            TmMaterialTheme(dark = true) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().background(under))
                    BrandLoaderHandOff(label.value)
                }
            }
        }.use { scene ->
            scene.render(0)
            val ground = TmSchemes.Dark.background.toArgb()
            assertEquals(ground, corner(scene.render(100_000_000L)))
            label.value = null
            Snapshot.sendApplyNotifications()
            for (ms in 120L..300L step 20L) scene.render(ms * 1_000_000L)
            val midway = corner(scene.render(320_000_000L))
            assertTrue("partly faded", midway != ground && midway != under.toArgb())
            for (ms in 340L..980L step 40L) scene.render(ms * 1_000_000L)
            assertEquals(under.toArgb(), corner(scene.render(1_000_000_000L)))
        }
    }

    @Test
    fun shots() {
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        out.mkdirs()
        for ((w, h) in listOf(1366 to 768, 1920 to 1080)) {
            for (dark in listOf(true, false)) {
                val theme = if (dark) "dark" else "light"
                for (at in listOf(0.15f, 0.35f, 0.5f, 0.65f, 0.85f)) {
                    val name = "desktop-${w}x$h-$theme-${(at * 100).toInt()}.png"
                    out.resolve(name).writeBytes(render(w, h, dark, sweepAt = at))
                }
            }
        }
        // One loop at 25 frames a second, from the scene's own clock, for the animated preview.
        for (dark in listOf(true, false)) {
            val theme = if (dark) "dark" else "light"
            val dir = out.resolve("loop-desktop-$theme").apply { mkdirs() }
            val times = (0..LOOP_FRAMES).map { it * 40L }
            scene(960, 540, dark) { frames(it, times) }.forEachIndexed { i, png ->
                dir.resolve("f%03d.png".format(i)).writeBytes(png)
            }
        }
    }

    private fun render(w: Int, h: Int, dark: Boolean, sweepAt: Float): ByteArray =
        scene(w, h, dark, sweepAt) { frames(it, listOf(800L)).single() }

    private fun <T> scene(
        w: Int,
        h: Int,
        dark: Boolean,
        sweepAt: Float? = null,
        lines: List<String>? = null,
        block: (ImageComposeScene) -> T,
    ): T =
        ImageComposeScene(w, h, Density(1f)) {
            TmMaterialTheme(dark = dark) { Loader(sweepAt, lines) }
        }.use(block)

    @Composable
    private fun Loader(sweepAt: Float?, lines: List<String>?) {
        if (lines == null) {
            BrandLoader(label = L.signinConnecting, sweepAt = sweepAt)
        } else {
            BrandLoader(label = L.signinConnecting, lines = lines, sweepAt = sweepAt)
        }
    }

    private fun frames(scene: ImageComposeScene, millis: List<Long>): List<ByteArray> {
        scene.render(0)
        return millis.map { scene.render(it * 1_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes }
    }

    private fun corner(image: Image): Int = Bitmap.makeFromImage(image).getColor(2, 2)

    private fun pixels(png: ByteArray): IntArray {
        val bitmap = Bitmap.makeFromImage(Image.makeFromEncoded(png))
        val out = IntArray(bitmap.width * bitmap.height)
        var i = 0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) out[i++] = bitmap.getColor(x, y)
        assertEquals(out.size, i)
        return out
    }

    private companion object {
        /** 3 s, the sweep and its rest, at 40 ms a frame. */
        const val LOOP_FRAMES = 75
    }
}
