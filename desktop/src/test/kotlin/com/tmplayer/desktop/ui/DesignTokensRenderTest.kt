package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.Thumbnails
import com.tmplayer.ui.components.ArtBackdrop
import com.tmplayer.ui.components.BackdropSource
import com.tmplayer.ui.components.CoverArt
import com.tmplayer.ui.components.GeneratedCover
import com.tmplayer.ui.components.ProgressLine
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Scrims
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface as SkSurface
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The cinematic refresh's foundations on one sheet per theme: a [GeneratedCover] in each of the
 * eight pairs (with a number, a chat initial, a progress line, and the compact form), the three
 * scrims over a white and a black picture with text on them, an [ArtBackdrop] at each step of
 * its chain, and the hero pill. With TMPLAYER_SHOTS=<dir> the frames are written there as
 * `desktop-tokens-<theme>.png`.
 */
class DesignTokensRenderTest {

    private val dir = Files.createTempDirectory("tm-tokens").toFile()

    @Test
    fun tokenSheet() {
        // The online step of the chain, warmed into the cache the way a real lookup leaves it: one
        // picture wide enough to stretch sharp, and one under the line that sits over its field.
        runBlocking {
            Thumbnails.online(WIDE_URL, 1280) { png(1280, 720, "wide") }
            Thumbnails.online(SMALL_URL, 1280) { png(320, 180, "small") }
        }
        for (dark in listOf(true, false)) {
            save("desktop-tokens-${if (dark) "dark" else "light"}.png", render(dark) { Sheet() })
        }
    }

    @Composable
    private fun Sheet() {
        Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val names = listOf(
                "Lecture 07 Vectors and matrices.mp4", "Part 4.mkv", "Harbour.Notes.S01E03.1080p.mkv",
                "Screen recording.mp4", "Organic Chemistry Chapter 12.mp4", "Coast walk, day 2.mp4",
                "Birthday highlights.mp4", "Module 3 Sourdough starter.mp4",
            )
            // One cover in each pair, the pair following the column.
            val covers = names.mapIndexed { at, name ->
                CoverArt.of(1L, name, chatTitle = if (at % 3 == 0) "Weekend Clips" else null).copy(pair = at)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                covers.take(5).forEachIndexed { at, cover ->
                    Box(Modifier.size(224.dp, 126.dp).clip(RoundedCornerShape(Corner.Medium))) {
                        GeneratedCover(cover, Modifier.fillMaxSize())
                        if (at == 1) ProgressLine(0.4f, Modifier.align(Alignment.BottomCenter))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                covers.drop(5).forEach { cover ->
                    Box(Modifier.size(224.dp, 126.dp).clip(RoundedCornerShape(Corner.Medium))) {
                        GeneratedCover(cover, Modifier.fillMaxSize())
                    }
                }
                covers.take(3).forEach { cover ->
                    Box(Modifier.size(140.dp, 79.dp).clip(RoundedCornerShape(Corner.Medium))) {
                        GeneratedCover(cover, Modifier.fillMaxSize(), compact = true)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                for (picture in listOf(Color.White, Color.Black)) {
                    ScrimSample(picture, Scrims.bottomLong(96.dp), onArt = false)
                    ScrimSample(picture, Scrims.startLong(), onArt = false)
                    ScrimSample(picture, Scrims.tileFoot, onArt = true)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val mini = jpeg(0xFF2AABEE.toInt())
                val cover = CoverArt.of(9L, "Lecture 07 Vectors.mp4")
                listOf(
                    BackdropSource(mini, 0, cover, wideUrl = WIDE_URL),
                    BackdropSource(mini, 0, cover, wideUrl = SMALL_URL),
                    BackdropSource(mini, 0, cover),
                    BackdropSource(null, 0, cover),
                ).forEach { source ->
                    Box(Modifier.size(272.dp, 153.dp).clip(RoundedCornerShape(Corner.Medium))) {
                        ArtBackdrop(source, Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Scrims.bottomLong(110.dp)))
                        Text(
                            "Lecture 7",
                            style = MaterialTheme.typography.titleMedium,
                            color = Tone.text,
                            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp),
                        )
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.height(52.dp).width(220.dp).clip(CircleShape).background(Tone.hero),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Resume", style = MaterialTheme.typography.titleMedium, color = Tone.onHero)
                }
                Text("Hero title", style = MaterialTheme.typography.displaySmall, color = Tone.text)
                Text("Muted line on the ground", style = MaterialTheme.typography.labelLarge, color = Tone.muted)
                Box(Modifier.size(120.dp, 48.dp).background(Tone.surface)) {
                    Text("surface", color = Tone.muted, modifier = Modifier.align(Alignment.Center))
                }
                Box(Modifier.size(120.dp, 48.dp).background(Tone.surfaceHigh)) {
                    Text("surfaceHigh", color = Tone.muted, modifier = Modifier.align(Alignment.Center))
                }
            }
        }
    }

    /** A scrim over a flat [picture], with text where text goes on it. */
    @Composable
    private fun ScrimSample(picture: Color, brush: Brush, onArt: Boolean) {
        Box(Modifier.size(180.dp, 110.dp).background(picture)) {
            Box(Modifier.fillMaxSize().background(brush))
            Text(
                "Title on art",
                style = MaterialTheme.typography.titleSmall,
                color = if (onArt) Tone.onArt else Tone.text,
                modifier = Modifier.align(Alignment.BottomStart).padding(10.dp),
            )
        }
    }

    private fun render(dark: Boolean, page: @Composable () -> Unit): ByteArray =
        ImageComposeScene(WIDTH, HEIGHT, Density(1f)) {
            TmMaterialTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) { page() }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(500)
            scene.render(500_000_000)
            Thread.sleep(200)
            scene.render(1_000_000_000)
            scene.render(2_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve(name).writeBytes(png)
    }

    /** Two bands and a bright disc, so a picture's shape and crop can be seen. */
    private fun png(width: Int, height: Int, name: String): File {
        val surface = SkSurface.makeRasterN32Premul(width, height)
        val w = width.toFloat()
        val h = height.toFloat()
        surface.canvas.drawRect(Rect.makeWH(w, h), Paint().apply { color = 0xFF203A6A.toInt() })
        surface.canvas.drawRect(Rect.makeWH(w / 2, h), Paint().apply { color = 0xFFB0602A.toInt() })
        surface.canvas.drawCircle(w * 0.7f, h * 0.4f, h * 0.25f, Paint().apply { color = 0xFFF5D76E.toInt() })
        val bytes = surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
        return dir.resolve("$name.png").apply { writeBytes(bytes) }
    }

    /** A tiny JPEG in [argb], standing in for Telegram's minithumbnail. */
    private fun jpeg(argb: Int): ByteArray {
        val surface = SkSurface.makeRasterN32Premul(40, 24)
        surface.canvas.drawRect(Rect.makeWH(40f, 24f), Paint().apply { color = argb or 0xFF000000.toInt() })
        surface.canvas.drawRect(Rect.makeXYWH(24f, 4f, 12f, 12f), Paint().apply { color = 0xFFF5D76E.toInt() })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 80)!!.bytes
    }

    private companion object {
        const val WIDTH = 1280
        const val HEIGHT = 860
        const val WIDE_URL = "https://image.tmdb.org/t/p/w1280/tokens-wide.webp"
        const val SMALL_URL = "https://image.tmdb.org/t/p/w300/tokens-small.webp"
    }
}
