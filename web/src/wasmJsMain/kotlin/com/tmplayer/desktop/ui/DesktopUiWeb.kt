// The desktop's window furniture and its desktop only hosts, as the browser build has them. The
// page is the browser's window, so it has no buttons of its own to draw and nothing to drag; the
// updater and the downloads are the apps' (docs/WEB.md, "After the beta"). Send logs hands the
// file to the browser's downloads, the checks run while the tab is open and notify through the
// Notification API, and the language card and What's new are the desktop's.
package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.collectAsState
import com.tmplayer.data.ChatGate
import com.tmplayer.data.EpisodeAlerts
import com.tmplayer.data.NewVideoAlerts
import com.tmplayer.data.Notice
import com.tmplayer.data.PieceAlerts
import com.tmplayer.data.SupportReminder
import com.tmplayer.data.Td
import com.tmplayer.data.WhatsNew
import com.tmplayer.i18n.L
import com.tmplayer.i18n.LanguageNotice
import com.tmplayer.ui.i18n.rememberLanguageNotice
import com.tmplayer.ui.i18n.rememberWhatsNew
import com.tmplayer.ui.theme.floatingSurface
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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

/**
 * The page's last log lines (RecentLog), scrubbed of names and ids, as a file in the browser's
 * downloads: what Settings, About saves too. False when there was nothing to save or it failed.
 */
internal suspend fun saveWebLogs(): Boolean {
    val lines = com.tmplayer.desktop.os.DesktopLog.recentLines(com.tmplayer.data.LogExport.MAX_LINES)
    if (lines.isEmpty()) return false
    return runCatching {
        val settings = com.tmplayer.desktop.DesktopServices.settings
        val known = if (settings != null) com.tmplayer.data.LogExport.knownNames(settings, com.tmplayer.desktop.DesktopServices.watched) else emptySet()
        val name = com.tmplayer.data.LogExport.fileName()
        TmHost.saveText(name, com.tmplayer.data.LogExport.report(webFeedbackFacts(), lines, known), "text/plain")
    }.getOrDefault(false)
}

/**
 * The desktop's two buttons under an error (a failed sign in, Telegram out of reach, the player's
 * error): Save logs, to the browser's downloads, and Report on GitHub with the page's facts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SendLogsActions(modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val scope = rememberCoroutineScope()
    val toast = com.tmplayer.ui.components.rememberToast()
    var busy by remember { mutableStateOf(false) }
    FlowRow(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedButton(
            enabled = !busy,
            onClick = {
                busy = true
                scope.launch {
                    val saved = saveWebLogs()
                    busy = false
                    toast(if (saved) s.logsSavedTo(com.tmplayer.data.LogExport.fileName()) else s.logsFailed)
                }
            },
        ) {
            Icon(TmIcons.Logs, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(s.logsSave)
        }
        TextButton(onClick = { com.tmplayer.desktop.os.OpenExternal.browse(com.tmplayer.data.Feedback.issueUrl(webFeedbackFacts())) }) {
            Icon(TmIcons.Bug, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(s.logsReportGithub)
        }
    }
}

/**
 * The desktop's checks (DesktopChecks.kt) while the tab is open, every [CHECK_EVERY_MS], each
 * behind its own switch: new videos in the starred chats, new episodes of shows under way, the
 * missing pieces of cut videos and the support ask. A browser cannot run them with the tab
 * closed, and there is no disk or updater to watch, so low storage and updates are not here.
 * What they find is said as a notification only while the viewer is not looking at the page and
 * has allowed notifications; a press brings the tab to the front and opens the chat.
 */
@Composable
fun DesktopChecks(state: ShellState) {
    LaunchedEffect(state) {
        delay(CHECK_FIRST_AFTER_MS)
        while (true) {
            runCatching { webCheckOnce(state) }.onFailure { com.tmplayer.platform.Logger.w(CHECKS_TAG, "check failed: ${it.message}") }
            delay(CHECK_EVERY_MS)
        }
    }
}

private suspend fun webCheckOnce(state: ShellState) {
    val settings = state.settings
    val allowed = TmHost.notifyPermission() == "granted"
    // As the desktop does while its window is in front: the checks still run, so what is seen now
    // is not said later, but nothing is posted.
    fun post(): Boolean = allowed && !TmHost.inFront()

    val newVideos = settings.noticeOnNow(Notice.NewVideos)
    val episodes = settings.noticeOnNow(Notice.NewEpisodes)
    val pieces = settings.noticeOnNow(Notice.MissingPieces)
    if (newVideos || episodes || pieces) {
        val session = withTimeoutOrNull(CHECK_CONNECT_WAIT_MS) { Td.awaitConnectedSession() }
        if (session != null) {
            val chats = ChatGate.awaitReady()
            if (newVideos) {
                val alert = NewVideoAlerts.check(session.client, settings, chats)
                if (alert != null && post()) {
                    TmHost.notifyTap(alert.title, alert.body.orEmpty(), "new-videos") { openChatFromNotice(state, alert.chatId) }
                }
            }
            if (episodes) {
                val alert = EpisodeAlerts.check(session.client, settings, state.watched, chats)
                if (alert != null && post()) {
                    TmHost.notifyTap(alert.title, alert.body.orEmpty(), "episodes") { openChatFromNotice(state, alert.chatId) }
                }
            }
            if (pieces) {
                val alerts = PieceAlerts.check(session.client, settings, chats, System.currentTimeMillis())
                if (post()) {
                    for ((i, alert) in alerts.withIndex()) {
                        TmHost.notifyTap(alert.title, alert.body.orEmpty(), "pieces-$i") { openChatFromNotice(state, alert.play?.chatId) }
                    }
                }
            }
        }
    }
    if (post()) {
        SupportReminder.claimNudgeRung(settings, System.currentTimeMillis())?.let { rung ->
            val star = rung == SupportReminder.NudgeRung.Star
            TmHost.notifyTap(
                if (star) L.notifyStarTitle else L.notifySponsorTitle,
                if (star) L.notifyStarBody else L.notifySponsorBody,
                "support",
            ) { SupportReminder.requestOpen() }
        }
    }
}

