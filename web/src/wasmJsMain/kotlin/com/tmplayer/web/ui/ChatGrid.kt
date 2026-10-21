package com.tmplayer.web.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.data.SizeFilter
import com.tmplayer.web.WebAppState
import com.tmplayer.web.data.ChatKind
import com.tmplayer.web.data.WebChat
import com.tmplayer.web.data.WebSponsored
import com.tmplayer.web.data.WebSponsoredMessages
import com.tmplayer.web.data.WebVideo
import com.tmplayer.web.js.TmHost
import com.tmplayer.web.player.WebPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

/** How a chat's grid is ordered, as the apps' sort menu offers. */
enum class GridSort { Newest, Oldest, Name, Size, Unwatched }

private sealed interface GridEntry {
    data class Video(val video: WebVideo) : GridEntry
    data class Ad(val item: WebSponsored) : GridEntry
}

/**
 * One chat's videos, newest first, in pages as the grid scrolls; sorted, filtered by size and
 * searched as the apps' chat page is, with the channel's sponsored messages placed among them the
 * way Telegram asks.
 */
@Composable
fun ChatGrid(app: WebAppState, chat: WebChat, onPlay: (WebVideo, List<WebVideo>) -> Unit) {
    val s = LocalStrings.current
    val items = remember(chat.id) { mutableStateListOf<WebVideo>() }
    var nextVideo by remember(chat.id) { mutableStateOf(0L) }
    var nextDocument by remember(chat.id) { mutableStateOf(0L) }
    var loading by remember(chat.id) { mutableStateOf(true) }
    var done by remember(chat.id) { mutableStateOf(false) }
    var sort by remember { mutableStateOf(GridSort.Newest) }
    var hideSmall by remember { mutableStateOf(app.prefs.string("hideSmall") == "1") }
    var query by remember(chat.id) { mutableStateOf("") }
    var searching by remember(chat.id) { mutableStateOf(false) }
    val state = rememberLazyGridState()
    val scope = rememberCoroutineScope()
    val sponsored by produceState<WebSponsoredMessages.Batch?>(null, chat.id) {
        value = if (chat.kind == ChatKind.Channel) app.sponsored.load(chat.id) else null
    }

    suspend fun loadMore() {
        if (done) return
        loading = true
        val page = app.videos.page(chat.id, nextVideo, nextDocument, chat.protectedContent, s.mediaUntitledVideo)
        val known = items.mapTo(HashSet()) { it.key }
        items += page.items.filter { it.key !in known }
        nextVideo = page.nextVideo
        nextDocument = page.nextDocument
        done = page.done
        loading = false
    }

    LaunchedEffect(chat.id) { loadMore() }
    LaunchedEffect(state, chat.id) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
            .distinctUntilChanged()
            .filter { it >= items.size - 12 }
            .collect { if (!loading && query.isBlank()) loadMore() }
    }
    // A search asks Telegram, which finds a video by its caption as well as its name.
    val found by produceState<List<WebVideo>?>(null, query, chat.id) {
        if (query.isBlank()) {
            value = null
            return@produceState
        }
        delay(350)
        value = app.videos.searchIn(chat.id, query, chat.protectedContent, s.mediaUntitledVideo)
    }

    val shown = remember(items.size, items.lastOrNull(), sort, hideSmall, found, app.resume.version.value) {
        val base = found ?: items.toList()
        val filtered = if (hideSmall) base.filter { SizeFilter.matches(it.sizeBytes, SizeFilter.DEFAULT_MIN, SizeFilter.CEILING) } else base
        when (sort) {
            GridSort.Newest -> filtered
            GridSort.Oldest -> filtered.reversed()
            GridSort.Name -> filtered.sortedBy { it.title.lowercase() }
            GridSort.Size -> filtered.sortedByDescending { it.sizeBytes }
            GridSort.Unwatched -> filtered.sortedBy { app.resume.isWatched(it.key) }
        }
    }
    val entries = remember(shown, sponsored) { place(shown, sponsored) }

    Column(Modifier.fillMaxSize()) {
        GridTools(
            sort = sort,
            onSort = { sort = it },
            hideSmall = hideSmall,
            onHideSmall = {
                hideSmall = it
                app.prefs.setString("hideSmall", if (it) "1" else "0")
            },
            searching = searching,
            onSearching = {
                searching = it
                if (!it) query = ""
            },
            query = query,
            onQuery = { query = it },
        )
        Box(Modifier.fillMaxSize()) {
            if (entries.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    when {
                        loading || (query.isNotBlank() && found == null) -> CircularProgressIndicator()
                        query.isNotBlank() -> Text(s.browseNoMatch(query), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else -> Text(s.browseNoPlayableVideos, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 168.dp),
                    state = state,
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 104.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(
                        entries,
                        key = { e -> if (e is GridEntry.Video) e.video.key else "ad:" + (e as GridEntry.Ad).item.messageId },
                        span = { e -> if (e is GridEntry.Ad) GridItemSpan(maxLineSpan) else GridItemSpan(1) },
                    ) { e ->
                        when (e) {
                            is GridEntry.Video -> VideoCard(app, e.video) { onPlay(e.video, shown) }
                            is GridEntry.Ad -> SponsoredCard(app, e.item) {
                                scope.launch {
                                    app.sponsored.click(e.item, media = false)
                                    if (e.item.url.isNotBlank()) TmHost.openUrl(e.item.url)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/** Telegram's spacing rule: zero means one card after everything, else one every [between]. */
private fun place(videos: List<WebVideo>, batch: WebSponsoredMessages.Batch?): List<GridEntry> {
    val ads = batch?.items.orEmpty()
    if (ads.isEmpty()) return videos.map { GridEntry.Video(it) }
    val between = batch?.between ?: 0
    if (between == 0) return videos.map { GridEntry.Video(it) } + GridEntry.Ad(ads.first())
    val out = ArrayList<GridEntry>(videos.size + ads.size)
    var next = 0
    videos.forEachIndexed { i, v ->
        out += GridEntry.Video(v)
        if ((i + 1) % between == 0 && next < ads.size) out += GridEntry.Ad(ads[next++])
    }
    return out
}

@Composable
private fun GridTools(
    sort: GridSort,
    onSort: (GridSort) -> Unit,
    hideSmall: Boolean,
    onHideSmall: (Boolean) -> Unit,
    searching: Boolean,
    onSearching: (Boolean) -> Unit,
    query: String,
    onQuery: (String) -> Unit,
) {
    val s = LocalStrings.current
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                placeholder = { Text(s.gridSearchThisChat) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onSearching(false) }, modifier = Modifier.height(44.dp)) { Text(s.commonClose) }
        } else {
            FilterChip(selected = false, onClick = { onSearching(true) }, label = { Text(s.gridSearchThisChat) }, modifier = Modifier.height(36.dp))
            Box {
                FilterChip(
                    selected = sort != GridSort.Newest,
                    onClick = { menu = true },
                    label = { Text(s.gridSort + ": " + sortLabel(sort)) },
                    modifier = Modifier.height(36.dp),
                )
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    for (option in GridSort.entries) {
                        DropdownMenuItem(text = { Text(sortLabel(option)) }, onClick = {
                            onSort(option)
                            menu = false
                        })
                    }
                }
            }
            FilterChip(selected = hideSmall, onClick = { onHideSmall(!hideSmall) }, label = { Text(s.searchFilterHideSmall) }, modifier = Modifier.height(36.dp))
        }
    }
}

@Composable
private fun sortLabel(sort: GridSort): String {
    val s = LocalStrings.current
    return when (sort) {
        GridSort.Newest -> s.gridSortNewest
        GridSort.Oldest -> s.gridSortOldest
        GridSort.Name -> s.gridSortName
        GridSort.Size -> s.gridSortSize
        GridSort.Unwatched -> s.gridSortUnwatched
    }
}

@Composable
private fun SponsoredCard(app: WebAppState, item: WebSponsored, onClick: () -> Unit) {
    LaunchedEffect(item.messageId) {
        // On screen for a moment counts as seen, as in the apps.
        delay(1000)
        app.sponsored.markViewed(item)
    }
    val image by produceState<ImageBitmap?>(app.files.decodeInline(item.miniThumbnail), item.thumbnailFileId) {
        if (item.thumbnailFileId != 0) app.files.image(item.thumbnailFileId, priority = 1)?.let { value = it }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        image?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(64.dp).clip(RoundedCornerShape(8.dp)))
            Spacer(Modifier.size(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(item.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            if (item.title.isNotBlank()) Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            if (item.text.isNotBlank()) Text(item.text, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (item.sponsorInfo.isNotBlank()) Text(item.sponsorInfo, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.buttonText.isNotBlank()) {
            Spacer(Modifier.size(8.dp))
            Text(item.buttonText, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun VideoCard(app: WebAppState, video: WebVideo, onClick: () -> Unit) {
    val s = LocalStrings.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focused by interaction.collectIsFocusedAsState()
    // Held for a moment under the pointer or the focus: the first megabytes come ahead.
    LaunchedEffect(hovered || focused) {
        if (hovered || focused) {
            delay(250)
            WebPlayer.warm(video)
        }
    }
    val image by produceState<ImageBitmap?>(app.files.cached(video.thumbnailFileId) ?: app.files.decodeInline(video.miniThumbnail), video.thumbnailFileId) {
        if (video.thumbnailFileId != 0) app.files.image(video.thumbnailFileId, priority = 2)?.let { value = it }
    }
    val resume = app.resume.fraction(video.key)
    val watched = app.resume.isWatched(video.key)
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .hoverable(interaction)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, onClick = onClick),
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
        ) {
            image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            val badge = video.durationSec.takeIf { it > 0 }?.let { s.formatter.clock(it * 1000L) }.orEmpty()
            if (badge.isNotEmpty()) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (watched) {
                Text(
                    s.commonWatched,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            } else if (resume > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
                    Box(Modifier.fillMaxWidth(resume).height(3.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(video.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            s.formatter.size(video.sizeBytes),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(2.dp))
    }
}
