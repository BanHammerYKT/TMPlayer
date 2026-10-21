package com.tmplayer.ui.auth

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Renders a Telegram login link as a QR code.
 *
 * Deliberately plain black-on-white: phone cameras across a living room read that far more
 * reliably than anything tinted to match the app. The modules come from :core's own encoder
 * ([com.tmplayer.data.QrCode], which QrCodeTest reads back with ZXing), at error correction M
 * with a one module margin, so the phone, the desktop and the browser draw the same code; only
 * turning the pixels into an [ImageBitmap] is each platform's (`QrBitmap.kt`).
 */
object QrCode {

    fun render(text: String, sizePx: Int): ImageBitmap? = runCatching {
        val code = com.tmplayer.data.QrCode.encode(text, com.tmplayer.data.QrCode.Ecc.Medium) ?: return@runCatching null
        val modules = code.size + MARGIN * 2
        val scale = (sizePx / modules).coerceAtLeast(1)
        val side = modules * scale
        val pixels = IntArray(side * side) { WHITE }
        for (y in 0 until code.size) for (x in 0 until code.size) {
            if (!code[x, y]) continue
            val left = (x + MARGIN) * scale
            val top = (y + MARGIN) * scale
            for (dy in 0 until scale) {
                val row = (top + dy) * side
                for (dx in 0 until scale) pixels[row + left + dx] = BLACK
            }
        }
        opaqueImage(pixels, side, side)
    }.getOrNull()

    private const val MARGIN = 1
    private const val BLACK = 0xFF000000.toInt()
    private const val WHITE = 0xFFFFFFFF.toInt()
}
