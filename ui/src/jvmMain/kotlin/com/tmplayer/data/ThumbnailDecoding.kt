package com.tmplayer.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Image
import java.io.File

// The desktop's half of [Thumbnails]: Skia decodes the JPEGs TDLib hands over.

/** An opaque picture from [argb], one Int per pixel, row by row ([MiniBlur]). */
internal fun imageFromPixels(argb: IntArray, width: Int, height: Int): ImageBitmap {
    val bytes = ByteArray(argb.size * 4)
    for (i in argb.indices) {
        val p = argb[i]
        bytes[i * 4] = (p shr 16).toByte()
        bytes[i * 4 + 1] = (p shr 8).toByte()
        bytes[i * 4 + 2] = p.toByte()
        bytes[i * 4 + 3] = 0xFF.toByte()
    }
    val info = org.jetbrains.skia.ImageInfo(width, height, org.jetbrains.skia.ColorType.RGBA_8888, org.jetbrains.skia.ColorAlphaType.OPAQUE)
    return Image.makeRaster(info, bytes, width * 4).toComposeImageBitmap()
}

internal fun decodeImage(data: ByteArray): ImageBitmap? =
    runCatching { Image.makeFromEncoded(data).toComposeImageBitmap() }.getOrNull()

/**
 * Full size. Telegram's thumbnails are already a few hundred pixels across, and the desktop has
 * the memory a television does not, so there is nothing to sample down.
 */
@Suppress("UNUSED_PARAMETER")
internal fun decodeImageFile(path: String, maxWidth: Int, alpha: Boolean = false): ImageBitmap? =
    decodeImage(File(path).readBytes())

internal fun ImageBitmap.byteCount(): Int = width * height * 4

/**
 * The desktop decodes every picture at its full size ([decodeImageFile]), so one decode serves
 * every width it is drawn at and the cache keys an online picture by its address alone.
 */
internal const val decodesFullSize: Boolean = true

/**
 * A provider's larger copy of [url] for a picture drawn [pixels] wide: a desktop window draws
 * posters and the Home carousel far wider than the copies the metadata names.
 */
internal fun sharperUrl(url: String, pixels: Int): String = com.tmplayer.online.MetaImageSize.atLeast(url, pixels)

/**
 * How a picture is sampled when it is drawn at another size: with mipmaps, so a large poster
 * drawn smaller stays smooth instead of shimmering, and bilinear when it is drawn larger.
 */
internal val artFilter: androidx.compose.ui.graphics.FilterQuality = androidx.compose.ui.graphics.FilterQuality.Medium

/** TDLib's file [fileId], downloaded to [path], decoded as [decodeImageFile] does. */
@Suppress("UNUSED_PARAMETER")
internal suspend fun decodeTdImage(fileId: Int, path: String, maxWidth: Int): ImageBitmap? = decodeImageFile(path, maxWidth)
