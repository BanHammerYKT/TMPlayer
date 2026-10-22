package com.tmplayer.ui.browse

import com.tmplayer.ui.components.FastScroller
import com.tmplayer.ui.components.keepTopInSight
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import com.tmplayer.ui.theme.trackedFor
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.onPlaced
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import com.tmplayer.ui.components.BackdropSource
import com.tmplayer.ui.components.CoverArt
import com.tmplayer.ui.components.ScrollToTop
import com.tmplayer.ui.components.TileBadgeInset
import com.tmplayer.ui.components.rememberCover
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.online.TrailerIcon
import com.tmplayer.ui.theme.Focus
import com.tmplayer.ui.theme.Scrims
import com.tmplayer.ui.theme.rememberReducedMotion
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.data.CourseProgress
import com.tmplayer.data.EpisodeCopies
import com.tmplayer.data.MediaItem
import com.tmplayer.data.MediaMapper
import com.tmplayer.data.NewEpisodes
import com.tmplayer.data.SeasonDownload
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesEpisode
import com.tmplayer.data.SeriesProgress
import com.tmplayer.data.SeriesSeason
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.VideoCopies
import com.tmplayer.data.WatchPoint
import com.tmplayer.i18n.L
import com.tmplayer.data.copyQuality
import com.tmplayer.data.Watching
import com.tmplayer.player.StreamStats
import com.tmplayer.ui.components.MediaArt
import com.tmplayer.ui.online.MetaCastRow
import com.tmplayer.ui.online.MetaCredit
import com.tmplayer.ui.online.MetaFactsLines
import com.tmplayer.ui.online.MoreLikeThisRow
import com.tmplayer.ui.online.TrailerHost
import com.tmplayer.ui.online.TrailerQr
import com.tmplayer.ui.online.rememberMetaExtras
import com.tmplayer.ui.online.rememberTrailerLauncher
import com.tmplayer.ui.online.MetaPicture
import com.tmplayer.ui.online.rememberMeta
import com.tmplayer.ui.online.rememberSeasonEpisodes
import com.tmplayer.online.EpisodeNames
import com.tmplayer.ui.components.WatchedBadge
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing

/*
 * The series view (R5), shared by the phone, the television and the desktop: a show's picture for
 * its tile, and the panel with the season picker and the episode list. Each platform puts its own chrome round these: a bottom sheet on the phone, a full
 * screen page on the television, and a page with a season dropdown on the desktop.
 */

/**
 * What the panels need to know about watching, read from the platform's stores.
 *
 * [finished] is handed the Watched list; a video also counts as watched once its saved position is
 * past the watched mark (see [Watching.counts]), so a show's count and a course's agree.
 */
class SeriesWatch(
    val point: (MediaItem) -> WatchPoint?,
    finished: (MediaItem) -> Boolean,
    /**
     * When the viewer last watched a video, in milliseconds, or null when nothing says (see
     * [NewEpisodes.touchTimes]). Only the "New" badge reads it; without it no show is new.
     */
    val touchedAt: (MediaItem) -> Long? = { null },
) {
    private val onList = finished
    val finished: (MediaItem) -> Boolean = { item -> Watching.counts(onList(item), point(item)) }

    /** How far through the course [items] make up, or null when they are not one: see [CourseProgress.of]. */
    fun course(items: List<MediaItem>): CourseProgress? = CourseProgress.of(items, onList, point)

    fun progress(series: Series): SeriesProgress =
        SeriesShelf.progress(series, finished) { point(it)?.fraction ?: 0f }

    /** How many of [series]' episodes arrived since the viewer last watched it: see [NewEpisodes.inSeries]. */
    fun newEpisodes(series: Series): Int =
        NewEpisodes.inSeries(series, { finished(it) || (point(it)?.fraction ?: 0f) > 0f }, touchedAt)

    /** Watched when any copy of the episode is, or a file of several episodes that holds it. */
    fun finishedEpisode(episode: SeriesEpisode): Boolean = episode.watched(finished)

    /** Where the viewer stopped in whichever copy of the episode got furthest. */
    fun pointOf(episode: SeriesEpisode): WatchPoint? =
        episode.copies.mapNotNull(point).maxByOrNull { it.fraction }

    /** The copy of [episode] to play: see [SeriesShelf.pick]. */
    fun pick(episode: SeriesEpisode, progress: SeriesProgress): MediaItem =
        SeriesShelf.pick(episode, finished, { point(it)?.fraction ?: 0f }, progress.like)

    /** "Download season" or "Download unwatched" for [season]: see [SeasonDownload.copies]. */
    fun download(series: Series, season: SeriesSeason, which: SeasonDownload.Which) = SeasonDownloadRequest { held ->
        SeasonDownload.copies(series, season, which, finished, { point(it)?.fraction ?: 0f }, held)
    }

    companion object {
        val None = SeriesWatch({ null }, { false })
    }
}

/**
 * A press of "Download season" or "Download unwatched", answered with the copies to queue once the
 * platform has said which are downloaded already or on the queue: that answer is the platform's
 * to read, and on a phone it is a walk of the download records off the drawing thread.
 */
fun interface SeasonDownloadRequest {
    fun copies(held: (MediaItem) -> Boolean): List<MediaItem>
}

/** "2 seasons · 18 eps", the tile's second line. */
@Composable
fun seriesSummary(series: Series): String =
    LocalStrings.current.seriesSummary(series.seasons.size, series.episodeCount)

/** "3 of 18 watched", "All watched", or nothing for a show not yet started. */
@Composable
fun seriesProgressLine(progress: SeriesProgress): String? {
    val s = LocalStrings.current
    return when {
        progress.finished -> s.seriesAllWatched
        progress.watched > 0 -> s.seriesProgress(progress.watched, progress.total)
        else -> null
    }
}

/**
 * The seasons as a row of tabs. On a television the season under the remote is the one shown, so
 * walking Left and Right along the row pages through the seasons and Down enters the episodes.
 */
