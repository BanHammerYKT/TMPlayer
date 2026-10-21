package com.tmplayer.ui.browse

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.Spacer
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import com.tmplayer.ui.components.EDGE_SHARE
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tmplayer.data.FeaturedPick
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf
import com.tmplayer.ui.components.EdgeBackdrop
import com.tmplayer.ui.components.BackdropSource
import com.tmplayer.ui.components.SkeletonBox
import com.tmplayer.ui.components.rememberCover
import com.tmplayer.ui.components.rememberShimmer
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.online.rememberMeta
import com.tmplayer.ui.components.warmUpOnIntent
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Motion
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.focusRing
import com.tmplayer.ui.theme.rememberReducedMotion

/**
 * The television's featured carousel at the top of Home: the same newest five different films,
 * shows and videos in the starred chats as the phone's ([FeaturedCarousel],
 * [com.tmplayer.data.HomeFeatured]), one picture at a time across the whole width of the page and
 * up behind its [header], with its name and Play and Details at the start, the phone's order.
 *
 * Made for a remote and a 1 GB stick rather than a finger:
 * - One page is composed at a time, cross-faded to the next: no pager, so only one picture is
 *   fetched and held, a w780 backdrop of about 0.7 MB.
 * - Play and Details stay put while the pages change under them, so the remote never loses its
 *   place. Left on Play goes back a page (from the first, on to the rail as anywhere else), Right
 *   on Details goes on a page; OK on Play plays; Down goes to the rows.
 * - It moves on by itself every [ADVANCE_MS] only while the remote is elsewhere on Home and
 *   nothing is over it ([active]), and never with motion off.
 * - It is [height] tall from the first frame, a stand-in shimmering until the pages arrive, so
 *   Home never jumps.
 */
