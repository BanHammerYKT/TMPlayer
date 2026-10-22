package com.tmplayer.ui.browse

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.Bookmark
import com.tmplayer.data.DetailAction
import com.tmplayer.data.MediaFacts
import com.tmplayer.data.MediaItem
import com.tmplayer.data.WatchPoint
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.components.BackdropSource
import com.tmplayer.ui.components.IconTip
import com.tmplayer.ui.components.SwapIcon
import com.tmplayer.ui.components.TipSide
import com.tmplayer.ui.components.pressScale
import com.tmplayer.ui.components.rememberCover
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.online.MetaCastRow
import com.tmplayer.ui.online.MetaCredit
import com.tmplayer.ui.online.MetaFactsLines
import com.tmplayer.ui.online.MoreLikeThisRow
import com.tmplayer.ui.online.TrailerHost
import com.tmplayer.ui.online.TrailerIcon
import com.tmplayer.ui.online.TrailerQr
import com.tmplayer.ui.online.rememberMatched
import com.tmplayer.ui.online.rememberMetaExtras
import com.tmplayer.ui.online.rememberTrailerLauncher
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Danger
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.collectIsFocusVisibleAsState
import com.tmplayer.ui.theme.focusRing
import com.tmplayer.ui.theme.rememberReducedMotion
import com.tmplayer.ui.theme.trackedFor

/*
 * A video's detail drawn for a large window: what the desktop's sheet (and a wide browser
 * window, which draws the desktop's screens) shows over the page. The phone keeps its own page
 * ([MediaDetailScreen]) and the television its pane ([MediaDetailPanel]).
 *
 * The order is a title page's: the backdrop across the top with the title logo (or the name, set
 * large) on its faded foot, the facts and the scores, Play or Resume as the one bright pill with
 * the everyday actions as round buttons beside it, then the overview at a reading measure beside
 * the file's own facts, the copies and bookmarks, the cast, the seasons and episodes, "More like
 * this", and the things done now and then at the foot.
 */

