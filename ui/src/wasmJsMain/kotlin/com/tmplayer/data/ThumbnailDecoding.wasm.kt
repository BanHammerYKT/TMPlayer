package com.tmplayer.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise
import kotlinx.coroutines.await
import org.jetbrains.skia.Image

// The browser's half of [Thumbnails], as the desktop's (ThumbnailDecoding.kt in jvmMain): Skia
// decodes the JPEGs at full size. TDLib's files are tdweb's, read by their id rather than by a
// path on a disk; a provider's poster is one of the build's in-memory files (java.io.File).

@JsFun("(id, priority) => window.TmTd.readBase64(id, priority)")
private external fun tdReadBase64(fileId: Int, priority: Int): Promise<JsString>

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

@Suppress("UNUSED_PARAMETER")
internal fun decodeImageFile(path: String, maxWidth: Int, alpha: Boolean = false): ImageBitmap? =
    decodeImage(java.io.File(path).readBytes())

@OptIn(ExperimentalEncodingApi::class)
@Suppress("UNUSED_PARAMETER")
internal suspend fun decodeTdImage(fileId: Int, path: String, maxWidth: Int): ImageBitmap? {
    val text = tdReadBase64(fileId, THUMBNAIL_READ_PRIORITY).await<JsString>().toString()
    if (text.isEmpty()) return null
    return decodeImage(Base64.Default.decode(text))
}

private const val THUMBNAIL_READ_PRIORITY = 4

internal fun ImageBitmap.byteCount(): Int = width * height * 4

internal const val decodesFullSize: Boolean = true

internal fun sharperUrl(url: String, pixels: Int): String = com.tmplayer.online.MetaImageSize.atLeast(url, pixels)

internal val artFilter: androidx.compose.ui.graphics.FilterQuality = androidx.compose.ui.graphics.FilterQuality.Medium
