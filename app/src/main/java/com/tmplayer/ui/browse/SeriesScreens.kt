package com.tmplayer.ui.browse

import com.tmplayer.ui.components.TopMessageHost
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.tmplayer.data.MediaItem
import com.tmplayer.data.Series
import com.tmplayer.data.SeriesProgress
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv

/**
 * A show's tile in a chat's grid or a row on Home: the frame a video's tile has ([VideoTile]), with
 * the show's art and "2 seasons · 18 eps" under its name, as a 2:3 [poster] card or a 16:9 one.
 * [badge] goes in the art's top end corner.
 */
@Composable
internal fun SeriesCard(
    series: Series,
    progress: SeriesProgress,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dense: Boolean = false,
    onFocused: () -> Unit = {},
    badge: (@Composable () -> Unit)? = null,
    /** A 2:3 poster card, in a grid of films and shows; a 16:9 card in a row of videos. */
    poster: Boolean = false,
    /** Episodes posted since the viewer last watched the show: a "New" badge on the picture. */
    fresh: Boolean = false,
) {
    val summary = listOfNotNull(seriesSummary(series), seriesProgressLine(progress)).joinToString("  ·  ")
    VideoTile(
        title = series.title,
        onClick = onClick,
        onFocused = onFocused,
        dense = dense,
        poster = poster,
        modifier = modifier,
        meta = { ink ->
            // A show already begun keeps its line in the accent; otherwise the tile's own colour.
            val accent = Tone.accent
            val begun = progress.watched > 0
            TileCaption(
                summary,
                if (poster) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                remember(begun, accent, ink) { if (begun) androidx.compose.ui.graphics.ColorProducer { accent } else ink },
            )
        },
    ) {
        // VideoTile already rounds the art's corners.
        SeriesArt(series, progress, Modifier.fillMaxSize(), compact = dense, badge = badge, poster = poster, fresh = fresh, clip = false)
    }
}

/**
 * A show, opened: a page of its own over everything, on a phone and a television alike. Choosing
 * an episode plays it and closes this, so Back from the player lands on the chat, not on the page.
 */
@Composable
internal fun SeriesOpened(
    series: Series,
    watch: SeriesWatch,
    onPlay: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
    onLongClick: ((MediaItem) -> Unit)? = null,
    /** Download season and Download unwatched; see [queueSeason]. Null leaves them out. */
    onDownload: ((SeasonDownloadRequest) -> Unit)? = null,
    /** The chat the show is in, for the page's facts line. */
    chatTitle: String? = null,
) {
    // Every episode its chat holds, not only those the screen behind had read.
    val whole = com.tmplayer.ui.browse.rememberWholeSeries(series)
    // Which episodes are in the Downloads folder, for the season's download buttons: read again
    // whenever the index changes (a download finishing, or one deleted while the page is open),
    // each file asked of the disk off the drawing thread, as [queueSeason] does.
    val context = androidx.compose.ui.platform.LocalContext.current
    val settings = remember(context) { com.tmplayer.data.SettingsStore(context) }
    val history by remember(settings) { settings.downloadHistory }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val downloadedIds by produceState(initialValue = emptySet<String>(), history) {
        value = withContext(Dispatchers.IO) {
            history.filter { com.tmplayer.data.LocalDownloads.presentFile(it) != null }
                .mapTo(HashSet()) { "${it.chatId}:${it.messageId}" }
        }
    }
    SeriesPage(whole, watch, onPlay, onDismiss, onLongClick.takeIf { isTouch() }, onDownload, chatTitle, downloadedIds)
}

/**
 * The show's page: full screen, edge to edge, its backdrop under the status bar, in a window of
 * its own like [com.tmplayer.ui.components.TvMenu], so Back (the key, the gesture or the round
 * button at the top start) closes it and the grid behind keeps the focus it had. On a television
 * it keeps inside the overscan, with the play button focused first.
 */
@Composable
private fun SeriesPage(
    series: Series,
    watch: SeriesWatch,
    onPlay: (MediaItem) -> Unit,
    onDismiss: () -> Unit,
    onLongClick: ((MediaItem) -> Unit)?,
    onDownload: ((SeasonDownloadRequest) -> Unit)?,
    chatTitle: String?,
    downloadedIds: Set<String>,
) {
    val touch = isTouch()
    // On a phone the page grows out of the show's tile, and folds back towards it.
    val motion = com.tmplayer.ui.components.rememberPageMotion(onDismiss)
    Dialog(
        onDismissRequest = motion.close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        if (ScreenshotBarsOverride.hidden) HideDialogBars()
        motion.Frame {
        Box(Modifier.fillMaxSize().background(Tone.background)) {
            SeriesPanel(
                series = series,
                watch = watch,
                // The page stays open under the player, so Back from an episode lands on the
                // show again, with its progress and Continue line brought up to date.
                onPlay = onPlay,
                onLongClick = onLongClick,
                onDownload = onDownload,
                downloadedIds = downloadedIds,
                chatTitle = chatTitle,
                contentPadding = if (touch) {
                    PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)
                } else {
                    PaddingValues(start = Tv.SafeH, end = Tv.SafeH, bottom = Tv.SafeV)
                },
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)),
                // A remote has Back on it; a finger needs something to press.
                onBack = motion.close.takeIf { touch },
                barInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Top),
            )
            // Over the page, which is a window of its own above the app's.
            TopMessageHost()
        }
        }
    }
}

/**
 * Set by the promo build's screenshot fixture when it hides the system bars (`--ez nobars true`):
 * the show's page is a window of its own, so it has to hide them again for itself. Never set in
 * the app.
 */
object ScreenshotBarsOverride {
    @Volatile
    var hidden: Boolean = false
}

@Composable
private fun HideDialogBars() {
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.LaunchedEffect(view) {
        val window = (view.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window ?: return@LaunchedEffect
        androidx.core.view.WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(androidx.core.view.WindowInsetsCompat.Type.statusBars() or androidx.core.view.WindowInsetsCompat.Type.navigationBars())
        }
    }
}
