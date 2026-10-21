package com.tmplayer.ui.online

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.animation.core.Animatable
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.tmplayer.online.MetaImageSize
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Thumbnails
import com.tmplayer.i18n.Translator
import com.tmplayer.online.MetaEpisode
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.MetaKind
import com.tmplayer.online.MetaQuery
import com.tmplayer.online.MetaResult
import com.tmplayer.online.MetaWords
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * Posters and overviews in Compose, shared by the phone, the television and the desktop. Every
 * piece here draws nothing at all while lookups are off, before an answer arrives, or when nothing
 * matched, so whatever it stands in front of (the video's own thumbnail, the caption) is what
 * shows in all of those cases.
 */

/**
 * True where a video tile may trade its thumbnail for the show's or film's picture: Home's rows.
 * A chat's own grid keeps the frames, since a season of episodes all wearing one backdrop could
 * not be told apart.
 */
val LocalOnlineArt = compositionLocalOf { false }

/**
 * What the providers say about the video named [fileName], or null. [showOnly] asks for the show
 * alone, which is what a tile needs, and shares one lookup between all of a show's episodes.
 */
@Composable
fun rememberMeta(fileName: String, caption: String?, showOnly: Boolean = false): MetaInfo? {
    val found = rememberLookup(fileName, caption, showOnly) ?: return null
    // Only an answer the name backs with a year or an episode code: with no file to measure,
    // the runtime cannot vouch for it here (see [rememberMatched]).
    val name = remember(fileName, caption) { com.tmplayer.data.MediaName.parse(fileName, caption) }
    return found.takeIf { com.tmplayer.online.MetaConfidence.basis(name, it) != null }
}

/**
 * What the providers say about [item], with what backs it ([com.tmplayer.online.MatchBasis]), or
 * null: lookups off, nothing found, a match nothing but the title backs ([OnlineMetadata.matched]),
 * or one the viewer turned down ("Wrong match"). A title the viewer gave the video is what is
 * looked up.
 */
@Composable
fun rememberMatched(item: MediaItem, showOnly: Boolean = false): com.tmplayer.online.MatchedMeta? {
    val corrections by com.tmplayer.data.VideoCorrections.state.collectAsState()
    val correction = corrections[item.id]
    val renamed = correction?.title
    val found = rememberLookup(renamed ?: item.nameForParsing, if (renamed != null) null else item.caption, showOnly)
    val name = remember(item, renamed) { com.tmplayer.data.SeriesShelf.episodeOf(item, corrections) }
    val quick = found?.let { info -> com.tmplayer.online.MetaConfidence.basis(name, info)?.let { com.tmplayer.online.MatchedMeta(info, it) } }
    val matched by produceState(quick, found, name, item.durationSec) {
        if (value == null && found != null && com.tmplayer.online.MetaConfidence.needsRuntime(name, found, item.durationSec)) {
            value = withContext(Dispatchers.IO) { OnlineMetadata.current?.matched(item, name, found) }
        }
    }
    if (correction?.wrongMatch == true) return null
    return matched?.takeIf { it.info == found }
}

/** The provider's answer for a name, before anything checks that it is the right one. */
@Composable
private fun rememberLookup(fileName: String, caption: String?, showOnly: Boolean): MetaInfo? {
    val online = OnlineMetadata.current ?: return null
    val settings by online.settings.collectAsState()
    if (!settings.enabled) return null
    val language by Translator.active.collectAsState()
    val query = remember(fileName, caption, showOnly) {
        MetaQuery.of(fileName, caption)?.let { if (showOnly) it.showOnly() else it }
    } ?: return null
    val known = remember(query, language.tag, settings) { (online.peek(query) as? MetaResult.Found)?.info }
    val found by produceState(known, query, language.tag, settings.ownKey) {
        if (value == null) value = (withContext(Dispatchers.IO) { online.lookup(query) } as? MetaResult.Found)?.info
    }
    return found
}

/**
 * [rememberMeta] for a Telegram video: its file name (or the title the viewer gave it), and its
 * caption, gated as [rememberMatched] gates it.
 */
@Composable
fun rememberMeta(item: MediaItem, showOnly: Boolean = false): MetaInfo? =
    rememberMatched(item, showOnly)?.info

/**
 * The provider's episode list for [season] of the show [meta], for the names in a series list:
 * null while lookups are off, for a film, before the answer arrives, or when nobody could be
 * asked. Shares [OnlineMetadata.season]'s memory and cache with the detail page's episode guide.
 */
@Composable
fun rememberSeasonEpisodes(meta: MetaInfo?, season: Int): List<MetaEpisode>? {
    val online = OnlineMetadata.current ?: return null
    if (meta == null || meta.kind != MetaKind.Show) return null
    val listed by produceState(online.peekSeason(meta, season), meta, season) {
        if (value == null) value = withContext(Dispatchers.IO) { online.season(meta, season) }
    }
    return listed
}

/**
 * The picture at [url], once it is on the disk and decoded; nothing until then.
 *
 * At the size it is drawn: its width in pixels on the screen (the interface size and the screen's
 * density both in it) picks the provider's copy where the platform can use a larger one
 * ([Thumbnails.sharp], the desktop), so a poster in a wide window is not a small copy stretched.
 * A copy already decoded stays up while a larger one is fetched, so a resize never blanks it.
 */
@Composable
fun MetaPicture(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    maxWidth: Int = 480,
    contentDescription: String? = null,
) {
    if (url == null) return
    val online = OnlineMetadata.current ?: return
    var drawn by remember { mutableIntStateOf(0) }
    // Android samples every picture to [maxWidth] whatever it is drawn at, so only the desktop measures.
    val wanted = if (com.tmplayer.data.decodesFullSize) maxOf(maxWidth, drawn) else maxWidth
    var bitmap by remember(url) { mutableStateOf(Thumbnails.peekSharp(url, wanted, maxWidth)) }
    // TMDB's small copy, blurred, while the sharp one comes: the picture is there in one short
    // round trip and comes into focus rather than popping in late (user, 2026-10-21).
    val tinyUrl = remember(url) { MetaImageSize.tiny(url) }
    var tiny by remember(url) { mutableStateOf(if (bitmap == null && tinyUrl != null) Thumbnails.peekOnline(tinyUrl, TINY_PICTURE_WIDTH) else null) }
    // Already decoded when the tile was composed: shown sharp at once, no fade.
    val reveal = remember(url) { Animatable(if (bitmap != null) 1f else 0f) }
    val still = com.tmplayer.ui.theme.rememberReducedMotion() || com.tmplayer.ui.theme.Motion.reduced
    LaunchedEffect(url, wanted) {
        val have = bitmap
        if (have != null && have.width >= wanted) return@LaunchedEffect
        val started = System.nanoTime()
        coroutineScope {
            val early = if (have == null && tiny == null && tinyUrl != null) {
                launch {
                    // Not when the sharp copy is on the disk: it decodes before the small one could land.
                    val onDisk = withContext(Dispatchers.IO) {
                        online.hasImage(url) || online.hasImage(MetaImageSize.atLeast(url, Thumbnails.lighter(wanted)))
                    }
                    if (onDisk) return@launch
                    Thumbnails.online(tinyUrl, TINY_PICTURE_WIDTH) { online.image(tinyUrl) }?.let {
                        if (bitmap == null) tiny = it
                        PicturePerf.note("poster small", started)
                    }
                }
            } else {
                null
            }
            Thumbnails.sharp(url, wanted, maxWidth) { online.image(it) }?.let {
                early?.cancel()
                bitmap = it
                if (have == null) PicturePerf.note(if (tiny != null) "poster sharp after small" else "poster sharp", started)
            }
        }
    }
    val shown = bitmap
    LaunchedEffect(shown != null) {
        if (shown == null || reveal.value >= 1f) return@LaunchedEffect
        if (still) reveal.snapTo(1f) else reveal.animateTo(1f, androidx.compose.animation.core.tween(REVEAL_MS))
    }
    Box(modifier.onSizeChanged { drawn = it.width }) {
        val small = tiny
        if (small != null && reveal.value < 1f) {
            Image(
                small,
                contentDescription = null,
                modifier = Modifier.matchParentSize().blur(TINY_BLUR),
                contentScale = contentScale,
                alignment = alignment,
                filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
            )
        }
        if (shown != null) {
            val focus = reveal.value
            Image(
                shown,
                contentDescription = contentDescription,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer { alpha = focus }
                    // The blur eases out as the sharp copy fades in, so it comes into focus.
                    .then(if (focus < 1f && small != null) Modifier.blur(TINY_BLUR * (1f - focus)) else Modifier),
                contentScale = contentScale,
                alignment = alignment,
                filterQuality = Thumbnails.drawFilter,
            )
        }
    }
}

/** The blur on a small copy standing in for the sharp one. Android draws it from API 31; earlier the stretched copy is soft on its own. */
private val TINY_BLUR = 12.dp

/** How wide TMDB's small copies are decoded: `w92` whole, a `w300` still halved. */
private const val TINY_PICTURE_WIDTH = 160

/** How long the sharp copy takes to come into focus over the small one. */
private const val REVEAL_MS = 380

/** The first few pictures' timings, for a device pass to read in the log; past that they would only be noise. */
private object PicturePerf {
    private val left = java.util.concurrent.atomic.AtomicInteger(16)

    fun note(what: String, since: Long) {
        if (left.getAndDecrement() > 0) com.tmplayer.data.PerfLog.mark(what, since)
    }
}

/**
 * A video tile's picture: [content] (the thumbnail, as a rule) with the show's or film's wide
 * picture over it once it arrives, where [LocalOnlineArt] allows it. Never the poster: every tile
 * is 16:9, and the poster belongs to the detail panel.
 */
@Composable
fun OnlineArt(item: MediaItem, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (!LocalOnlineArt.current) {
        Box(modifier) { content() }
        return
    }
    val meta = rememberMeta(item, showOnly = true)
    Box(modifier) {
        content()
        // The wide picture only. A 2:3 poster cropped into a 16:9 tile is a band across its middle,
        // so without a wide one the tile keeps the video's own frame.
        MetaPicture(meta?.wideUrl, Modifier.fillMaxSize())
    }
}

/** "From TMDB" (or TVmaze, with its licence) under the words it credits. */
@Composable
fun MetaCredit(info: MetaInfo, modifier: Modifier = Modifier) {
    Text(
        MetaWords.credit(info.provider),
        style = MaterialTheme.typography.labelSmall,
        color = Tone.muted,
        modifier = modifier,
    )
}