@Composable
internal fun TvFeaturedCarousel(
    picks: List<FeaturedPick>?,
    height: Dp,
    start: Dp,
    end: Dp,
    watch: SeriesWatch,
    chatTitle: (Long) -> String,
    active: Boolean,
    onPlay: (MediaItem, String) -> Unit,
    onDetails: (FeaturedPick) -> Unit,
    modifier: Modifier = Modifier,
    /** The page's heading, standing on the picture's top; null leaves it to the page. */
    header: (@Composable () -> Unit)? = null,
    /** On Play, for Down from the TV top bar. */
    playFocus: androidx.compose.ui.focus.FocusRequester? = null,
) {
    var headerPx by remember { mutableIntStateOf(0) }
    // Home gives the carousel the whole width to the screen's edge, as a backdrop should have;
    // the heading on it keeps the safe area at its end, as the rows below do.
    Box(modifier.fillMaxWidth()) {
        // The heading's height and the picture's own make the carousel's size; everything else is
        // laid over that, so the picture runs up behind the heading.
        // The heading and the picture's height in one column: the carousel has its whole size in
        // its first frame (a measured spacer was a frame late, and Home jumped), and the heading is
        // drawn over the picture by its zIndex.
        Column(Modifier.fillMaxWidth().zIndex(1f)) {
            if (header != null) Box(Modifier.fillMaxWidth().onSizeChanged { headerPx = it.height }) { header() }
            Spacer(Modifier.fillMaxWidth().height(height))
        }
        if (picks.isNullOrEmpty()) {
            TvFeaturedSkeleton(start, Modifier.matchParentSize())
            return@Box
        }
        val s = LocalStrings.current
        val count = picks.size
        val latest by rememberUpdatedState(picks)
        // The page up, by key, so a carousel that refills stays on the page being looked at.
        var currentKey by remember { mutableStateOf(picks.first().key) }
        val index = picks.indexOfFirst { it.key == currentKey }.coerceAtLeast(0)
        val pick = picks[index]
        val go: (Int) -> Unit = { to -> latest.getOrNull(((to % count) + count) % count)?.let { currentKey = it.key } }

        val still = rememberReducedMotion() || Motion.reduced
        val auto = count > 1 && !still
        var focusedIn by remember { mutableStateOf(false) }
        val paused = focusedIn || !active
        val progress = remember { Animatable(0f) }
        var progressKey by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(currentKey, paused, auto, count) {
            // A new page starts its time from empty; a pause keeps it where it stopped.
            if (progressKey != currentKey) {
                progress.snapTo(0f)
                progressKey = currentKey
            }
            if (!auto || paused) return@LaunchedEffect
            val left = (1f - progress.value).coerceIn(0f, 1f)
            progress.animateTo(1f, tween((ADVANCE_MS * left).toInt(), easing = LinearEasing))
            go(index + 1)
        }

        Crossfade(targetState = pick, animationSpec = Motion.standard(), label = "tvFeatured", modifier = Modifier.matchParentSize()) { shown ->
            // The heading's words over any picture: a fade from the top, deeper than the heading,
            // under the page's own words.
            TvFeaturedArt(shown, chatTitle, start = start, headerFade = if (header != null) headerPx.toFloat() else 0f)
        }

        // What Play starts, worked out as the phone's page does.
        val series = pick.series
        val showProgress = remember(series, watch) { series?.let { watch.progress(it) } }
        val episode = series?.let { showProgress?.next ?: it.episodes.firstOrNull() }
        val playItem = if (series != null && episode != null && showProgress != null) watch.pick(episode, showProgress) else pick.item
        val playLabel = if (series != null && episode != null && showProgress != null) {
            seriesPlayLabel(episode.code, watch.pointOf(episode), showProgress.watched > 0)
        } else if ((watch.point(pick.item)?.positionMs ?: 0L) > 0L) {
            s.commonResume
        } else {
            s.commonPlay
        }
        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
        val back = if (rtl) Key.DirectionRight else Key.DirectionLeft
        val forward = if (rtl) Key.DirectionLeft else Key.DirectionRight

        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = start, bottom = PAGE_PAD)
                .onFocusChanged { focusedIn = it.hasFocus },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HeroPill(
                label = playLabel,
                onClick = { onPlay(playItem, chatTitle(playItem.chatId)) },
                modifier = Modifier.then(if (playFocus != null) Modifier.focusRequester(playFocus) else Modifier).warmUpOnIntent(playItem).onPreviewKeyEvent { event ->
                    // Back a page; from the first page Left carries on to the rail as usual.
                    if (event.type == KeyEventType.KeyDown && event.key == back && count > 1 && index > 0) {
                        go(index - 1)
                        true
                    } else {
                        false
                    }
                },
            )
            TvDetailsPill(
                label = s.detailOpenDetails,
                onClick = { onDetails(pick) },
                modifier = Modifier.onPreviewKeyEvent { event ->
                    if (event.type == KeyEventType.KeyDown && event.key == forward && count > 1) {
                        go(index + 1)
                        true
                    } else {
                        false
                    }
                },
            )
        }
        // Which page is up, at the foot of the end, level with Play. Only a mark: Left on Play
        // and Right on Details already turn the pages, and a stop for the dots would come
        // between Details and its Right.
        CarouselDots(
            count = count,
            current = index,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = end, bottom = PAGE_PAD + (HERO_PILL_HEIGHT - TV_DOTS_HEIGHT) / 2),
        )
    }
}

/** A page's picture, the fades that let its words read, and the name at its start. */
@Composable
private fun TvFeaturedArt(pick: FeaturedPick, chatTitle: (Long) -> String, start: Dp, headerFade: Float) {
    val s = LocalStrings.current
    val item = pick.item
    val series = pick.series
    val chat = chatTitle(item.chatId)
    // A show's backdrop rather than one episode's still: the page stands for the whole show.
    val meta = rememberMeta(item, showOnly = series != null)
    val cover = rememberCover(item, chat)
    val source = remember(item, meta?.wideUrl, cover) { BackdropSource(item.miniThumbnail, item.thumbnailFileId, cover, meta?.wideUrl) }
    val title = remember(pick) { series?.title?.ifBlank { null } ?: SeriesShelf.tileTitle(item) }
    val facts = remember(pick, chat, s) { featuredFacts(pick, title, chat, s) }
    Box(
        Modifier
            .fillMaxSize()
            .semantics { contentDescription = listOf(title, facts).filter { it.isNotBlank() }.joinToString(". ") },
    ) {
        // The picture at the end, melting into the page at its start and foot, so the words at the
        // start and the heading above stand on the page itself; the clock and the account at the
        // heading's end stand on a glow of the page's colour.
        EdgeBackdrop(
            source,
            Modifier.fillMaxSize(),
            // The picture melts into the page's ground towards the top, across the whole width, so
            // every item of the top bar reads over it in every theme.
            topFade = with(LocalDensity.current) { (headerFade * BAR_FADE_REACH).toDp() },
            onPictured = HomeFirstPaint::markReady,
        )
        // Over Play and Details: the title's logo where TMDB has one (the name in type until then
        // and otherwise), the facts line and two lines of the overview, as the desktop's page.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth(TEXT_SHARE)
                .padding(start = start, bottom = PAGE_PAD + HERO_PILL_HEIGHT + TITLE_GAP),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            com.tmplayer.ui.online.TitleArt(meta, maxWidth = TV_LOGO_MAX_WIDTH, maxHeight = TV_LOGO_MAX_HEIGHT) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = Tone.text,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            FeaturedInfo(pick, meta, overviewLines = 2)
        }
    }
}

