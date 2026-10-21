package com.tmplayer.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.data.Thumbnails
import com.tmplayer.ui.online.MetaPicture
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Scrims
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The picture on a film's or a show's 2:3 poster card, by the best thing there is:
 *
 * 1. the online poster, when posters and overviews are on and the providers know the title;
 * 2. a portrait Telegram thumbnail, which fills the card as it is;
 * 3. a landscape thumbnail, shown whole across the middle of the card over a soft field of the
 *    same picture ([Thumbnails.backdrop], a 32 px decode stretched, no blur call), so nothing of
 *    the frame is cropped away to make it tall. No name is set on it: every card that wears this
 *    art writes the name under it, and the same words on the picture as well read twice
 *    (user, 2026-10-21);
 * 4. the file's [GeneratedCover], when there is no picture at all. That one keeps the name the
 *    caller's [cover] carries, since the cover is the picture there: a gradient with nothing on it
 *    reads as a card that failed to load. A caller that writes the name beside a small poster
 *    hands a cover with a blank title.
 *
 * The caller clips and sizes it; [posterUrl] is the online poster's address, or null.
 */
@Composable
fun PosterArt(
    miniThumbnail: ByteArray?,
    thumbnailFileId: Int,
    cover: Cover,
    posterUrl: String?,
    modifier: Modifier = Modifier,
    compactCover: Boolean = false,
) {
    // The inline preview first, off the composition thread, then the thumbnail when it arrives.
    val mini by produceState<ImageBitmap?>(null, miniThumbnail) {
        value = withContext(Dispatchers.Default) { Thumbnails.mini(miniThumbnail) }
    }
    var full by remember(thumbnailFileId) { mutableStateOf(Thumbnails.peekFull(thumbnailFileId)) }
    LaunchedEffect(thumbnailFileId) { if (full == null) full = Thumbnails.full(thumbnailFileId) }
    val picture = full ?: mini
    Box(modifier.background(Tone.surface)) {
        Crossfade(targetState = picture, animationSpec = Motion.standard(), label = "posterPicture") { shown ->
            when {
                shown == null -> GeneratedCover(cover, Modifier.fillMaxSize(), compact = compactCover)
                shown.height >= shown.width -> Image(
                    shown,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = Thumbnails.drawFilter,
                    modifier = Modifier.fillMaxSize(),
                )
                else -> WidePicture(shown, miniThumbnail, thumbnailFileId)
            }
        }
        MetaPicture(posterUrl, Modifier.fillMaxSize(), maxWidth = POSTER_WIDTH)
    }
}

/** A landscape picture whole across a tall card, over a soft field of itself. */
@Composable
private fun WidePicture(picture: ImageBitmap, miniThumbnail: ByteArray?, thumbnailFileId: Int) {
    val field by produceState<ImageBitmap?>(null, thumbnailFileId, miniThumbnail) {
        value = Thumbnails.backdrop(thumbnailFileId, miniThumbnail)
    }
    Box(Modifier.fillMaxSize()) {
        Image(
            field ?: picture,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Low,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = FIELD_DIM)))
        Image(
            picture,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            alignment = Alignment.Center,
            filterQuality = Thumbnails.drawFilter,
            modifier = Modifier.fillMaxSize(),
        )
        // The foot darkened as on every tile, for the progress line and a badge there.
        Box(Modifier.fillMaxSize().background(Scrims.tileFoot))
    }
}

/** The online poster's decode width: a card is at most about 180 dp wide. */
private const val POSTER_WIDTH = 342

/** The soft field is dimmed so the sharp picture over it reads as the picture. */
private const val FIELD_DIM = 0.3f
