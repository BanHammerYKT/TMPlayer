package com.tmplayer.web.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.web.WebAppState
import com.tmplayer.web.data.ChatListKey
import com.tmplayer.web.data.WebChat
import com.tmplayer.web.data.WebVideo
import com.tmplayer.web.player.PlayerEvent
import com.tmplayer.web.player.WebPlayer
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Material's window size classes on the window's width, as the Android app reads them. */
enum class WidthClass { Compact, Medium, Expanded }

fun widthClass(width: Dp): WidthClass = when {
    width < 600.dp -> WidthClass.Compact
    width < 840.dp -> WidthClass.Medium
    else -> WidthClass.Expanded
}

private data class Dest(val route: Route, val label: String, val icon: ImageVector)

/** The signed in app: a bottom bar on a phone, a rail beside the content from 600 dp. */
@Composable
fun Shell(app: WebAppState) {
    val s = LocalStrings.current
    val nav = remember { Nav(Route.Home) }
    val dests = listOf(
        Dest(Route.Home, s.browseTabHome, WebIcons.Home),
        Dest(Route.Chats(), s.navChats, WebIcons.Chats),
        Dest(Route.Search, s.commonSearch, WebIcons.Search),
        Dest(Route.Settings, s.navSettings, WebIcons.Settings),
    )
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val size = widthClass(maxWidth)
        val top = nav.top
        val topLevel = dests.firstOrNull { it.route == top || (top is Route.Chats && it.route is Route.Chats) }
        if (size == WidthClass.Compact) {
            Box(Modifier.fillMaxSize()) {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Content(app, nav, top, bottomPadding = 88.dp)
                }
                if (topLevel != null) {
                    BottomBar(dests, topLevel.route, Modifier.align(Alignment.BottomCenter)) { nav.reset(it) }
                }
            }
        } else {
            Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Rail(dests, topLevel?.route) { nav.reset(it) }
                Box(Modifier.fillMaxHeight().width(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Column(Modifier.fillMaxSize()) { Content(app, nav, top, bottomPadding = 0.dp) }
            }
        }
    }
}

@Composable
private fun Content(app: WebAppState, nav: Nav, route: Route, bottomPadding: Dp) {
    val s = LocalStrings.current
    when (route) {
        is Route.Chats -> {
            TopTitle(s.navChats)
            ChatList(app, route.list, bottomPadding) { nav.push(Route.Chat(it.id)) }
        }
        is Route.Chat -> {
            val chat = app.chats.chat(route.id)
            TopTitle(chat?.title.orEmpty(), onBack = { nav.back() })
            if (chat != null) {
                ChatGrid(app, chat) { video, all -> play(app, video, all, chat) }
            }
        }
        Route.Home -> {
            TopTitle(s.browseTabHomeHeading)
            HomeScreen(app, bottomPadding) { nav.push(Route.Chat(it.id)) }
        }
        Route.Search -> {
            TopTitle(s.commonSearch)
            SearchScreen(app, bottomPadding) { nav.push(Route.Chat(it.id)) }
        }
        Route.Settings -> {
            TopTitle(s.navSettings)
            WebSettings(app)
        }
        else -> Placeholder(s.browseEmptyOther)
    }
}

@OptIn(ExperimentalTime::class)
fun play(app: WebAppState, video: WebVideo, all: List<WebVideo>, chat: WebChat) {
    val index = all.indexOfFirst { it.key == video.key }
    // The list is newest first; the next episode is the one posted after this one.
    val next = all.getOrNull(index - 1)
    val previous = all.getOrNull(index + 1)
    val start = app.resume.point(video.key)?.positionSec ?: 0.0
    val spec = WebPlayer.spec(
        video, app.prefs, start, video.title, chat.title, chat.title,
        hasNext = next != null, hasPrevious = previous != null, next = next,
    )
    WebPlayer.open(spec, app.prefs) { event ->
        val now = Clock.System.now().toEpochMilliseconds()
        app.resume.record(video.key, event.positionSec, event.durationSec, now)
        when (event) {
            is PlayerEvent.Next, is PlayerEvent.Ended -> next?.let { play(app, it, all, chat) }
            is PlayerEvent.Previous -> previous?.let { play(app, it, all, chat) }
            else -> Unit
        }
    }
}

@Composable
fun TopTitle(title: String, onBack: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = if (onBack == null) 16.dp else 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                Icon(WebIcons.Back, contentDescription = LocalStrings.current.commonBack)
            }
        }
        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Placeholder(text: String) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChatList(app: WebAppState, list: ChatListKey, bottomPadding: Dp, onOpen: (WebChat) -> Unit) {
    val s = LocalStrings.current
    val version by app.chats.version.collectAsState()
    val loading by app.chats.loading.collectAsState()
    val chats = remember(version, list) { app.chats.list(list) }
    if (chats.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            if (loading) CircularProgressIndicator() else Text(s.browseNoChats, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding)) {
            items(chats, key = { it.id }) { chat -> ChatRow(app, chat) { onOpen(chat) } }
        }
    }
}

@Composable
private fun BottomBar(dests: List<Dest>, selected: Route, modifier: Modifier, onPick: (Route) -> Unit) {
    Row(
        modifier
            .navigationBarsPadding()
            .padding(bottom = 12.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(28.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (d in dests) {
            val on = d.route == selected
            Column(
                Modifier
                    .clip(RoundedCornerShape(22.dp))
                    .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh)
                    .clickable { onPick(d.route) }
                    .padding(horizontal = 18.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(d.icon, contentDescription = null, tint = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(d.label, style = MaterialTheme.typography.labelSmall, color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Rail(dests: List<Dest>, selected: Route?, onPick: (Route) -> Unit) {
    Column(
        Modifier.fillMaxHeight().width(88.dp).padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        for (d in dests) {
            val on = d.route == selected
            Column(
                Modifier.clip(RoundedCornerShape(16.dp)).clickable { onPick(d.route) }.padding(vertical = 6.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.clip(RoundedCornerShape(16.dp))
                        .background(if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background)
                        .padding(horizontal = 18.dp, vertical = 4.dp),
                ) {
                    Icon(d.icon, contentDescription = null, tint = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(4.dp))
                Text(d.label, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
fun ChatRow(app: WebAppState, chat: WebChat, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ChatAvatar(app, chat, 48)
        Spacer(Modifier.size(12.dp))
        Text(
            chat.title.ifBlank { LocalStrings.current.chatsUntitled(chat.id) },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
        if (chat.unread > 0) {
            Box(
                Modifier.clip(CircleShape).background(if (chat.muted) MaterialTheme.colorScheme.surfaceContainerHighest else MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
                Text(
                    LocalStrings.current.formatter.number(chat.unread),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (chat.muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
fun ChatAvatar(app: WebAppState, chat: WebChat, sizeDp: Int) {
    val image by produceState<ImageBitmap?>(app.files.cached(chat.photoFileId) ?: app.files.decodeInline(chat.photoMini), chat.photoFileId) {
        if (chat.photoFileId != 0) app.files.image(chat.photoFileId, priority = 4)?.let { value = it }
    }
    Box(
        Modifier.size(sizeDp.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        val img = image
        if (img != null) {
            Image(img, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Text(
                chat.title.trim().take(1).uppercase(),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}
