package com.tmplayer.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.dp
import com.tmplayer.data.PerfLog
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.tmplayer.data.Thumbnails
import com.tmplayer.data.decodesFullSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.layout.onSizeChanged
import com.tmplayer.online.MetaImageSize
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.theme.Motion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * A large picture behind a hero or a page, from the best art a video has.
 *
 * The chain, best first: the online wide picture ([wideUrl], a backdrop or an episode's still,
 * fetched at the provider's size of at least [ONLINE_WIDTH] on every platform, since the metadata
 * names a w780 backdrop that a television stretches soft across its width); the full Telegram thumbnail; the inline preview, which
 * stretched this far is already a blur; and last the video's [GeneratedCover]. Each step fades
 * into the next over [Motion.Normal], so a better picture arriving never pops.
 *
 * Telegram's thumbnails are 320 pixels wide at best, and one stretched across a television is a
 * smear of blocks. So a picture under [SHARP_MIN_WIDTH] is not stretched sharp: the frame gets
 * [Thumbnails.backdrop], a 32 pixel shrink of it drawn across the whole frame with bilinear
 * filtering, which is a soft field of its colours, and the picture itself sits over it at its own
 * shape, at the top end of the frame, fading into the field at its start and its foot. The art
 * reads as art rather than as a blur of pixels, and there is no `blur()` anywhere in it.
 *
 * [alignment] places a picture cropped to a frame wider than its own shape: a frame much wider
 * than tall keeps the picture's top, where the people usually are, with [Alignment.TopCenter].
 *
 * A device short of memory draws the same chain: the online picture is a w780 backdrop, about
 * 0.7 MB decoded, and drawing the field alone there left the carousel a blur of colour on a 1 GB
 * television stick. The scrims that text needs over all of this are the caller's ([com.tmplayer.ui.theme.Scrims]).
 */
@Composable
fun ArtBackdrop(
    source: BackdropSource,
    modifier: Modifier = Modifier,
    alignment: Alignment = Alignment.Center,
) {
    var drawn by remember { mutableIntStateOf(0) }
    val art = rememberBackdropArt(source, drawn)

    Box(modifier.onSizeChanged { drawn = it.width }) {
        // From nothing too, so the first picture fades in rather than popping.
        Crossfade(targetState = art, animationSpec = Motion.standard(), label = "artBackdrop") { step ->
            when {
                step == null -> Unit
                step.picture != null && step.field == null -> Image(
                    bitmap = step.picture,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alignment = alignment,
                    filterQuality = Thumbnails.drawFilter,
                    modifier = Modifier.fillMaxSize(),
                )
                step.field != null -> Box(Modifier.fillMaxSize()) {
                    Image(
                        bitmap = step.field,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        filterQuality = FilterQuality.Low,
                        modifier = Modifier.fillMaxSize().then(if (step.blurred) Modifier.blur(STAND_IN_BLUR) else Modifier),
                    )
                    step.picture?.let { SmallPicture(it, Modifier.align(Alignment.TopEnd)) }
                }
                // The colours alone: the cover's big number and chat initial sit in its top
                // corners, where a page's Close or Back floats, and the page already says both.
                else -> GeneratedCover(
                    remember(source.cover) { source.cover.copy(number = null, chatInitial = null) },
                    Modifier.fillMaxSize(),
                    showTitle = false,
                )
            }
        }
    }
}

/**
 * Where an [ArtBackdrop]'s picture comes from: a video's own art, its [cover] for when it has
 * none, and the online [wideUrl] when metadata is on and matched.
 */
@Immutable
data class BackdropSource(
    val miniThumbnail: ByteArray?,
    val thumbnailFileId: Int,
    val cover: Cover,
    val wideUrl: String? = null,
) {
    override fun equals(other: Any?): Boolean =
        other is BackdropSource &&
            other.thumbnailFileId == thumbnailFileId &&
            other.cover == cover &&
            other.wideUrl == wideUrl &&
            (other.miniThumbnail?.contentEquals(miniThumbnail) ?: (miniThumbnail == null))

    override fun hashCode(): Int =
        ((thumbnailFileId * 31 + cover.hashCode()) * 31 + wideUrl.hashCode()) * 31 + (miniThumbnail?.contentHashCode() ?: 0)
}


