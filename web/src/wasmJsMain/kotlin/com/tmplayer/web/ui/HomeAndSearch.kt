package com.tmplayer.web.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tmplayer.web.WebAppState
import com.tmplayer.web.data.ChatKind
import com.tmplayer.web.data.ChatListKey
import com.tmplayer.web.data.WebChat
import com.tmplayer.web.data.WebVideo
import kotlinx.coroutines.delay

/**
 * Home: Continue watching first, then the newest videos of the chats at the top of the list, a
 * row each, as the apps' Home has them until the shared HomeRows rules arrive with :core.
 */
@Composable
fun HomeScreen(app: WebAppState, bottomPadding: Dp, onOpenChat: (WebChat) -> Unit) {
    val s = LocalStrings.current
    val resumeVersion by app.resume.version.collectAsState()
    val chatsVersion by app.chats.version.collectAsState()
    val continuing by produceState<List<WebVideo>?>(null, resumeVersion) {
        value = app.resume.recent().take(20).mapNotNull { (key, _) -> app.lookups.video(key, s.mediaUntitledVideo) }
    }
    val rowChats = remember(chatsVersion) {
        app.chats.list(ChatListKey.Main).filter { it.kind == ChatKind.Channel || it.kind == ChatKind.Group || it.kind == ChatKind.Saved }.take(8)
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottomPadding + 16.dp)) {
        item { InstallBanner(app) }
        val cont = continuing
        if (!cont.isNullOrEmpty()) {
            item { RowTitle(s.homeRowContinue) }
            item { VideoRow(app, cont) { video -> app.chats.chat(video.chatId)?.let { play(app, video, cont, it) } } }
        }
        items(rowChats, key = { it.id }) { chat -> ChatVideoRow(app, chat, onOpenChat) }
    }
}

@Composable
private fun RowTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
    )
}

@Composable
private fun VideoRow(app: WebAppState, videos: List<WebVideo>, onPlay: (WebVideo) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        items(videos, key = { it.key }) { video ->
            Box(Modifier.width(200.dp)) { VideoCard(app, video) { onPlay(video) } }
        }
    }
}

@Composable
private fun ChatVideoRow(app: WebAppState, chat: WebChat, onOpenChat: (WebChat) -> Unit) {
    val s = LocalStrings.current
    val videos by produceState<List<WebVideo>?>(null, chat.id) {
        value = app.videos.page(chat.id, 0, 0, chat.protectedContent, s.mediaUntitledVideo, limit = 12).items.take(12)
    }
    val list = videos
    if (list.isNullOrEmpty()) return
    Column {
        Box(Modifier.fillMaxWidth()) {
            androidx.compose.material3.TextButton(onClick = { onOpenChat(chat) }, modifier = Modifier.padding(start = 4.dp, top = 8.dp)) {
                Text(chat.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        VideoRow(app, list) { video -> play(app, video, list, chat) }
    }
}

/** Search: chats by name as you type, and videos in every chat by name or caption. */
@Composable
fun SearchScreen(app: WebAppState, bottomPadding: Dp, onOpenChat: (WebChat) -> Unit) {
    val s = LocalStrings.current
    var query by remember { mutableStateOf("") }
    // What Telegram is asked: the typed words once they rest (SearchSettle), at once on the
    // keyboard's search key, and cleared at once. One letter is not asked for while typing.
    var settled by remember { mutableStateOf("") }
    LaunchedEffect(query) {
        val clean = query.trim()
        if (clean.isEmpty()) {
            settled = ""
            return@LaunchedEffect
        }
        if (clean == settled || !com.tmplayer.data.GlobalSearch.searchable(clean)) return@LaunchedEffect
        delay(com.tmplayer.data.SearchSettle.MS)
        settled = clean
    }
    val chatsVersion by app.chats.version.collectAsState()
    val chats = remember(settled, chatsVersion) {
        if (settled.isBlank()) emptyList() else app.chats.list(ChatListKey.Main).filter { it.title.contains(settled.trim(), ignoreCase = true) }.take(8)
    }
    // Keyed by the settled words, so the same words are asked once and a newer search cancels
    // the one before. What was found stays listed until the new answer replaces it; the spinner
    // is for a first search only, and a cleared field empties the list at once.
    val videos by produceState<List<WebVideo>?>(emptyList(), settled) {
        if (settled.isBlank()) {
            value = emptyList()
            return@produceState
        }
        if (value.isNullOrEmpty()) value = null
        value = app.lookups.search(settled, s.mediaUntitledVideo)
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize()) {
        val held = com.tmplayer.ui.components.rememberHeldText(query)
        OutlinedTextField(
            value = held.value,
            onValueChange = { held.edit(it, { query = it }) },
            placeholder = { Text(s.searchFieldHint) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { settled = query.trim() }),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).focusRequester(focus),
        )
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 168.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = bottomPadding + 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(chats, key = { "c" + it.id }, span = { GridItemSpan(maxLineSpan) }) { chat -> ChatRow(app, chat) { onOpenChat(chat) } }
            val found = videos
            if (found == null) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                }
            } else {
                items(found, key = { it.key }) { video ->
                    VideoCard(app, video) { app.chats.chat(video.chatId)?.let { play(app, video, found, it) } }
                }
                if (found.isEmpty() && chats.isEmpty() && settled.isNotBlank()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(s.browseEmptyNoMatch(settled), color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(24.dp))
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(0.dp))
}