@Composable
fun SeasonTabs(
    seasons: List<SeriesSeason>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    // Up or Down into the row lands on the season shown, not on whichever tab sits nearest the
    // card the remote left: since the tab under focus is the season shown, landing on the next one
    // would swap the episodes from under the viewer.
    val chosenFocus = focusRequester ?: remember { FocusRequester() }
    Row(
        modifier
            .then(if (tv) Modifier.focusProperties { onEnter = { chosenFocus.requestFocus() } } else Modifier)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        seasons.forEach { season ->
            val interactions = remember { MutableInteractionSource() }
            val focused by interactions.collectIsFocusVisibleAsState()
            val chosen = season.number == selected
            LaunchedEffect(focused) { if (focused && tv) onSelect(season.number) }
            val fill = when {
                focused -> Tone.focusFill
                chosen -> Tone.accent.copy(alpha = 0.18f)
                else -> Tone.surfaceHigh
            }
            Column(
                Modifier
                    .then(if (chosen) Modifier.focusRequester(chosenFocus) else Modifier)
                    .clip(RoundedCornerShape(Corner.Small))
                    .background(fill)
                    .border(
                        if (chosen && !focused) 1.5.dp else 0.dp,
                        if (chosen && !focused) Tone.accent else Color.Transparent,
                        RoundedCornerShape(Corner.Small),
                    )
                    .focusRing(focused, RoundedCornerShape(Corner.Small))
                    .semantics {
                        role = Role.Tab
                        this.selected = chosen
                    }
                    .clickable(interactionSource = interactions, indication = null) { onSelect(season.number) }
                    .padding(horizontal = if (tv) 20.dp else 16.dp, vertical = if (tv) 10.dp else 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    s.seriesSeason(season.number),
                    style = if (tv) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge,
                    fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                    color = when {
                        focused -> Tone.onFocusFill
                        chosen -> Tone.accent
                        else -> Tone.text
                    },
                    maxLines = 1,
                )
                Text(
                    s.seriesEpisodesCount(season.episodes.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (focused) Tone.onFocusFill.copy(alpha = 0.85f) else Tone.muted,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * One episode as a card: its Telegram thumbnail with the progress line and the tick on it, the
 * "S1 E3" label, "Episode 3", the file's own name, and the running time, size and where the
 * viewer stopped.
 *
 * The thumbnail is the one TDLib already has for the message (the inline preview first, then the
 * small thumbnail file), loaded as the row scrolls into a lazy list; nothing of the video itself is
 * fetched for it. An episode posted with no thumbnail gets its generated cover, with its number set
 * large, so a long season of bare files still reads as a numbered set.
 *
 * An episode the chat holds in several copies (350 MB, 600 MB, 1.8 GB) is still one card, which
 * plays [chosen] and names its quality in the line of facts. Which copy is the show's choice, made
 * once for every episode with [QualityPicker] on the show's page and by the player's copy button
 * while watching, not a row of chips under every card.
 */
@Composable
fun EpisodeRow(
    episode: SeriesEpisode,
    point: WatchPoint?,
    finished: Boolean,
    upNext: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    chosen: MediaItem = episode.item,
    /** The episode playing, in the player's episode list: tinted and badged as such. */
    current: Boolean = false,
    /**
     * On a television, holding OK is [onLongClick]: the player's episode list marks an episode
     * watched that way. Off elsewhere, where OK only plays.
     */
    holdOk: Boolean = false,
    /** Hung on the row itself, for focus to land on. */
    lineModifier: Modifier = Modifier,
    /**
     * The episode's name from TMDB or TVmaze ([com.tmplayer.online.EpisodeNames.listName]), or
     * null for the plain "Episode 5". The S1 E5 line above it stays either way.
     */
    name: String? = null,
    /**
     * The episode as the provider lists it ([com.tmplayer.online.EpisodeNames.listed]): its still
     * is drawn over the video's own frame once it arrives, so the provider's picture comes first
     * and the frame only stands where it has none.
     */
    guide: com.tmplayer.online.MetaEpisode? = null,
    /** The provider's few lines about the episode under its facts, on the show's own page. */
    showOverview: Boolean = false,
    /** A large window's show page: a larger picture and type, as the page around it has. */
    large: Boolean = false,
    /**
     * The overview's two lines kept from the first frame, while the season's listing is on its way,
     * so the cards do not grow (and the list under them move) when the words arrive.
     */
    holdOverview: Boolean = false,
) {
    val shape = RoundedCornerShape(Corner.Medium)
    val card = modifier
        .fillMaxWidth()
        .clip(shape)
        .background(if (current) Tone.accent.copy(alpha = 0.14f) else Tone.surface)
        .border(1.dp, if (current) Tone.accent.copy(alpha = 0.5f) else Tone.outline, shape)
    EpisodeLine(
        episode, chosen, point, finished, upNext, onClick, card.then(lineModifier), onLongClick, current, holdOk, name,
        still = guide?.stillUrl,
        overview = guide?.overview?.trim()?.takeIf { showOverview && it.isNotEmpty() },
        large = large,
        holdOverview = showOverview && holdOverview,
    )
}

/**
 * A choice of one among a few short labels, as a round chip: a show's quality ("1080p"), with
 * [description] read out for it. Under the remote it takes the focus fill, as every other
 * television control does.
 */
@Composable
internal fun CopyChip(label: String, chosen: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, description: String = label) {
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val shape = CircleShape
    Text(
        label,
        style = if (tv) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
        fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
        color = when {
            focused -> Tone.onFocusFill
            chosen -> Tone.accent
            else -> Tone.text
        },
        maxLines = 1,
        modifier = modifier
            .clip(shape)
            .background(
                when {
                    focused -> Tone.focusFill
                    chosen -> Tone.accent.copy(alpha = 0.18f)
                    else -> Tone.surfaceHigh
                },
            )
            .focusRing(focused, shape)
            .border(1.dp, if (chosen && !focused) Tone.accent.copy(alpha = 0.6f) else Color.Transparent, shape)
            .semantics {
                contentDescription = description
                selected = chosen
            }
            .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = if (tv) 8.dp else 6.dp),
    )
}

/**
 * A show posted in more than one quality: "Quality" and a chip for each ("1080p", "720p"), the one
 * the episodes play marked. One choice for the whole show, since whoever picks the 720p of one
 * episode wants the 720p of the next; nothing at all for a show posted in one quality.
 */
@Composable
fun QualityPicker(heights: List<Int>, selected: Int?, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    if (heights.size < 2) return
    val s = LocalStrings.current
    Row(
        modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(s.seriesQuality, style = MaterialTheme.typography.labelLarge, color = Tone.muted, maxLines = 1, modifier = Modifier.padding(end = 4.dp))
        heights.forEach { height ->
            val label = qualityLabel(height)
            CopyChip(label, chosen = height == selected, onClick = { onSelect(height) }, description = s.seriesQualityPick(label))
        }
    }
}

/** "1080p", "4K": a picture height as a quality chip names it. */
internal fun qualityLabel(height: Int): String = if (height >= 2160) "4K" else "${height}p"

/**
 * The copy of [episode] nearest the show's chosen [height]: the same quality where the episode has
 * it, else the closest, the lower on a tie. [fallback] for an episode with one copy, or none with
 * a quality to compare.
 */
internal fun copyAt(episode: SeriesEpisode, height: Int?, fallback: MediaItem): MediaItem {
    if (height == null || episode.copies.size <= 1) return fallback
    return episode.copies
        .mapNotNull { copy -> VideoCopies.heightOf(copy)?.let { copy to it } }
        .minWithOrNull(compareBy<Pair<MediaItem, Int>> { kotlin.math.abs(it.second - height) }.thenBy { it.second })
        ?.first ?: fallback
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EpisodeLine(
    episode: SeriesEpisode,
    item: MediaItem,
    point: WatchPoint?,
    finished: Boolean,
    upNext: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    onLongClick: (() -> Unit)?,
    current: Boolean = false,
    holdOk: Boolean = false,
    name: String? = null,
    still: String? = null,
    overview: String? = null,
    large: Boolean = false,
    holdOverview: Boolean = false,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val shape = RoundedCornerShape(Corner.Medium)
    val cover = rememberCover(item)
    Row(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (focused) Tone.surfaceHigh else Color.Transparent)
            .border(if (focused) Focus.Edge else 0.dp, if (focused) Tone.accent else Color.Transparent, shape)
            .then(
                if (onLongClick != null && tv && holdOk) {
                    Modifier.okHold(interactions, onClick, onLongClick)
                } else if (onLongClick != null && !tv) {
                    Modifier.combinedClickable(interactionSource = interactions, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClick)
                },
            )
            .padding(if (tv || large) 12.dp else 8.dp),
        horizontalArrangement = Arrangement.spacedBy(if (large) 20.dp else if (tv) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(if (large) EPISODE_ART_LARGE else if (tv) EPISODE_ART_TV else EPISODE_ART_TOUCH)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(Corner.Small)),
        ) {
            MediaArt(item.miniThumbnail, item.thumbnailFileId, Modifier.fillMaxSize(), cover = cover, compactCover = true)
            // The provider's still for the episode, over the frame once it is decoded, at the
            // copy the card's width needs (EpisodeStill).
            EpisodeStill(still, Modifier.fillMaxSize())
            val fraction = when {
                point != null && point.fraction > 0f -> point.fraction
                finished -> 1f
                else -> 0f
            }
            if (fraction > 0f) {
                Box(Modifier.matchParentSize().background(Scrims.tileFoot))
                com.tmplayer.ui.components.ProgressLine(fraction, Modifier.align(Alignment.BottomCenter), inset = 6.dp)
            }
            if (finished) {
                WatchedBadge(
                    size = if (tv) 24.dp else 20.dp,
                    modifier = Modifier.align(Alignment.TopEnd).padding(TileBadgeInset),
                )
            }
            if (upNext && !finished) {
                Icon(
                    TmIcons.Play,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(if (tv) 40.dp else 32.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .padding(4.dp),
                )
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    s.seriesEpisodeShort(episode.season, episode.episode),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Tone.muted,
                    maxLines = 1,
                )
                when {
                    current -> EpisodeBadge(s.episodesPlaying)
                    upNext && !finished -> EpisodeBadge(s.seriesUpNextBadge)
                }
            }
            Text(
                name ?: s.seriesEpisode(episode.episode),
                style = if (tv || large) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall,
                color = Tone.text,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            // No Telegram file name line: the S1 E5 line, the name and the facts already say which
            // episode it is (user, 2026-10-22), and a card that never gains or loses a line keeps its
            // height when the provider's name arrives.
            val stopped = point?.takeIf { it.positionMs > 0 && !finished }
                ?.let { s.seriesStoppedAt(StreamStats.formatClock(it.positionMs)) }
            val meta = listOfNotNull(
                MediaMapper.formatDuration(item.durationSec).ifEmpty { null },
                // Which of the episode's copies the card plays, where it has more than one.
                item.takeIf { episode.copies.size > 1 && VideoCopies.heightOf(it) != null }?.copyQuality(),
                MediaMapper.formatSize(item.sizeBytes).ifEmpty { null },
                stopped ?: s.seriesWatchedLabel.takeIf { finished },
            ).joinToString("  ·  ")
            if (meta.isNotEmpty()) {
                Text(
                    meta,
                    style = if (large) MaterialTheme.typography.labelLarge else MaterialTheme.typography.labelMedium,
                    color = if (stopped != null || finished) Tone.accent else Tone.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (overview != null || holdOverview) {
                Text(
                    overview.orEmpty(),
                    style = if (tv || large) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    color = Tone.muted,
                    maxLines = 2,
                    minLines = if (holdOverview) 2 else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/**
 * An episode's still from the provider, faded in over whatever stands under it (the video's own
 * frame) once it is decoded, at the copy the card's drawn width needs
 * ([com.tmplayer.online.MetaImageSize.still]): TMDB's `w185` or `w300` for a card, a few
 * kilobytes that land at once, never the full size still a large backdrop would want. The card
 * holds its 16:9 place from the start, so nothing moves when it arrives.
 */
@Composable
internal fun EpisodeStill(url: String?, modifier: Modifier = Modifier) {
    if (url == null) return
    val online = com.tmplayer.online.OnlineMetadata.current ?: return
    BoxWithConstraints(modifier) {
        val pixels = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.roundToPx() }.coerceAtLeast(1)
        val sized = remember(url, pixels) { com.tmplayer.online.MetaImageSize.still(url, pixels) }
        // Android samples to this width; the desktop decodes whole, whatever it says.
        val decode = pixels.coerceIn(EPISODE_STILL_MIN_DECODE, EPISODE_STILL_MAX_DECODE)
        var bitmap by remember(sized) { mutableStateOf(com.tmplayer.data.Thumbnails.peekOnline(sized, decode)) }
        val reveal = remember(sized) { androidx.compose.animation.core.Animatable(if (bitmap != null) 1f else 0f) }
        val still = com.tmplayer.ui.components.rememberLessMotion()
        LaunchedEffect(sized) {
            if (bitmap == null) bitmap = com.tmplayer.data.Thumbnails.online(sized, decode) { online.image(sized) }
        }
        val shown = bitmap
        LaunchedEffect(shown != null) {
            if (shown == null || reveal.value >= 1f) return@LaunchedEffect
            if (still) reveal.snapTo(1f) else reveal.animateTo(1f, androidx.compose.animation.core.tween(EPISODE_STILL_FADE_MS))
        }
        if (shown != null) {
            androidx.compose.foundation.Image(
                shown,
                contentDescription = null,
                modifier = Modifier.fillMaxSize().graphicsLayer { alpha = reveal.value },
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                filterQuality = com.tmplayer.data.Thumbnails.drawFilter,
            )
        }
    }
}

private const val EPISODE_STILL_MIN_DECODE = 160
private const val EPISODE_STILL_MAX_DECODE = 420
private const val EPISODE_STILL_FADE_MS = 240

/** The widest and tallest a title logo is set at the head of a show's page or a video's. */
internal val TITLE_LOGO_WIDTH = 320.dp
internal val TITLE_LOGO_HEIGHT = 96.dp
internal val TITLE_LOGO_WIDTH_TV = 380.dp
internal val TITLE_LOGO_HEIGHT_TV = 120.dp
internal val TITLE_LOGO_WIDTH_LARGE = 480.dp
internal val TITLE_LOGO_HEIGHT_LARGE = 150.dp

/**
 * The show's play pill, naming what it starts: "Resume S01E04 · 12 min left" for an episode left
 * part way (bare "Resume S01E04" while its length is unknown), "Continue S01E05" once earlier ones
 * are watched, else "Play S01E01".
 */
fun seriesPlayLabel(code: String, point: WatchPoint?, anyWatched: Boolean): String {
    val at = point?.positionMs ?: 0L
    return when {
        at > 0 && point != null && point.durationMs > at -> {
            val minutes = ((point.durationMs - at + 59_999) / 60_000).coerceAtLeast(1)
            L.seriesResumeLeft(minutes = minutes, code = code)
        }
        at > 0 -> L.seriesResume(code)
        anyWatched -> L.seriesContinue(code)
        else -> L.seriesPlay(code)
    }
}

/** "Up next" or "Playing" beside an episode's label, in the accent. */
@Composable
private fun EpisodeBadge(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = Tone.onAccent,
        maxLines = 1,
        modifier = Modifier
            .clip(CircleShape)
            .background(Tone.accent)
            .padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

/**
 * A show opened, as a page: the backdrop (the show's own when posters and overviews have one,
 * else the next episode's Telegram thumbnail as a soft field, else its generated cover) with the
 * poster beside the title when online, the facts line ("2021 · 3 seasons · 24 eps · 12 of 24
 * watched · the chat"), the overview, one bright pill straight to the next episode and the other
 * actions round beside it, then the season picker and the episodes of the chosen season as cards.
 * Everything scrolls as one, so the backdrop leaves the screen as the episodes come up.
 *
 * [seasonPicker] is the platform's: tabs on a phone and a television ([SeasonTabs], the default),
 * a dropdown on the desktop. On a television the play button takes focus first, so OK straight
 * after opening a show carries on where the viewer left it, and Down walks to the seasons and
 * then the episodes.
 *
 * Beside the play button, Download queues the season shown. Once some of that season is watched
 * and some is not, Download season and Download unwatched are two buttons side by side, so a
 * remote reaches both along the same line.
 *
 * The bright pill always plays, whatever "Open a video's page first" says: it names the episode it
 * starts ("Play S01E04", "Resume S01E04 · 12 min left"), and a press that opened a page instead
 * would leave a viewer pressing Play again and getting the page again. An episode's own card is a
 * tile like any other, so [onOpenEpisode] is where a platform sends it to that episode's page
 * first; null plays it with [onPlay], as before.
 *
 * [contentPadding] is the page's margin for the words and the rows; the backdrop runs full bleed
 * under it. [trailing] sits at the top end over the backdrop, for the platform's own close or back.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeriesPanel(
    series: Series,
    watch: SeriesWatch,
    onPlay: (MediaItem) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    onLongClick: ((MediaItem) -> Unit)? = null,
    seasonPicker: @Composable (selected: Int, onSelect: (Int) -> Unit) -> Unit = { selected, onSelect ->
        SeasonTabs(series.seasons, selected, onSelect)
    },
    /** At the top end over the backdrop, for the platform's own close or back control. */
    trailing: @Composable () -> Unit = {},
    /**
     * An episode card's click or OK, when it opens the episode's page rather than playing it.
     * Handed a way to put focus back on the card when that page closes. Null plays the episode.
     */
    onOpenEpisode: ((MediaItem, refocus: () -> Unit) -> Unit)? = null,
    /** Opens a video's detail, for "More like this"; null leaves that row out. */
    onOpenItem: ((MediaItem) -> Unit)? = onLongClick,
    /**
     * Queues the season shown, or its unwatched episodes, through the platform's multi-download;
     * null leaves the download buttons out.
     */
    onDownload: ((SeasonDownloadRequest) -> Unit)? = null,
    /** The chat the show was posted in, for the facts line; null leaves it out. */
    chatTitle: String? = null,
    /**
     * The round back button at the top start, for a finger or a pointer; null leaves it out (a
     * remote has Back). It sits in a band that fills in as the page scrolls, with the show's
     * name in it, so the page passes under the band rather than under a bare button.
     */
    onBack: (() -> Unit)? = null,
    /** What the band keeps clear at the top: the status bar on a phone, nothing on the desktop. */
    barInsets: WindowInsets = WindowInsets(0),
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    val progress = watch.progress(series)
    val next = progress.next
    var season by rememberSaveable(series.key) {
        mutableIntStateOf(next?.season ?: series.seasons.first().number)
    }
    val shown = series.seasons.firstOrNull { it.number == season } ?: series.seasons.first()
    // The qualities the show was posted in, highest first, and the one its episodes play: the
    // viewer's pick here, else that of the copy they watched last, else of the newest upload.
    val heights = remember(series) {
        series.episodes.flatMap { it.copies }.mapNotNull(VideoCopies::heightOf).distinct().sortedDescending()
    }
    var pickedHeight by rememberSaveable(series.key) { mutableStateOf<Int?>(null) }
    val height = pickedHeight ?: (progress.like ?: next?.item ?: series.cover).let(VideoCopies::heightOf)
    fun copyOf(episode: SeriesEpisode, fallback: MediaItem) =
        if (pickedHeight == null) fallback else copyAt(episode, pickedHeight, fallback)
    val playFocus = remember { FocusRequester() }
    // Whether Play holds focus, and whether the viewer has pressed a key on the page yet: Play is
    // asked for until it has focus or a key has gone elsewhere. Not "until anything has focus":
    // the window hands focus to the first control it can reach as soon as it gets it, which on a
    // slow television is the season tab, before Play is even laid out.
    var playFocused by remember(series.key) { mutableStateOf(false) }
    var keyPressed by remember(series.key) { mutableStateOf(false) }
    // A page opened from a card on the TV home screen comes up before its window has focus, and a
    // request made then is lost; it is made again once the window has it.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    // Set once Play has been placed. Play sits in a lazy list item in a window of its own, and on a
    // slow television that item is laid out well after the page opens: a request before then is
    // lost, so it waits for Play itself rather than for a count of frames, which a stick outlasts.
    var playPlaced by remember(series.key) { mutableStateOf(false) }
    LaunchedEffect(series.key, windowFocused, playPlaced) {
        if (!tv || !windowFocused || !playPlaced) return@LaunchedEffect
        // Asked again on each frame until Play has focus, for as long as a slow first
        // layout may take.
        val until = System.nanoTime() + FOCUS_WAIT_NS
        while (System.nanoTime() < until) {
            withFrameNanos { }
            if (playFocused || keyPressed) return@LaunchedEffect
            runCatching { playFocus.requestFocus() }
        }
    }
    // Download is for the season shown, so its buttons follow the season picked.
    val downloads = onDownload != null && SeasonDownload.anySavable(shown)
    val bothDownloads = downloads && SeasonDownload.unwatchedDiffers(shown, watch.finished)

    val metaState = com.tmplayer.ui.online.rememberMetaState(series.cover, showOnly = true)
    val meta = metaState.value
    val extrasState = com.tmplayer.ui.online.rememberMetaExtrasState(meta, metaState.settled)
    val extras = extrasState.value
    val names = rememberSeasonEpisodes(meta, shown.number)
    // The first frame waits a moment for what the caches hold, so a show seen before is drawn
    // whole at once; what is still coming after that has its place held (DetailSlots.kt).
    val namesSettled = meta == null || meta.kind != com.tmplayer.online.MetaKind.Show || names != null
    val logoReady = com.tmplayer.ui.online.rememberTitleLogoReady(meta, metaState.settled)
    val firstPaint = rememberFirstPaint(series.key, metaState.settled && extrasState.settled && logoReady && namesSettled)
    val titleHold = logoMayCome(metaState)
    val holdTitle = remember(series.key, firstPaint) { titleHold }
    val holdMetaLines = remember(series.key, firstPaint) { !extrasState.settled }
    val holdOverview = remember(series.key, firstPaint) { !metaState.settled }
    val holdPoster = remember(series.key, firstPaint) { meta?.posterUrl != null || !metaState.settled }
    val holdTrailer = remember(series.key, firstPaint) { extras?.trailer != null || !extrasState.settled }
    val yearInFacts = remember(series.key, firstPaint) { metaState.settled }
    val onlineBackdrop = remember(series.key, firstPaint) { meta?.backdropUrl != null || !metaState.settled }
    // A season's episode words held per season: from its first frame while its listing comes.
    val namesComing = !metaState.settled || (meta?.kind == com.tmplayer.online.MetaKind.Show && names == null)
    val holdEpisodeWords = remember(series.key, shown.number, firstPaint) { namesComing }
    val trailers = rememberTrailerLauncher()
    // The trailer's player, once for the page and outside its list: placed in the list's first
    // item it was composed twice while the first paint's list handed over to the live one, and
    // two players opened one over the other (sound over a black screen, 2026-10-22).
    TrailerHost(trailers)
    val target = next ?: series.episodes.first()
    val art = next?.item ?: target.item
    val cover = remember(art.chatId, art.messageId, series.title) { CoverArt.of(art).copy(title = series.title, number = null) }
    val source = remember(art.id, cover, meta?.backdropUrl) { BackdropSource(art.miniThumbnail, art.thumbnailFileId, cover, meta?.backdropUrl) }
    val reduced = rememberReducedMotion()
    val pad = contentPadding.horizontalOnly()

    // An anime season runs to hundreds of episodes, and scrolling to the 850th is no way to find
    // it. Past [EPISODE_RANGE] the season is cut into ranges, opened on the one holding the next
    // episode to watch.
    val ranges = remember(shown) {
        if (shown.episodes.size > EPISODE_RANGE) shown.episodes.chunked(EPISODE_RANGE) else emptyList()
    }
    var rangeAt by remember(shown) {
        mutableIntStateOf(ranges.indexOfFirst { range -> next != null && range.any { it.sameAs(next) } }.coerceAtLeast(0))
    }
    val listed = ranges.getOrNull(rangeAt) ?: shown.episodes
    val list = rememberLazyListState()
    // Another season or range picked while the episodes fill the screen: back up to the picker,
    // with the new list under it. Opening is its own case, below. Saved, so a page brought back
    // after the phone is turned keeps where it was scrolled to rather than opening again.
    var opened by rememberSaveable(series.key) { mutableStateOf(false) }
    // Where the up-next episode sits in the list shown, or -1 when it is in another season or range.
    val upNextAt = if (next != null) listed.indexOfFirst { it.sameAs(next) } else -1
    val upNextFocus = remember(series.key) { FocusRequester() }
    val scope = rememberCoroutineScope()
    LaunchedEffect(shown.number, rangeAt, firstPaint) {
        // Nothing to scroll while the first frame is held for the caches.
        if (!firstPaint) return@LaunchedEffect
        if (!opened) {
            opened = true
            // A long season (an anime's hundreds) opens on its up-next episode under a finger, one
            // card of what came before above it, so nobody scrolls to the 57th by hand. A short one
            // opens at the top, where the hero and Play are. A remote gets there with Down from Play.
            if (!tv && upNextAt > 0 && listed.size > LONG_SEASON) list.scrollToItem(FIRST_EPISODE_ITEM + upNextAt - 1)
            return@LaunchedEffect
        }
        if (list.firstVisibleItemIndex > SEASONS_ITEM) list.scrollToItem(SEASONS_ITEM)
    }
    // On a television Down from Play goes straight to the up-next card, scrolled into view first
    // since a lazy list has not composed it yet; Up from the cards still reaches the seasons.
    val downToUpNext = if (tv && upNextAt >= 0) {
        Modifier.onPreviewKeyEvent { event ->
            if (event.key != Key.DirectionDown) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyDown) {
                scope.launch {
                    list.scrollToItem((FIRST_EPISODE_ITEM + upNextAt - 1).coerceAtLeast(SEASONS_ITEM))
                    withFrameNanos { }
                    runCatching { upNextFocus.requestFocus() }
                }
            }
            true
        }
    } else {
        Modifier
    }

    BoxWithConstraints(
        modifier
            .onPreviewKeyEvent { keyPressed = true; false }
            // Should focus come into the page from outside before Play has it, it goes to Play.
            .then(if (tv) Modifier.focusProperties { onEnter = { runCatching { playFocus.requestFocus() } } } else Modifier)
            .focusGroup(),
    ) {
        val wide = maxWidth > maxHeight || maxWidth >= WIDE_PAGE
        // A large window on the desktop (or a wide browser): a title page drawn to its size, a
        // bigger backdrop, title, poster and type, Play a size up with round buttons beside it,
        // and larger episode cards. A phone and a television keep their own.
        val large = wide && !tv && com.tmplayer.ui.components.deviceForm() == com.tmplayer.data.DeviceForm.Desktop && maxWidth >= LARGE_PAGE
        val heroHeight = if (large) {
            (maxHeight * 0.7f).coerceIn(420.dp, 720.dp)
        } else if (wide) {
            (maxHeight * 0.62f).coerceIn(280.dp, 560.dp)
        } else {
            // Chosen once, so the online picture fades into the frame rather than growing it.
            tallBackdropHeight(maxWidth, maxHeight, online = onlineBackdrop)
        }
        val heading: @Composable () -> Unit = {
            val title = meta?.title?.takeIf { it.isNotBlank() } ?: series.title
            // The show's title logo from TMDB where it has one, the name in type until it is
            // decoded and wherever it has none; read out as the name either way. Its place is as
            // tall as the logo may be, so the name giving way to it moves nothing.
            val logoHeight = if (large) TITLE_LOGO_HEIGHT_LARGE else if (tv) TITLE_LOGO_HEIGHT_TV else TITLE_LOGO_HEIGHT
            TitleSlot(logoHeight, hold = holdTitle) {
                com.tmplayer.ui.online.TitleArt(
                    meta,
                    maxWidth = if (large) TITLE_LOGO_WIDTH_LARGE else if (tv) TITLE_LOGO_WIDTH_TV else TITLE_LOGO_WIDTH,
                    maxHeight = logoHeight,
                    settled = metaState.settled,
                    waitMs = 0L,
                    modifier = Modifier.clearAndSetSemantics {
                        heading()
                        contentDescription = title
                    },
                ) {
                    Text(
                        title,
                        style = (if (large) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall).trackedFor(title),
                        fontWeight = if (large) FontWeight.Bold else null,
                        color = Tone.text,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val poster: @Composable () -> Unit = {
            val url = meta?.posterUrl
            // Its place is held while the match is on its way: a soft poster, the picture fading
            // in over it, or the show's own cover where the match has none, so the title beside
            // it never moves over or re-wraps.
            if (url != null || holdPoster) {
                Box(
                    Modifier
                        .width(if (large) SERIES_POSTER_LARGE else if (tv) SERIES_POSTER_TV else if (wide) SERIES_POSTER_WIDE else SERIES_POSTER)
                        .aspectRatio(2f / 3f)
                        .clip(RoundedCornerShape(Corner.Medium))
                        .background(Tone.surfaceHigh),
                ) {
                    when {
                        url != null -> MetaPicture(url, Modifier.fillMaxSize(), maxWidth = 342, contentDescription = s.metadataPoster)
                        !metaState.settled -> PosterPlaceholder()
                        else -> MediaArt(art.miniThumbnail, art.thumbnailFileId, Modifier.fillMaxSize(), cover = cover, compactCover = true)
                    }
                }
            }
        }
        val facts: @Composable () -> Unit = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FactLine(
                    listOfNotNull(
                        // The year only when it was there on the first frame; later it opens the
                        // genres line, in its held place, so the facts never re-wrap.
                        meta?.year?.takeIf { yearInFacts }?.let { Fact(it.toString()) },
                        Fact(seriesSummary(series)),
                        seriesProgressLine(progress)?.let { Fact(it, accent = true) },
                        chatTitle?.takeIf { it.isNotBlank() }?.let { Fact(it) },
                    ),
                    style = if (large) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                )
                DetailMetaLines(
                    extrasState,
                    year = meta?.year?.takeIf { !yearInFacts },
                    hold = holdMetaLines,
                    yearPending = !yearInFacts && !metaState.settled,
                    // The facts line above already counts the seasons.
                    seasons = false,
                )
                // How far through the whole show; the facts line says it in words.
                if (progress.watched > 0) ProgressLine(progress.watched.toFloat() / progress.total, Modifier.widthIn(max = 240.dp))
            }
        }
        val overview: @Composable () -> Unit = {
            val text = meta?.overview?.takeIf { it.isNotBlank() }
            if (text != null || holdOverview) {
                Column(Modifier.then(if (large) Modifier.widthIn(max = LARGE_READING) else Modifier), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    SynopsisSlot(
                        text,
                        waiting = !metaState.settled,
                        hold = holdOverview,
                        style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.bodyMedium,
                        lines = if (large || wide) 3 else null,
                    )
                    // The credit's line kept with the held words, so it does not add one when they come.
                    Box(Modifier.neverShrink()) { meta?.let { if (text != null) MetaCredit(it) } }
                    MetaPeopleLine(extras)
                }
            }
        }
        val actions: @Composable () -> Unit = {
            // The trailer's place is held (unseen) while the extras are on their way, so the
            // download buttons after it never move over when it comes.
            val trailer = extras?.trailer
            val trailerGhost = trailer == null && holdTrailer
            val others = buildList {
                if (trailer != null || holdTrailer) add(Triple(s.metadataTrailer, TrailerIcon) { trailer?.let(trailers::open); Unit })
                if (downloads) {
                    add(Triple(s.seriesDownloadSeason, TmIcons.Download) { onDownload?.invoke(watch.download(series, shown, SeasonDownload.Which.Season)); Unit })
                }
                if (bothDownloads) {
                    add(Triple(s.seriesDownloadUnwatched, TmIcons.Download) { onDownload?.invoke(watch.download(series, shown, SeasonDownload.Which.Unwatched)); Unit })
                }
            }
            val hero: @Composable (Modifier) -> Unit = { m ->
                HeroPill(
                    label = seriesPlayLabel(target.code, watch.pointOf(target), anyWatched = progress.watched > 0),
                    icon = TmIcons.Play,
                    onClick = { onPlay(copyOf(target, next?.item ?: watch.pick(target, progress))) },
                    modifier = m.then(downToUpNext).onPlaced { playPlaced = true }.onFocusChanged { playFocused = it.hasFocus },
                    focusRequester = playFocus,
                )
            }
            Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (large) {
                    // Play a size up, and the trailer and the downloads as round buttons beside it.
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        HeroPill(
                            label = seriesPlayLabel(target.code, watch.pointOf(target), anyWatched = progress.watched > 0),
                            icon = TmIcons.Play,
                            onClick = { onPlay(copyOf(target, next?.item ?: watch.pick(target, progress))) },
                            modifier = Modifier.onPlaced { playPlaced = true }.onFocusChanged { playFocused = it.hasFocus },
                            focusRequester = playFocus,
                            large = true,
                        )
                        others.forEach { (label, icon, run) -> WideIconAction(label, icon, run, ghost = trailerGhost && label == s.metadataTrailer, waiting = trailerGhost && label == s.metadataTrailer && !extrasState.settled) }
                    }
                } else if (tv) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        hero(Modifier)
                        others.forEach { (label, icon, run) -> RoundAction(label, icon, run, short = roundLabel(label, s), ghost = trailerGhost && label == s.metadataTrailer, waiting = trailerGhost && label == s.metadataTrailer && !extrasState.settled) }
                    }
                } else if (wide) {
                    // A phone on its side: the round buttons in Play's row, after it, as on a
                    // television, wrapping only when they do not fit. Play comes first at the start,
                    // so the trailer arriving later never moves it.
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        hero(Modifier)
                        others.forEach { (label, icon, run) -> RoundAction(label, icon, run, short = roundLabel(label, s), ghost = trailerGhost && label == s.metadataTrailer, waiting = trailerGhost && label == s.metadataTrailer && !extrasState.settled) }
                    }
                } else {
                    hero(Modifier.fillMaxWidth())
                    if (others.isNotEmpty()) {
                        // Under a full width Play, centred on it, as on a video's page.
                        FlowRow(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            others.forEach { (label, icon, run) -> RoundAction(label, icon, run, short = roundLabel(label, s), ghost = trailerGhost && label == s.metadataTrailer, waiting = trailerGhost && label == s.metadataTrailer && !extrasState.settled) }
                        }
                    }
                }
                trailers.unopened?.takeIf { it == extras?.trailer }?.let { TrailerQr(it) }
            }
        }

        // The page's ground, and the band's way back, for the moment the caches are asked.
        if (firstPaint) HeroStaysWhole(list) { LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().keepTopInSight(list, enabled = tv),
            contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(if (tv) 8.dp else 6.dp),
        ) {
            item(key = "hero") {
                Column(verticalArrangement = Arrangement.spacedBy(if (large) 24.dp else 16.dp)) {
                    Box(Modifier.fillMaxWidth().heightIn(min = heroHeight).clipToBounds()) {
                        Box(Modifier.matchParentSize()) {
                            PageBackdrop(
                                source,
                                fromStart = wide,
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    .fillMaxWidth(if (wide) BACKDROP_SHARE else 1f)
                                    .fillMaxHeight()
                                    .graphicsLayer {
                                        // Half the list's speed while the hero is on screen; none with animations off.
                                        if (!reduced && list.firstVisibleItemIndex == 0) translationY = list.firstVisibleItemScrollOffset * PARALLAX
                                    },
                            )
                        }
                        Row(
                            Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth(if (large) TEXT_SHARE_LARGE else if (wide) TEXT_SHARE else 1f)
                                .padding(pad)
                                .padding(top = heroHeight * 0.35f, bottom = if (wide) 8.dp else 0.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (tv || large) 24.dp else 16.dp),
                            verticalAlignment = Alignment.Bottom,
                        ) {
                            poster()
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (large) 14.dp else 10.dp)) {
                                heading()
                                if (wide) {
                                    facts()
                                    overview()
                                }
                            }
                        }
                        Box(Modifier.align(Alignment.TopEnd)) { trailing() }
                    }
                    if (!wide) {
                        Column(Modifier.padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) { facts() }
                    }
                    actions()
                    if (!wide) Box(Modifier.padding(pad)) { overview() }
                    Spacer(Modifier.height(4.dp))
                }
            }
            item(key = "seasons") {
                Column(Modifier.fillMaxWidth().padding(pad), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    seasonPicker(shown.number) { season = it }
                    if (ranges.isNotEmpty()) EpisodeRanges(ranges = ranges, selected = rangeAt, onSelect = { rangeAt = it })
                    QualityPicker(heights, selected = height?.takeIf { it in heights }, onSelect = { pickedHeight = it })
                    Spacer(Modifier.height(2.dp))
                }
            }
            items(listed, key = { it.item.id }) { episode ->
                val chosen = copyOf(episode, if (next != null && episode.sameAs(next)) next.item else watch.pick(episode, progress))
                val rowFocus = remember { FocusRequester() }
                EpisodeRow(
                    episode = episode,
                    point = watch.pointOf(episode),
                    finished = watch.finishedEpisode(episode),
                    upNext = next != null && episode.sameAs(next),
                    onClick = {
                        val open = onOpenEpisode
                        if (open != null) open(chosen) { runCatching { rowFocus.requestFocus() } } else onPlay(chosen)
                    },
                    onLongClick = onLongClick?.let { { it(chosen) } },
                    chosen = chosen,
                    modifier = Modifier.padding(pad).widthIn(max = if (large) EPISODES_MAX_LARGE else EPISODES_MAX),
                    lineModifier = (if (next != null && episode.sameAs(next)) Modifier.focusRequester(upNextFocus) else Modifier)
                        .focusRequester(rowFocus),
                    name = EpisodeNames.listName(episode.season, episode.episode, names),
                    guide = EpisodeNames.listed(episode.season, episode.episode, names),
                    showOverview = true,
                    large = large,
                    holdOverview = holdEpisodeWords,
                )
            }
            // The cast and "More like this" after the episodes: composed only once scrolled to.
            if (meta != null && extras != null) {
                if (extras.cast.isNotEmpty()) {
                    item(key = "meta-cast") { MetaCastRow(extras.cast, Modifier.padding(pad).padding(top = 16.dp)) }
                }
                if (extras.similar.isNotEmpty() && onOpenItem != null) {
                    item(key = "meta-more") { MoreLikeThisRow(meta, extras, onOpenItem, Modifier.padding(pad).padding(top = 16.dp)) }
                }
            }
        }
        }
        if (firstPaint) {
            ScrollToTop(list)
            // A long season is one hold from its end; the bubble names the episode on screen.
            val episodeLabels = remember(listed) { listed.associate { it.item.id to s.seriesEpisodeShort(it.season, it.episode) } }
            FastScroller(list, label = { key -> episodeLabels[key] })
        }
        onBack?.let { back ->
            PageBackBand(list, meta?.title?.takeIf { it.isNotBlank() } ?: series.title, back, barInsets, Modifier.align(Alignment.TopStart))
        }
    }
}

/**
 * The page's back button, in a band across the top that fills with the page's colour over the
 * first [BAND_FILL] of scrolling and then names the show. At the top of the page only the round
 * button shows, over the backdrop; scrolled, the hero, Play and the episodes pass under a solid
 * band instead of under the button, and the status bar keeps a quiet field behind its icons.
 */
@Composable
private fun PageBackBand(list: LazyListState, title: String, onBack: () -> Unit, insets: WindowInsets, modifier: Modifier) {
    val s = LocalStrings.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val fillPx = with(density) { BAND_FILL.toPx() }
    val field = Tone.background
    val fill by remember(list, fillPx) {
        derivedStateOf {
            if (list.firstVisibleItemIndex > 0) 1f else (list.firstVisibleItemScrollOffset / fillPx).coerceIn(0f, 1f)
        }
    }
    Row(
        modifier
            .fillMaxWidth()
            .drawBehind { drawRect(field.copy(alpha = fill)) }
            .windowInsetsPadding(insets)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        PageDiscButton(TmIcons.ArrowBack, s.seriesBack, onBack)
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = Tone.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).graphicsLayer { alpha = fill },
        )
    }
}

/** How far the page scrolls before the back band is solid. */
private val BAND_FILL = 72.dp

/**
 * A long season's episodes in ranges of [EPISODE_RANGE], "1-100", "101-200", drawn like the
 * season tabs. Numbered by the episodes in each range, so a season with gaps says what it holds.
 */
@Composable
private fun EpisodeRanges(
    ranges: List<List<SeriesEpisode>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tv = !isTouch()
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ranges.forEachIndexed { index, range ->
            val interactions = remember { MutableInteractionSource() }
            val focused by interactions.collectIsFocusVisibleAsState()
            val chosen = index == selected
            val shape = RoundedCornerShape(Corner.Small)
            Text(
                "${range.first().episode}-${range.last().episode}",
                style = if (tv) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelLarge,
                fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
                color = when {
                    focused -> Tone.onFocusFill
                    chosen -> Tone.accent
                    else -> Tone.text
                },
                maxLines = 1,
                modifier = Modifier
                    .clip(shape)
                    .background(
                        when {
                            focused -> Tone.focusFill
                            chosen -> Tone.accent.copy(alpha = 0.18f)
                            else -> Tone.surfaceHigh
                        },
                    )
                    .border(if (chosen && !focused) 1.5.dp else 0.dp, if (chosen && !focused) Tone.accent else Color.Transparent, shape)
                    .focusRing(focused, shape)
                    .semantics {
                        role = Role.Tab
                        this.selected = chosen
                    }
                    .clickable(interactionSource = interactions, indication = null) { onSelect(index) }
                    .padding(horizontal = if (tv) 18.dp else 14.dp, vertical = if (tv) 10.dp else 8.dp),
            )
        }
    }
}

/** Episodes per range once a season is longer than this; see [EpisodeRanges]. */
private const val EPISODE_RANGE = 100

/**
 * A thin rounded bar of how much of a show or a course is watched, for the top of its list: the
 * tile's bar, drawn at rest on the page rather than over a picture.
 */
@Composable
fun ProgressLine(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(Tone.muted.copy(alpha = 0.25f)),
    ) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(Tone.accent))
    }
}

