package com.tmplayer.data

import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The inline preview made soft: larger, the same colours, and no hard edge left between blocks. */
class MiniBlurTest {

    private fun picture(w: Int, h: Int, paint: (Int, Int) -> Color): ImageBitmap {
        val image = ImageBitmap(w, h)
        val canvas = Canvas(image)
        val p = Paint()
        for (y in 0 until h) for (x in 0 until w) {
            p.color = paint(x, y)
            canvas.drawRect(x.toFloat(), y.toFloat(), x + 1f, y + 1f, p)
        }
        return image
    }

    private fun pixels(image: ImageBitmap) = IntArray(image.width * image.height).also { image.readPixels(it) }

    @Test
    fun `a flat colour stays that colour and grows to the target size`() {
        val red = Color(200, 40, 30)
        val out = MiniBlur.soften(picture(20, 30) { _, _ -> red })
        assertEquals(96, out.height)
        assertEquals(64, out.width)
        pixels(out).forEach { assertEquals(red.toArgb(), it) }
    }

    @Test
    fun `a hard edge becomes a gradual one`() {
        val out = MiniBlur.soften(picture(40, 40) { x, _ -> if (x < 20) Color.Black else Color.White })
        val row = pixels(out).let { all -> (0 until out.width).map { all[out.height / 2 * out.width + it] and 0xFF } }
        val biggestStep = row.zipWithNext { a, b -> abs(a - b) }.max()
        assertTrue("step $biggestStep", biggestStep < 40)
        assertTrue(row.first() < 10 && row.last() > 245)
    }
}
