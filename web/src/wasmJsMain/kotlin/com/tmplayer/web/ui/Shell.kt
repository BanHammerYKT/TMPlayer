package com.tmplayer.web.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tmplayer.web.WebAppState
import com.tmplayer.web.data.ChatListKey
import com.tmplayer.web.data.WebChat

/** The signed in app. Phase 1: the chat list; the full shells (phone and desktop) build on it. */
@Composable
fun Shell(app: WebAppState) {
    val s = LocalStrings.current
    val version by app.chats.version.collectAsState()
    val loading by app.chats.loading.collectAsState()
    val chats = remember(version) { app.chats.list(ChatListKey.Main) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Text(
            s.navChats,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
        if (chats.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (loading) CircularProgressIndicator() else Text(s.webChatsEmpty)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(chats, key = { it.id }) { chat -> ChatRow(app, chat) {} }
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
        Text(chat.title.ifBlank { LocalStrings.current.chatsUntitled(chat.id) }, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
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
    Spacer(Modifier.height(0.dp))
}