@Composable
internal fun PlayButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val tv = !isTouch()
    Row(
        modifier
            .height(if (tv) 48.dp else 40.dp)
            .clip(CircleShape)
            .background(Tone.accent)
            .border(if (focused) 2.dp else 0.dp, if (focused) Tone.text else Color.Transparent, CircleShape)
            .clickable(interactionSource = interactions, indication = if (tv) null else androidx.compose.foundation.LocalIndication.current, onClick = onClick)
            .padding(start = 14.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ink = Tone.onAccent
        Icon(TmIcons.Play, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    }
}

private fun PaddingValues.horizontalOnly(): PaddingValues =
    PaddingValues(
        start = calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
        end = calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
    )

private val SERIES_POSTER: Dp = 96.dp
private val SERIES_POSTER_WIDE: Dp = 120.dp
private val SERIES_POSTER_TV: Dp = 140.dp
private val SERIES_POSTER_LARGE: Dp = 176.dp

/** From this width a desktop show page is drawn large (see SeriesPanel). */
private val LARGE_PAGE: Dp = 1000.dp

/** The words' share of a large page's width, from the start; the backdrop shows past them. */
private const val TEXT_SHARE_LARGE = 0.55f

/** A large show page's overview: about 65 characters a line. */
private val LARGE_READING: Dp = 680.dp

/** A large show page's episode cards stop growing here. */
private val EPISODES_MAX_LARGE: Dp = 1120.dp

/** As wide as this, a show's page puts its backdrop at the end and its words over the start. */
private val WIDE_PAGE: Dp = 720.dp

/** How much of a wide page's width the title, facts and overview take, from the start. */
private const val TEXT_SHARE = 0.6f

/** The episode cards stop growing here, so a wide window does not stretch a line of text across it. */
private val EPISODES_MAX: Dp = 960.dp

/** The season picker's place in a show page's list, after the hero. */
private const val SEASONS_ITEM = 1

/** The first episode card's place in that list, after the hero and the season picker. */
private const val FIRST_EPISODE_ITEM = 2

/** A season longer than this opens on its up-next episode on a phone or a desktop. */
private const val LONG_SEASON = 12
private val EPISODE_ART_TOUCH: Dp = 128.dp
private val EPISODE_ART_TV: Dp = 176.dp

/** A large window's episode card: at 2x still within a `w300` still (see EpisodeStill). */
private val EPISODE_ART_LARGE: Dp = 208.dp

/**
 * OK plays, holding OK is [onHold]: the remote's long press, written out of key events because
 * a D-pad hold does not reach `combinedClickable` on a television. A hold is a repeat of the key
 * while it is still down, which the system sends first at its own long-press interval (the signal
 * the app's `holdable` reads too); the press then counts on the way up, so a hold is never also a
 * play. A release with no press seen here (the OK that opened the window) does nothing.
 */
private fun Modifier.okHold(
    interactions: MutableInteractionSource,
    onClick: () -> Unit,
    onHold: () -> Unit,
): Modifier {
    // Not state: nothing draws from it. [0] 1 while a press is live; [1] 1 once this press has
    // been taken as a hold.
    val press = longArrayOf(0L, 0L)
    return this
        .semantics {
            role = Role.Button
            onClick { onClick(); true }
            onLongClick { onHold(); true }
        }
        .onPreviewKeyEvent { event ->
            if (event.key !in OK_KEYS) return@onPreviewKeyEvent false
            when (event.type) {
                KeyEventType.KeyDown -> {
                    if (press[0] == 0L) {
                        press[0] = 1L
                        press[1] = 0L
                    } else if (press[1] == 0L) {
                        press[1] = 1L
                        onHold()
                    }
                    true
                }
                KeyEventType.KeyUp -> {
                    val pressed = press[0] != 0L
                    val held = press[1] != 0L
                    press[0] = 0L
                    press[1] = 0L
                    if (pressed && !held) onClick()
                    true
                }
                else -> false
            }
        }
        .focusable(interactionSource = interactions)
}

private val OK_KEYS = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

/** How long to keep asking for Play's focus after it is placed, before giving up on it. */
private const val FOCUS_WAIT_NS = 3_000_000_000L

/**
 * Where the page's list scrolls to bring a focused control into view, so the hero is never cut
 * at its top on a television, whose own rule stands every focused item a third of the way down
 * the screen: focused on opening, Play went up that far and took the show's name off the top.
 *
 * While the list is still on the hero, a control that fits with the list at its top takes the list
 * back to the top, and one already on screen moves nothing. Past the hero, and for the rows inside
 * the list (season tabs, cast), the platform's own rule stands.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HeroStaysWhole(list: LazyListState, content: @Composable () -> Unit) {
    val platform = LocalBringIntoViewSpec.current
    val spec = remember(list, platform) {
        object : BringIntoViewSpec {
            override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
                val viewport = list.layoutInfo.viewportSize.height.toFloat()
                // A row inside the list asks with its own width: not this list's question.
                if (viewport <= 0f || containerSize != viewport || list.firstVisibleItemIndex != 0) {
                    return platform.calculateScrollDistance(offset, size, containerSize)
                }
                val toTop = -list.firstVisibleItemScrollOffset.toFloat()
                val atTop = offset - toTop
                if (atTop >= 0f && atTop + size <= containerSize) return toTop
                if (offset >= 0f && offset + size <= containerSize) return 0f
                return platform.calculateScrollDistance(offset, size, containerSize)
            }
        }
    }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}
