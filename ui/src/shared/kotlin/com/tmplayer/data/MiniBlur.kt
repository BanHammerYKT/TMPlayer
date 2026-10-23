package com.tmplayer.data

import androidx.compose.ui.graphics.ImageBitmap
import kotlin.math.roundToInt

/**
 * Blurs a tiny picture (Telegram's inline preview) into a soft one, in plain code: upscaled to
 * about [TARGET] pixels on its long side, then three box blur passes each way, which is close to a
 * Gaussian. No `blur()` modifier or RenderEffect, which Android 9 (the TV sticks) does not have,
 * and nothing that needs a platform: it runs alike on Android, the desktop and the web.
 */
object MiniBlur {

    /** The long side the blurred copy is made at; drawn stretched with bilinear filtering past it. */
    private const val TARGET = 96

    /** The blur radius as a share of the long side: soft enough to hide the preview's blocks. */
    private const val RADIUS_SHARE = 0.06f

    fun soften(source: ImageBitmap): ImageBitmap {
        val w = source.width
        val h = source.height
        if (w <= 0 || h <= 0) return source
        val pixels = IntArray(w * h)
        source.readPixels(pixels)
        val scale = TARGET.toFloat() / maxOf(w, h)
        val outW = (w * scale).roundToInt().coerceAtLeast(1)
        val outH = (h * scale).roundToInt().coerceAtLeast(1)
        val channels = Array(3) { FloatArray(outW * outH) }
        // Bilinear upscale into float channels.
        for (y in 0 until outH) {
            val sy = ((y + 0.5f) / scale - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = sy.toInt()
            val y1 = minOf(y0 + 1, h - 1)
            val fy = sy - y0
            for (x in 0 until outW) {
                val sx = ((x + 0.5f) / scale - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = sx.toInt()
                val x1 = minOf(x0 + 1, w - 1)
                val fx = sx - x0
                val a = pixels[y0 * w + x0]
                val b = pixels[y0 * w + x1]
                val c = pixels[y1 * w + x0]
                val d = pixels[y1 * w + x1]
                for (ch in 0 until 3) {
                    val shift = 16 - ch * 8
                    val top = (a shr shift and 0xFF) * (1 - fx) + (b shr shift and 0xFF) * fx
                    val bottom = (c shr shift and 0xFF) * (1 - fx) + (d shr shift and 0xFF) * fx
                    channels[ch][y * outW + x] = top * (1 - fy) + bottom * fy
                }
            }
        }
        val radius = (maxOf(outW, outH) * RADIUS_SHARE).roundToInt().coerceAtLeast(1)
        val scratch = FloatArray(maxOf(outW, outH))
        for (channel in channels) {
            repeat(3) {
                boxPass(channel, outW, outH, radius, horizontal = true, scratch)
                boxPass(channel, outW, outH, radius, horizontal = false, scratch)
            }
        }
        // Handed over as one array: drawn a pixel at a time, as 1.28.0 did, the copy took five
        // thousand draw calls per card, which a television stick felt across a whole Home.
        val argb = IntArray(outW * outH) { i ->
            val r = channels[0][i].roundToInt().coerceIn(0, 255)
            val g = channels[1][i].roundToInt().coerceIn(0, 255)
            val b = channels[2][i].roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return imageFromPixels(argb, outW, outH)
    }

    /** One box blur pass along rows or columns, the edges held at their own colour. */
    private fun boxPass(data: FloatArray, w: Int, h: Int, r: Int, horizontal: Boolean, line: FloatArray) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        val span = 2 * r + 1
        for (l in 0 until lines) {
            fun at(i: Int): Float {
                val k = i.coerceIn(0, length - 1)
                return if (horizontal) data[l * w + k] else data[k * w + l]
            }
            var sum = 0f
            for (i in -r..r) sum += at(i)
            for (i in 0 until length) {
                line[i] = sum / span
                sum += at(i + r + 1) - at(i - r)
            }
            for (i in 0 until length) {
                if (horizontal) data[l * w + i] = line[i] else data[i * w + l] = line[i]
            }
        }
    }
}
