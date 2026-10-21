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
