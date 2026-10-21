// The desktop's window furniture and its desktop only hosts, as the browser build has them. The
// page is the browser's window, so it has no buttons of its own to draw and nothing to drag; the
// updater and the downloads are the apps' (docs/WEB.md, "After the beta"); the log export is in
// Settings, About (SettingsPagesWeb.kt).
package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.theme.Tone
import com.tmplayer.web.js.TmHost
import kotlinx.coroutines.await
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The window's own title bar buttons: the browser draws its own, so there are none here. */
@Stable
class WindowChrome private constructor()

val LocalWindowChrome = compositionLocalOf<WindowChrome?> { null }

@Composable
fun Modifier.windowDrag(): Modifier = this

@Suppress("UNUSED_PARAMETER")
@Composable
fun WindowControls(modifier: Modifier = Modifier, onArt: Boolean = false) {}

@Composable
internal fun RegisterWindowControls() {}

/** Saving the logs to a file: the browser keeps them in its console. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun SendLogsActions(modifier: Modifier = Modifier) {}

/** The desktop's new video and sponsor checks: not in the browser beta. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun DesktopChecks(state: ShellState) {}

/** An update in the side bar: the page is always the newest build. */
data class NavUpdate(val label: String, val version: String)

@Suppress("UNUSED_PARAMETER")
@Composable
fun rememberNavUpdate(state: ShellState): NavUpdate? = null

@Suppress("UNUSED_PARAMETER")
@Composable
fun UpdatePopupTrigger(state: ShellState, item: NavUpdate?) {}

@Suppress("UNUSED_PARAMETER")
@Composable
fun UpdatePopupHost(state: ShellState) {}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun DownloadToasts(state: ShellState) {}

@Composable
internal fun rememberDownloadsInFlight(): Int = 0

/** Downloads are the apps': the page says so where the desktop lists them. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun DownloadsPage(state: ShellState) {
    val s = LocalStrings.current
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Text(s.webNeedsAppTitle, color = Tone.muted, textAlign = TextAlign.Center)
    }
}

/** The language card, What's new and the failed update notice: not in the browser beta. */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ShellNotices(state: ShellState, modifier: Modifier = Modifier) {}

/**
 * Storage in the browser. The page keeps a playing video in memory only (web/tdweb/sparse-fs.js),
 * so Low storage mode is on and cannot be switched off, and there is no cache to size, no
 * download folder to pick and no downloads to list: those rows are the apps'. What is the
 * browser's to say is here: how much of its storage the page uses, and whether it has agreed to
 * keep it, since a browser that clears it under pressure signs the viewer out.
 */
@Composable
internal fun StorageGroup(state: ShellState) {
    val s = LocalStrings.current
    val toast = com.tmplayer.ui.components.rememberToast()
    val scope = rememberCoroutineScope()
    var kept by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) { kept = runCatching { TmHost.persisted().await<JsBoolean>().toBoolean() }.getOrDefault(false) }
    val used by produceState<Pair<Long, Long>?>(initialValue = null) {
        value = runCatching {
            val o = Json.parseToJsonElement(TmHost.estimate().await<JsString>().toString()).jsonObject
            val usage = o["usage"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
            val quota = o["quota"]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
            usage to quota
        }.getOrNull()
    }

    Setting(s.settingsLowStorage, s.webLowStorageAlways, icon = TmIcons.Archive) {
        Switch(checked = true, onCheckedChange = null, enabled = false)
    }
    val on = kept == true
    Action(
        s.webKeepStorage,
        if (on) s.webKeepStorageOn else s.webKeepStorageOff,
        TmIcons.Lock,
        end = RowEnd.None,
        enabled = kept == false,
    ) {
        // Asked for in the press itself, which some browsers need before they answer.
        val asked = TmHost.persist()
        scope.launch {
            val granted = runCatching { asked.await<JsBoolean>().toBoolean() }.getOrDefault(false)
            kept = granted
            if (!granted) toast(s.webKeepStorageRefused)
        }
    }
    used?.takeIf { it.second > 0 }?.let { (usage, quota) ->
        Setting(
            s.webStorageTitle,
            s.webStorageUsed(com.tmplayer.data.StorageRelocationPlan.size(usage), com.tmplayer.data.StorageRelocationPlan.size(quota)),
            icon = TmIcons.Download,
        ) {}
    }
    Text(
        s.webStorageBody,
        style = MaterialTheme.typography.bodyMedium,
        color = Tone.muted,
        modifier = Modifier.padding(start = SETTING_INSET, end = SETTING_INSET, top = 12.dp),
    )
}

/**
 * Signing out of the page: the desktop's prompt (SignOut.kt) without its downloads, which the
 * browser does not keep. What the account leaves behind goes as it does on the desktop; this is
 * the one place the page logs out of Telegram.
 */
@Composable
internal fun SignOutDialog(state: ShellState, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    ConfirmDialog(
        title = s.signoutTitle,
        message = s.webSignoutBody,
        confirmLabel = s.signoutConfirm,
        onDismiss = onDismiss,
        onConfirm = {
            onDismiss()
            scope.launch {
                val settings = state.settings
                runCatching { com.tmplayer.data.Td.clearMediaCache() }
                runCatching { settings.clearEverything(keepDownloads = false) }
                runCatching { state.watched.clear() }
                runCatching { state.watched.watchLater.clear() }
                state.bookmarks?.let { runCatching { it.clear() } }
                state.showChats(com.tmplayer.ui.browse.BrowseSection.of(com.tmplayer.ui.browse.BrowseTab.Chats))
                com.tmplayer.data.Td.logOut()
            }
        },
    )
}

/** The developer rows: not in the browser build. */
internal object DevOptions {
    const val TAPS = 7
    val forced: Boolean = false
    fun tap() {}
}