/**
 * The chain [ArtBackdrop] and [EdgeBackdrop] draw, best first, as it arrives: each step replaces
 * the one before. [drawn] is the frame's measured width in pixels, which the desktop fetches for.
 */
@Composable
internal fun rememberBackdropArt(source: BackdropSource, drawn: Int): BackdropArt? {
    val online = OnlineMetadata.current
    // Where the platform decodes at full size (the desktop), the online picture is fetched at the
    // width the frame is drawn, in steps, and only ever larger: a window being resized does not
    // fetch at every pixel, and a smaller window keeps the sharper copy it has.
    val wanted = if (decodesFullSize) maxOf(ONLINE_WIDTH, (drawn + WIDTH_STEP - 1) / WIDTH_STEP * WIDTH_STEP) else ONLINE_WIDTH
    val peak = remember { intArrayOf(0) }
    peak[0] = maxOf(peak[0], wanted)
    val widest = peak[0]
    var art by remember(source) { mutableStateOf(peek(source, widest)) }
    LaunchedEffect(source, widest) {
        // Not until the frame has been measured, so the first fetch is the size that is drawn.
        if (decodesFullSize && drawn == 0) return@LaunchedEffect
        val started = System.nanoTime()
        val wide = source.wideUrl?.let { url ->
            Thumbnails.peekSharp(url, widest, ONLINE_WIDTH, MetaImageSize::atLeast)
                ?.takeIf { it.width >= widest || MetaImageSize.atLeast(url, widest) == url }
                ?: online?.let { provider ->
                    kotlinx.coroutines.coroutineScope {
                        // While the sharp copy comes, something of the same picture: the inline
                        // preview at once, then TMDB's small copy blurred (a few kilobytes, one
                        // round trip), which the sharp one fades in over. Not when the sharp copy
                        // is on the disk already: it is decoded before the small one could land.
                        val early = launch {
                            if (art == null) {
                                withContext(Dispatchers.Default) { Thumbnails.mini(source.miniThumbnail) }?.let { if (art == null) art = BackdropArt(field = it) }
                            }
                            val tiny = MetaImageSize.tiny(url) ?: return@launch
                            val onDisk = withContext(Dispatchers.IO) {
                                provider.hasImage(url) || provider.hasImage(MetaImageSize.atLeast(url, Thumbnails.lighter(widest)))
                            }
                            if (onDisk) return@launch
                            Thumbnails.online(tiny, TINY_WIDTH) { provider.image(tiny) }?.let {
                                art = BackdropArt(field = it, blurred = true)
                                PerfLog.mark("backdrop small", started)
                            }
                        }
                        Thumbnails.sharp(url, widest, ONLINE_WIDTH, MetaImageSize::atLeast) { provider.image(it) }
                            .also { early.cancel() }
                    }
                }
        }
        if (wide != null && wide.width >= SHARP_MIN_WIDTH) {
            if (art != null) PerfLog.mark("backdrop sharp", started)
            art = BackdropArt(picture = wide)
            return@LaunchedEffect
        }
        // Until the thumbnail is in, the inline preview stretched is the backdrop.
        if (art == null) {
            withContext(Dispatchers.Default) { Thumbnails.mini(source.miniThumbnail) }?.let { art = BackdropArt(field = it) }
        }
        val full = Thumbnails.full(source.thumbnailFileId) ?: wide
        val field = Thumbnails.backdrop(source.thumbnailFileId, source.miniThumbnail)
        art = when {
            full != null && full.width >= SHARP_MIN_WIDTH -> BackdropArt(picture = full)
            full != null -> BackdropArt(picture = full, field = field)
            field != null -> BackdropArt(field = field)
            else -> art ?: BackdropArt()
        }
    }

    return art
}