/** A video's detail for a large window; see the file's note. Same inputs as [MediaDetailPanel]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WideDetailPanel(
    item: MediaItem,
    chatTitle: String,
    watched: WatchPoint?,
    finished: Boolean,
    rows: List<DetailRow>,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    firstAction: FocusRequester? = null,
    onOpenItem: ((MediaItem) -> Unit)? = null,
    bookmarks: List<Bookmark> = emptyList(),
    onOpenBookmark: ((Bookmark) -> Unit)? = null,
    onPickCopy: ((MediaItem) -> Unit)? = onOpenItem,
    copyStarted: (MediaItem) -> Boolean = { false },
) {
    val s = LocalStrings.current
    val corrections by com.tmplayer.data.VideoCorrections.state.collectAsState()
    val parsed = remember(item, corrections) { com.tmplayer.data.SeriesShelf.episodeOf(item, corrections) }
    val meta = rememberMatched(item)?.info
    // A match's own title, in the UI language; else the name as read, its joiners made spaces.
    val heading = meta?.title?.takeIf { it.isNotBlank() }
        ?: parsed.title.ifBlank { item.title }.let { if (' ' in it) it else it.replace(WIDE_JOINERS, " ") }
    val eyebrow = listOfNotNull(parsed.episodeCode, meta?.episode?.name?.takeIf { it.isNotBlank() }).joinToString("  ·  ")
    val metaOverview = meta?.let { it.episode?.overview?.takeIf { o -> o.isNotBlank() } ?: it.overview.takeIf { o -> o.isNotBlank() } }
    val overview = metaOverview ?: item.caption.takeIf { it.isNotBlank() }
    val extras = rememberMetaExtras(meta)
    val trailers = rememberTrailerLauncher()
    val cover = rememberCover(item)
    val art = rememberArtItem(item)
    val source = remember(item.id, art, cover, meta?.wideUrl) { BackdropSource(art.miniThumbnail, art.thumbnailFileId, cover, meta?.wideUrl) }
    val facts = remember(item) { MediaFacts.of(item) }
    val online = OnlineMetadata.current
    val lookupsOn = online?.settings?.collectAsState()?.value?.enabled == true
    val metadataOff = online != null && !lookupsOn
    val scroll = rememberScrollState()
    val reduced = rememberReducedMotion()
    val episodesAt = remember { BringIntoViewRequester() }
    val onEpisodes = episodesButton(item, meta, episodesAt)

    val primary = rows.firstOrNull()
    // Beside Play: the episodes, the everyday switches and the trailer.
    val quick = buildList {
        if (onEpisodes != null) add(DetailRow(DetailAction.Play, s.detailEpisodes, TmIcons.Episodes, onSelect = onEpisodes))
        addAll(rows.drop(1).filter { it.action in WIDE_HEADLINE })
        extras?.trailer?.let { trailer -> add(DetailRow(DetailAction.Play, s.metadataTrailer, TrailerIcon, onSelect = { trailers.open(trailer) })) }
    }
    // At the foot: the chat, the corrections, the hand offs, the switch for posters while off.
    val rest = buildList {
        addAll(rows.drop(1).filter { it.action !in WIDE_HEADLINE })
        if (metadataOff) add(DetailRow(DetailAction.Play, s.metadataTurnOn, TmIcons.Info, s.metadataTurnOnDetail, onSelect = { online?.setEnabled(true) }))
    }

    BoxWithConstraints(modifier.fillMaxSize()) {
        val roomy = maxWidth >= ROOMY
        val side = if (roomy) 48.dp else 32.dp
        // 16:9 of the sheet's width while that leaves Play on the first screen.
        val heroHeight = minOf(maxWidth * (9f / 16f), maxHeight * 0.56f).coerceAtLeast(240.dp)
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth().height(heroHeight).clipToBounds()) {
                PageBackdrop(
                    source,
                    fade = heroHeight * 0.7f,
                    modifier = Modifier
                        .fillMaxSize()
                        // Half the page's speed, so the picture drifts away under the title.
                        .graphicsLayer { if (!reduced) translationY = scroll.value * PARALLAX },
                )
                Column(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth(if (roomy) 0.72f else 0.86f)
                        .padding(horizontal = side),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (eyebrow.isNotEmpty()) {
                        Text(eyebrow, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Tone.accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                    com.tmplayer.ui.online.TitleArt(
                        meta,
                        maxWidth = if (roomy) 460.dp else 380.dp,
                        maxHeight = if (roomy) 150.dp else 120.dp,
                        modifier = Modifier.clearAndSetSemantics {
                            heading()
                            contentDescription = heading
                        },
                    ) {
                        val style = if (roomy) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displaySmall
                        Text(heading, style = style.trackedFor(heading), fontWeight = FontWeight.Bold, color = Tone.text, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            Column(
                Modifier.fillMaxWidth().padding(start = side, end = side, top = 20.dp, bottom = 40.dp),
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(22.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FactLine(
                            listOfNotNull(
                                (parsed.year ?: meta?.year)?.let { Fact(it.toString()) },
                                item.durationSec.takeIf { it > 0 }?.let { Fact(s.formatter.duration(it.toLong())) },
                                facts.resolution?.let { Fact(it, chip = true) },
                                item.sizeBytes.takeIf { it > 0 }?.let { Fact(com.tmplayer.data.SplitPieces.sizeFact(item, s.formatter.size(it))) },
                                chatTitle.takeIf { it.isNotBlank() }?.let { Fact(it) },
                                Fact(s.detailWatchedLine, accent = true).takeIf { finished },
                            ),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        MetaFactsLines(extras)
                        ResumeLine(watched, finished, item.durationSec)
                        PiecesMissingLine(item)
                    }
                    if (!item.canBeSaved) {
                        Text(
                            s.detailProtected,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Tone.muted,
                            modifier = Modifier
                                .clip(RoundedCornerShape(Corner.Small))
                                .background(Tone.surfaceHigh)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        primary?.let { HeroPill(it.label, it.icon, it.onSelect, focusRequester = firstAction, large = true) }
                        FlowRow(
                            Modifier.weight(1f, fill = false),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            quick.forEach { WideIconAction(it.label, it.icon, it.onSelect, destructive = it.destructive) }
                        }
                    }
                    trailers.unopened?.let { TrailerQr(it, Modifier.padding(vertical = 4.dp)) }
                    TrailerHost(trailers)
                }

                // The overview at a reading measure, the copies and bookmarks under it, and the
                // file's own facts beside it where the sheet is wide enough, else under it.
                val story: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        overview?.let { text ->
                            Column(Modifier.widthIn(max = READING), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Synopsis(text, style = MaterialTheme.typography.bodyLarge, lines = 5)
                                if (metaOverview != null && meta != null) MetaCredit(meta)
                            }
                        }
                        Versions(item, onPickCopy, chatTitle, copyStarted)
                        TelegramQualityLine(item)
                        DetailBookmarks(bookmarks, onOpenBookmark)
                    }
                }
                val file: @Composable () -> Unit = {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(s.detailHeading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = Tone.text)
                        DetailFacts(item, facts, s, overview = null, showSynopsis = false)
                    }
                }
                if (roomy) {
                    Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                        Box(Modifier.weight(1f)) { story() }
                        Box(Modifier.width(FILE_COLUMN)) { file() }
                    }
                } else {
                    story()
                    file()
                }

                if (meta != null && extras != null) MetaCastRow(extras.cast)
                SeasonsSection(item, meta, extras, onOpenItem, Modifier.bringIntoViewRequester(episodesAt))
                if (meta != null && extras != null) MoreLikeThisRow(meta, extras, onOpenItem)

                if (rest.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        HorizontalDivider(color = Tone.outline)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            rest.forEach { WideTextAction(it.label, it.icon, it.onSelect, destructive = it.destructive) }
                        }
                    }
                }
                // Where the name came from: the provider's match and what backed it, or the file.
                if (lookupsOn) {
                    Text(
                        meta?.let { m -> m.year?.let { s.detailMatchedAs(m.title, it.toString()) } ?: s.detailMatchedAsNoYear(m.title) } ?: s.detailFromFileName,
                        style = MaterialTheme.typography.labelMedium,
                        color = Tone.muted,
                    )
                }
            }
        }
        PageDiscButton(TmIcons.Close, s.detailClose, onClose, Modifier.align(Alignment.TopStart).padding(16.dp))
    }
}

/**
 * One of the everyday actions beside Play on a large window: a round button as tall as the pill,
 * outlined, its name in a tip under the pointer and read out. A switch's icon turns into its
 * other one in place, so the press shows.
 */
