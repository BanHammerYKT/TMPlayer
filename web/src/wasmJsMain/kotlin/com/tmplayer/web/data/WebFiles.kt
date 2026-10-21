package com.tmplayer.web.data

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.tmplayer.web.js.TmTd
import com.tmplayer.web.js.text
import com.tmplayer.web.td.WebTd
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.jetbrains.skia.Image

/**
 * Small pictures (thumbnails, chat photos) fetched through TDLib and decoded with Skia, kept in
 * a small LRU so a grid scrolled back over does not download them again. Video bytes never come
 * this way: the player reads those in JavaScript.
 */
class WebFiles(private val td: WebTd, private val scope: CoroutineScope) {
    private val gate = Semaphore(6)
    private val cache = LinkedHashMap<Int, ImageBitmap>()
    private val pending = HashMap<Int, CompletableDeferred<ImageBitmap?>>()
    private val failed = HashSet<Int>()

    /** The picture in file [fileId], downloading it first if TDLib does not have it. */
    suspend fun image(fileId: Int, priority: Int = 8): ImageBitmap? {
        if (fileId == 0 || fileId in failed) return null
        cache[fileId]?.let {
            cache.remove(fileId)
            cache[fileId] = it
            return it
        }
        pending[fileId]?.let { return it.await() }
        val wait = CompletableDeferred<ImageBitmap?>()
        pending[fileId] = wait
        val result = gate.withPermit { load(fileId, priority) }
        pending.remove(fileId)
        if (result == null) failed += fileId else put(fileId, result)
        wait.complete(result)
        return result
    }

    fun cached(fileId: Int): ImageBitmap? = cache[fileId]

    /** A minithumbnail, the blurred 40 px preview TDLib sends inline as base64. */
    @OptIn(ExperimentalEncodingApi::class)
    fun decodeInline(base64: String): ImageBitmap? {
        if (base64.isEmpty()) return null
        return runCatching { Image.makeFromEncoded(Base64.decode(base64)).toComposeImageBitmap() }.getOrNull()
    }

    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun load(fileId: Int, priority: Int): ImageBitmap? {
        val b64 = TmTd.readBase64(fileId, priority).text()
        if (b64.isEmpty()) return null
        return runCatching { Image.makeFromEncoded(Base64.decode(b64)).toComposeImageBitmap() }.getOrNull()
    }

    private fun put(fileId: Int, image: ImageBitmap) {
        cache[fileId] = image
        while (cache.size > MAX) cache.remove(cache.keys.first())
    }

    fun clear() {
        cache.clear()
        failed.clear()
    }

    private companion object {
        const val MAX = 400
    }
}
