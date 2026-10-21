package com.tmplayer.ui.auth

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo

/** ARGB pixels with no transparency as a Skia bitmap: N32 is BGRA in memory, an ARGB int little-endian. */
internal fun opaqueImage(pixels: IntArray, width: Int, height: Int): ImageBitmap {
    val bytes = ByteArray(pixels.size * 4)
    for (i in pixels.indices) {
        val p = pixels[i]
        bytes[i * 4] = p.toByte()
        bytes[i * 4 + 1] = (p ushr 8).toByte()
        bytes[i * 4 + 2] = (p ushr 16).toByte()
        bytes[i * 4 + 3] = (p ushr 24).toByte()
    }
    val bitmap = Bitmap()
    bitmap.allocPixels(ImageInfo.makeN32(width, height, ColorAlphaType.OPAQUE))
    bitmap.installPixels(bytes)
    bitmap.setImmutable()
    return org.jetbrains.skia.Image.makeFromBitmap(bitmap).toComposeImageBitmap()
}
