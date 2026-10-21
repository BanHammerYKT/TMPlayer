package com.tmplayer.web.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.tmplayer.web.WebAppState
import com.tmplayer.web.data.WebChat
import com.tmplayer.web.data.WebVideo
import com.tmplayer.web.player.WebPlayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/** One chat's videos, newest first, in pages as the grid scrolls. */
@Composable
fun ChatGrid(app: WebAppState, chat: WebChat, onPlay: (WebVideo, List<WebVideo>) -> Unit) {
    val s = LocalStrings.current
    val items = remember(chat.id) { mutableStateListOf<WebVideo>() }
    var nextVideo by remember(chat.id) { mutableStateOf(0L) }
    var nextDocument by remember(chat.id) { mutableStateOf(0L) }
    var loading by remember(chat.id) { mutableStateOf(true) }
    var done by remember(chat.id) { mutableStateOf(false) }
    val state = rememberLazyGridState()

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
            .collect { if (!loading) loadMore() }
    }

    Box(Modifier.fillMaxSize()) {
        if (items.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator() else Text(s.browseNoPlayableVideos, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 168.dp),
                state = state,
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(items, key = { it.key }) { video ->
                    VideoCard(app, video) { onPlay(video, items.toList()) }
                }
            }
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
    val resume = remember(video.key) { app.resume.fraction(video.key) }
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
            val badge = listOfNotNull(
                video.durationSec.takeIf { it > 0 }?.let { s.formatter.clock(it * 1000L) },
            ).joinToString("  ")
            if (badge.isNotEmpty()) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                        .background(Color(0xB3000000), RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
            if (resume > 0f) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0x66FFFFFF))) {
                    Box(Modifier.fillMaxWidth(resume).height(3.dp).background(MaterialTheme.colorScheme.primary))
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(video.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                s.formatter.size(video.sizeBytes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(2.dp))
    }
}