private fun openChatFromNotice(state: ShellState, chatId: Long?) {
    val chat = chatId?.let(state::chatOf)
    state.go(Destination.Chats)
    if (chat != null) state.openChat(chat)
}

private const val CHECKS_TAG = "WebChecks"

/** As on the desktop: about every 45 minutes, the first well after the start. */
private const val CHECK_EVERY_MS = 45L * 60 * 1000
private const val CHECK_FIRST_AFTER_MS = 3L * 60 * 1000
private const val CHECK_CONNECT_WAIT_MS = 60_000L

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

/**
 * The desktop's notices over the signed in page, never over the player: the one-time card saying
 * the page has followed the browser into a language, "What's new" once per release of highlights,
 * and the offer of the quick tour to an account signed in before it existed. The page is always
 * the newest build, so What's new goes by the highlights' own version ([WhatsNew.VERSION]): seen
 * once when it changes, and never on a first visit. No failed update card: a page has no updater.
 */
@Composable
fun ShellNotices(state: ShellState, modifier: Modifier = Modifier) {
    val s = LocalStrings.current
    val playing = state.nowPlaying != null
    val notice = rememberLanguageNotice(state.settings)
    val whatsNew = rememberWhatsNew(state.settings, WhatsNew.VERSION, hold = playing)
    var picking by remember { mutableStateOf(false) }
    val language = notice.language
    if (language != null && !playing) {
        Column(
            modifier.widthIn(max = 420.dp).floatingSurface().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(TmIcons.Info, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(22.dp))
                Text(s.languageNowIn(LanguageNotice.name(language)), style = MaterialTheme.typography.titleMedium)
            }
            Text(s.languageNowInBody, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                TextButton(onClick = {
                    notice.dismiss()
                    picking = true
                }) { Text(s.commonChange) }
                Button(onClick = notice.dismiss) { Text(s.languageKeep) }
            }
        }
    }
    if (picking) LanguagePopup(state.settings, onClose = { picking = false })
    if (whatsNew.showing && !playing) WhatsNewPopup(onClose = { whatsNew.showing = false })
    val tour by state.settings.tourState.collectAsState(initial = com.tmplayer.data.SettingsStore.TourState.Done)
    if (tour == com.tmplayer.data.SettingsStore.TourState.Never && language == null && !whatsNew.showing && !playing) {
        val scope = rememberCoroutineScope()
        ConfirmDialog(
            title = s.tourOfferTitle,
            message = s.tourOfferBody,
            confirmLabel = s.tourOfferTake,
            cancelLabel = s.deviceNotNow,
            destructive = false,
            focusConfirm = true,
            onConfirm = { scope.launch { runCatching { state.settings.openTour() } } },
            onDismiss = { scope.launch { runCatching { state.settings.markTourDone() } } },
        )
    }
}

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

/**
 * Pixels a strip moves for a wheel [delta] in the browser, where Compose hands on the page's
 * wheel event as it came: pixels in Chromium and Safari, lines in Firefox. Each is turned into
 * pixels as Compose for Web's own scrolling does (a line is 16 dp), so a strip moves as far for a
 * step of the wheel as the page under it does, rather than the desktop's 64 per step times a
 * hundred pixels.
 */
internal fun wheelPixels(delta: Float, density: Float): Float = when (lastWheelMode()) {
    WHEEL_LINES -> delta * LINE_DP * density
    WHEEL_PAGES -> delta * PAGE_PX * density
    else -> delta * density
}

/** The deltaMode of the newest wheel event the page has seen, kept by a listener added once. */
@JsFun(
    """() => {
        if (!window.__tmWheelWatch) {
            window.__tmWheelWatch = true;
            window.__tmWheelMode = 0;
            window.addEventListener('wheel', (e) => { window.__tmWheelMode = e.deltaMode; }, { capture: true, passive: true });
        }
        return window.__tmWheelMode;
    }""",
)
private external fun lastWheelMode(): Int

private const val WHEEL_LINES = 1
private const val WHEEL_PAGES = 2
private const val LINE_DP = 16f
private const val PAGE_PX = 600f
