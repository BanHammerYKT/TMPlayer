// The desktop's window furniture and its desktop only hosts, as the browser build has them. The
// page is the browser's window, so it has no buttons of its own to draw and nothing to drag; the
// updater, the downloads and the log export are the apps' (docs/WEB.md, "After the beta").
package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.theme.Tone

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

/** Storage settings are the apps' (where the cache and the downloads go). */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun StorageGroup(state: ShellState) {}

/** The developer rows: not in the browser build. */
internal object DevOptions {
    const val TAPS = 7
    val forced: Boolean = false
    fun tap() {}
}
