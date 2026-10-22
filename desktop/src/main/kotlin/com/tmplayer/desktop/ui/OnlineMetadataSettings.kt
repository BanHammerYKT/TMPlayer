package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.tmplayer.i18n.Translator
import com.tmplayer.online.MetaWords
import com.tmplayer.online.OmdbState
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.online.TmdbState
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.icons.TmIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings, Posters and details, on the desktop and the web: the same rows as phone and TV (the
 * switch, the TMDB and OMDb keys with the viewer's own as an override, the cache with its purge), drawn as this page's
 * own rows.
 */
@Composable
internal fun OnlineMetadataGroup(toggleModifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return
    val settings by online.settings.collectAsState()
    val state = online.tmdbState(settings)
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var editingKey by remember { mutableStateOf(false) }
    var editingOmdbKey by remember { mutableStateOf(false) }
    val omdbState = online.omdbState(settings)
    var clearingCache by remember { mutableStateOf(false) }
    var bytes by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { bytes = withContext(Dispatchers.IO) { online.cacheBytes() } }

    Group(s.metadataTitle)
    Toggle(
        s.metadataToggle,
        if (settings.enabled) s.metadataToggleOn else s.metadataToggleOff,
        settings.enabled,
        toggleModifier,
        icon = TmIcons.Image,
    ) { online.setEnabled(it) }
    // The player's skip intro button rides on lookups, so it is offered only while they are on.
    if (settings.enabled) {
        Toggle(s.metadataSkipTimes, s.metadataSkipTimesDetail, settings.skipTimes, icon = TmIcons.Episodes) {
            online.setSkipTimes(it)
        }
    }
    Action(
        s.metadataKey,
        MetaWords.key(state),
        TmIcons.Lock,
        titleColor = if (state == TmdbState.AppKey || state == TmdbState.OwnKey) Color.Unspecified else Tone.caution,
    ) { editingKey = true }
    Action(
        s.metadataOmdbKey,
        MetaWords.omdbKey(omdbState),
        TmIcons.Lock,
        titleColor = if (omdbState == OmdbState.OwnKeyRefused) Tone.caution else Color.Unspecified,
    ) { editingOmdbKey = true }
    Action(
        s.metadataClearCache,
        if (bytes > 0) s.metadataCacheDetail(Translator.messages.formatter.size(bytes)) else s.metadataCacheEmpty,
        TmIcons.Delete,
        end = RowEnd.None,
        enabled = bytes > 0,
    ) { clearingCache = true }
    if (clearingCache) {
        ConfirmDialog(
            title = s.confirmMetadataCacheTitle,
            message = s.confirmMetadataCacheMessage(Translator.messages.formatter.size(bytes)),
            confirmLabel = s.commonClear,
            onDismiss = { clearingCache = false },
            onConfirm = {
                clearingCache = false
                scope.launch {
                    withContext(Dispatchers.IO) { online.purge() }
                    bytes = 0
                    toast(s.metadataCacheCleared)
                }
            },
        )
    }

    if (editingKey) MetadataKeyDialog(onClose = { editingKey = false })
    if (editingOmdbKey) OmdbKeyDialog(onClose = { editingOmdbKey = false })
}

@Composable
internal fun MetadataKeyDialog(onClose: () -> Unit) {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return
    var key by remember { mutableStateOf(online.store.now.ownKey) }
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.metadataKeyTitle) },
        text = {
            Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(MetaWords.keyMessage(online.buildHasKey))
                OutlinedTextField(key, { key = it }, label = { Text(s.metadataKey) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(MetaWords.key(online.tmdbState()), style = MaterialTheme.typography.bodySmall, color = Tone.muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                online.setOwnKey(key)
                onClose()
            }) { Text(s.commonOk) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(s.commonCancel) } },
    )
}

/** The viewer's own OMDb key, for IMDb's score. Empty goes back to the build's. */
@Composable
internal fun OmdbKeyDialog(onClose: () -> Unit) {
    val s = LocalStrings.current
    val online = OnlineMetadata.current ?: return
    var key by remember { mutableStateOf(online.store.now.omdbKey) }
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.metadataOmdbKeyTitle) },
        text = {
            Column(Modifier.width(420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(MetaWords.omdbKeyMessage(online.buildHasOmdbKey))
                OutlinedTextField(key, { key = it }, label = { Text(s.metadataOmdbKey) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(MetaWords.omdbKey(online.omdbState()), style = MaterialTheme.typography.bodySmall, color = Tone.muted)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                online.setOmdbKey(key)
                onClose()
            }) { Text(s.commonOk) }
        },
        dismissButton = { TextButton(onClick = onClose) { Text(s.commonCancel) } },
    )
}