/** Details beside Play: a quiet pill with the info icon, filled under the remote. */
@Composable
private fun TvDetailsPill(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val still = rememberReducedMotion() || Motion.reduced
    val scale by animateFloatAsState(if (focused && !still) Motion.FocusScale else 1f, Motion.standard(Motion.Fast), label = "detailsScale")
    val fill by animateColorAsState(if (focused) Tone.focusFill else Tone.surfaceHigh.copy(alpha = 0.8f), Motion.standard(Motion.Fast), label = "detailsFill")
    val ink = if (focused) Tone.onFocusFill else Tone.text
    Row(
        modifier
            .heightIn(min = 48.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(CircleShape)
            .background(fill)
            .focusRing(focused, CircleShape)
            .clickable(interactionSource = interactions, indication = null, role = Role.Button, onClick = onClick)
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(TmIcons.Info, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
        Text(label, style = MaterialTheme.typography.titleSmall, color = ink, maxLines = 1)
    }
}

/** The carousel's frame while the starred chats are still being asked: the same size, shimmering. */
@Composable
private fun TvFeaturedSkeleton(start: Dp, modifier: Modifier = Modifier) {
    val shimmer = rememberShimmer()
    Box(modifier) {
        SkeletonBox(
            shimmer,
            Modifier
                .align(Alignment.TopEnd)
                .fillMaxWidth(EDGE_SHARE)
                .fillMaxHeight()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                .drawWithContent {
                    drawContent()
                    // Clear at the start, where the words go, solid towards the end edge.
                    val across = if (layoutDirection == LayoutDirection.Rtl) {
                        Brush.horizontalGradient(0f to Color.Black, 0.55f to Color.Black, 1f to Color.Transparent)
                    } else {
                        Brush.horizontalGradient(0f to Color.Transparent, 0.45f to Color.Black, 1f to Color.Black)
                    }
                    drawRect(across, blendMode = BlendMode.DstIn)
                    drawRect(Brush.verticalGradient(0f to Color.Black, 0.6f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                },
            RectangleShape,
        )
        Column(
            Modifier.align(Alignment.BottomStart).padding(start = start, bottom = PAGE_PAD),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SkeletonBox(shimmer, Modifier.width(280.dp).height(28.dp), RoundedCornerShape(8.dp))
            SkeletonBox(shimmer, Modifier.width(180.dp).height(14.dp), RoundedCornerShape(7.dp))
            SkeletonBox(shimmer, Modifier.padding(top = 6.dp).width(160.dp).height(48.dp), CircleShape)
        }
    }
}

/** How tall the carousel is on a television under its heading: room below for the first row's titles. */
internal val TV_FEATURED_HEIGHT = 260.dp

/** How long a page is up before the carousel moves on. */
private const val ADVANCE_MS = 7_000

/** The inset of the words and the buttons from the picture's edge. */
private val PAGE_PAD = 24.dp

/** The share of the width the name and facts may take, over the start's fade. */
private const val TEXT_SHARE = 0.6f

/** Between the name and Play: the hero is the name and its two buttons. */
private val TITLE_GAP = 14.dp

/** How far down the picture fades in from the top under the TV top bar, as a share of the bar's height. */
private const val BAR_FADE_REACH = 1.7f

/** The most a title logo takes over the television's carousel. */
private val TV_LOGO_MAX_WIDTH = 340.dp
private val TV_LOGO_MAX_HEIGHT = 80.dp

/** The row of dots' own height: a 6 dp mark with room above and below. */
private val TV_DOTS_HEIGHT = 22.dp
