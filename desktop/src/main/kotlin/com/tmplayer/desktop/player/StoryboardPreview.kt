package com.tmplayer.desktop.player

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.tmplayer.data.StoryboardFiles
import com.tmplayer.data.StoryboardMap
import com.tmplayer.data.StoryboardMaps
import com.tmplayer.data.StoryboardTile
import com.tmplayer.data.Thumbnails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Telegram's storyboard for the video playing (CP80), for the timebar's hover: the sprite is
 * fetched through TDLib and decoded once, the first time the pointer rests on the bar, and each
 * tile is drawn straight out of it. The desktop had no scrub pictures before; a storyboard is a
 * few dozen kilobytes, so there is no need for the video's own bytes or a second decoder.
 */
class DesktopStoryboard(private val files: StoryboardFiles, private val durationMs: () -> Long) {

    private val gate = Mutex()
    private var sprite: ImageBitmap? = null
    private var map: StoryboardMap? = null
    private var failed = false

    /** The sprite and the tile for [positionMs], or null while it cannot be had. */
    suspend fun tileAt(positionMs: Long): Pair<ImageBitmap, StoryboardTile>? {
        val (image, tiles) = load() ?: return null
        val tile = tiles.tileAt(positionMs) ?: return null
        if (tile.x + tile.width > image.width || tile.y + tile.height > image.height) return null
        return image to tile
    }

    private suspend fun load(): Pair<ImageBitmap, StoryboardMap>? = gate.withLock {
        val ready = sprite
        val tiles = map
        if (ready != null && tiles != null) return ready to tiles
        if (failed) return null
        // Not fetched this time (offline, slow): asked again on the next hover.
        val imagePath = Thumbnails.file(files.imageFileId) ?: return null
        val mapPath = Thumbnails.file(files.mapFileId)
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = File(imagePath).readBytes()
                val image = org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
                val text = mapPath?.let { runCatching { File(it).readText() }.getOrNull() }.orEmpty()
                image to StoryboardMaps.parse(text, files.tileWidth, files.tileHeight, image.width, image.height, durationMs())
            }.getOrNull()
        }
        if (loaded == null || loaded.second.isEmpty) {
            failed = true
            return null
        }
        sprite = loaded.first
        map = loaded.second
        loaded
    }
}

/** The storyboard of the video playing, for [StoryboardTip]; null where it has none. */
internal val LocalStoryboard = staticCompositionLocalOf<DesktopStoryboard?> { null }

/**
 * The tile for [positionMs] over the timebar, in a frame like the chips', or nothing while the
 * storyboard is still coming. Asked again only when the tile under the pointer changes.
 */
@Composable
internal fun StoryboardTip(board: DesktopStoryboard, positionMs: Long, modifier: Modifier = Modifier) {
    val shown by produceState<Pair<ImageBitmap, StoryboardTile>?>(null, board, positionMs / TILE_STEP_MS) {
        value = board.tileAt(positionMs) ?: value
    }
    val (image, tile) = shown ?: return
    val shape = RoundedCornerShape(6.dp)
    val height = TIP_WIDTH * tile.height / tile.width.coerceAtLeast(1)
    Canvas(
        modifier
            .size(TIP_WIDTH, height)
            .clip(shape)
            .border(1.5.dp, Color.White.copy(alpha = 0.85f), shape),
    ) {
        drawImage(
            image,
            srcOffset = IntOffset(tile.x, tile.y),
            srcSize = IntSize(tile.width, tile.height),
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
}

/**
 * The video's own frame at [positionMs] for the hover, from [frames] (the engine's second decoder,
 * [PlaybackEngine.previewFrame]), where there is no storyboard. Asked once per ten seconds of the
 * video, after the pointer has rested a moment, and kept; the last picture stays up while the
 * next is decoded, and none shows where that part of the video is not on the disk yet.
 */
@Composable
internal fun FrameTip(frames: FrameSource, positionMs: Long, modifier: Modifier = Modifier) {
    val slot = positionMs / FRAME_STEP_MS
    val shown by produceState<ImageBitmap?>(frames.cached(slot), frames, slot) {
        frames.cached(slot)?.let { value = it; return@produceState }
        kotlinx.coroutines.delay(FRAME_REST_MS)
        frames.frameAt(slot)?.let { value = it }
    }
    val image = shown ?: return
    val shape = RoundedCornerShape(6.dp)
    val height = TIP_WIDTH * image.height / image.width.coerceAtLeast(1)
    Canvas(
        modifier
            .size(TIP_WIDTH, height)
            .clip(shape)
            .border(1.5.dp, Color.White.copy(alpha = 0.85f), shape),
    ) {
        drawImage(image, dstSize = IntSize(size.width.toInt(), size.height.toInt()))
    }
}

/** The hover's frames for one video, by ten second slot, kept in a small cache. */
internal class FrameSource(private val fetch: suspend (Long) -> ImageBitmap?) {
    private val cache = com.tmplayer.platform.LruMap<Long, ImageBitmap>(FRAME_CACHE)
    private val gate = Mutex()

    fun cached(slot: Long): ImageBitmap? = cache[slot]

    suspend fun frameAt(slot: Long): ImageBitmap? = gate.withLock {
        cached(slot) ?: fetch(slot * FRAME_STEP_MS + FRAME_STEP_MS / 2)?.also { cache[slot] = it }
    }
}

internal val LocalFrameSource = staticCompositionLocalOf<FrameSource?> { null }

private const val FRAME_STEP_MS = 10_000L
private const val FRAME_REST_MS = 120L
private const val FRAME_CACHE = 48

/** How wide the hover's picture is drawn. */
internal val TIP_WIDTH = 160.dp

/** Pointer moves inside one step of the video ask for no new tile. */
private const val TILE_STEP_MS = 1_000L