/** What the backdrop draws at one step of the chain. Neither set means the generated cover. */
internal class BackdropArt(
    val picture: ImageBitmap? = null,
    val field: ImageBitmap? = null,
    /** True for the provider's small copy standing in for the sharp one: drawn blurred, so it reads as the picture coming into focus. */
    val blurred: Boolean = false,
)

/** The blur on a small stand-in; Android draws it from API 31, and earlier the stretched copy is soft enough. */
internal val STAND_IN_BLUR = 18.dp

/** The width TMDB's small stand-in copies are decoded at: `w92` posters whole, `w300` backdrops halved. */
internal const val TINY_WIDTH = 160

/** What is already decoded for [source], so a backdrop seen before is there on the first frame. */
private fun peek(source: BackdropSource, pixels: Int): BackdropArt? {
    source.wideUrl?.let { url -> Thumbnails.peekSharp(url, pixels, ONLINE_WIDTH, MetaImageSize::atLeast)?.let { return BackdropArt(picture = it) } }
    val full = Thumbnails.peekFull(source.thumbnailFileId) ?: return null
    return if (full.width >= SHARP_MIN_WIDTH) BackdropArt(picture = full) else null
}

/**
 * A small picture over its own soft field: at its own shape, [SMALL_PICTURE_SHARE] of the frame's
 * width or [SMALL_PICTURE_SCALE] times its own pixels, whichever is less (and less again where
 * the frame is too short for that), against the top end, faded out towards the
 * start and the foot so it has no edge against the field. The start of the frame, where a hero
 * sets its text, is the field alone.
 */
@Composable
private fun SmallPicture(picture: ImageBitmap, modifier: Modifier) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Never more than [SMALL_PICTURE_SCALE] times its own pixels: Telegram's 320 pixel thumbnail
    // at 65 per cent of a television's carousel was blown up three times and read as a blur.
    val widest = with(LocalDensity.current) { (picture.width * SMALL_PICTURE_SCALE).toDp() }
    Image(
        bitmap = picture,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        filterQuality = FilterQuality.Low,
        modifier = modifier
            .widthIn(max = widest)
            .fillMaxWidth(SMALL_PICTURE_SHARE)
            .aspectRatio(picture.width.toFloat() / picture.height.coerceAtLeast(1))
            // Drawn into its own layer, so the fades below cut the picture away rather than
            // painting black over it.
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .drawWithContent {
                drawContent()
                val across = if (rtl) {
                    arrayOf(0f to Color.Black, 1f - PICTURE_FADE to Color.Black, 1f to Color.Transparent)
                } else {
                    arrayOf(0f to Color.Transparent, PICTURE_FADE to Color.Black, 1f to Color.Black)
                }
                drawRect(Brush.horizontalGradient(*across), blendMode = BlendMode.DstIn)
                drawRect(
                    Brush.verticalGradient(0f to Color.Black, 1f - PICTURE_FADE to Color.Black, 1f to Color.Transparent),
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

/**
 * How wide the online picture is decoded for a backdrop on Android, a hero's width on a 1080p
 * screen, and the least the desktop fetches.
 */
private const val ONLINE_WIDTH = 1280

/** The steps the desktop's measured width is rounded up to before it picks a picture. */
private const val WIDTH_STEP = 320

/**
 * Below this many pixels across, a picture is too small to stretch over a backdrop sharp and
 * sits over its own soft field instead.
 */
internal const val SHARP_MIN_WIDTH = 480

/** How much of the frame's width a small picture takes, at most. */
private const val SMALL_PICTURE_SHARE = 0.65f

/** How many times its own width a small picture may be drawn, at most, before it reads as a blur. */
private const val SMALL_PICTURE_SCALE = 2f

/** The share of a small picture's width and height that fades into the field. */
private const val PICTURE_FADE = 0.35f