@Composable
internal fun WideIconAction(label: String, icon: ImageVector, onClick: () -> Unit, destructive: Boolean = false) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val ink = when {
        focused -> Tone.onFocusFill
        destructive -> Tone.danger
        else -> Tone.text
    }
    IconTip(label, side = TipSide.Below) {
        Box(
            Modifier
                .pressScale(interactions)
                .size(WIDE_ACTION)
                .clip(CircleShape)
                .background(if (focused) (if (destructive) Danger else Tone.focusFill) else Tone.surfaceHigh)
                .border(1.dp, if (focused) Color.Transparent else Tone.outline, CircleShape)
                .focusRing(focused, CircleShape)
                .clickable(interactionSource = interactions, indication = LocalIndication.current, onClick = onClick)
                .semantics {
                    contentDescription = label
                    role = Role.Button
                },
            contentAlignment = Alignment.Center,
        ) {
            SwapIcon(icon, ink, size = 24.dp)
        }
    }
}

/** One of the things done now and then, at the foot of the large detail: an outlined pill with its full name. */
@Composable
private fun WideTextAction(label: String, icon: ImageVector, onClick: () -> Unit, destructive: Boolean = false) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusVisibleAsState()
    val ink = when {
        focused -> Tone.onFocusFill
        destructive -> Tone.danger
        else -> Tone.text
    }
    Row(
        Modifier
            .pressScale(interactions, com.tmplayer.ui.components.Micro.PillPressScale)
            .heightIn(min = 40.dp)
            .clip(CircleShape)
            .background(if (focused) (if (destructive) Danger else Tone.focusFill) else Color.Transparent)
            .border(1.dp, if (focused) Color.Transparent else Tone.outline, CircleShape)
            .focusRing(focused, CircleShape)
            .clickable(interactionSource = interactions, indication = LocalIndication.current, onClick = onClick)
            .padding(start = 14.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, tint = if (focused || destructive) ink else Tone.muted, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = ink, maxLines = 1)
    }
}

/**
 * The actions a large window keeps beside Play: the phone's everyday ones ([HEADLINE_ACTIONS])
 * and Start over, which sits by Resume as a streaming app's does.
 */
private val WIDE_HEADLINE = HEADLINE_ACTIONS + DetailAction.StartOver

/** From this width the detail sets its words larger and puts the file's facts beside the overview. */
private val ROOMY = 880.dp

/** About 65 characters of the overview's type: the measure it reads best at. */
private val READING = 640.dp

/** The file's facts beside the overview. */
private val FILE_COLUMN = 300.dp

/** The round actions beside Play: as tall as the large pill. */
internal val WIDE_ACTION = 56.dp

private val WIDE_JOINERS = Regex("[-_.]+")
