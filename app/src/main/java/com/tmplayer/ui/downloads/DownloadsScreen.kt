package com.tmplayer.ui.downloads

import com.tmplayer.ui.components.keepTopInSight
import androidx.compose.foundation.BorderStroke
import com.tmplayer.ui.theme.Danger
import androidx.tv.material3.Text as TvText
import androidx.tv.material3.MaterialTheme as TvTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.tmplayer.ui.browse.BarOverflow
import com.tmplayer.ui.browse.LocalTouchChrome
import com.tmplayer.ui.browse.TouchChipStrip
import com.tmplayer.ui.browse.TouchFilterChip
import com.tmplayer.ui.browse.TouchPage
import com.tmplayer.ui.browse.TouchPageSpec
import com.tmplayer.ui.browse.VideoPosterThumb
import com.tmplayer.ui.components.FloatingBackButton
import com.tmplayer.ui.components.FloatingBarIcon
import com.tmplayer.ui.components.FloatingChrome
import com.tmplayer.ui.components.FloatingPageTitle
import com.tmplayer.ui.components.LocalBottomChrome
import com.tmplayer.ui.components.StickyActionBar
import com.tmplayer.ui.components.rememberCompactChrome
import com.tmplayer.ui.components.usesFloatingChrome
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tmplayer.data.ContentProtection
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.DiskSpace
import com.tmplayer.data.LegacyDownloads
import com.tmplayer.data.LocalDownloads
import com.tmplayer.data.LocalFileAvailability
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ShareMedia
import com.tmplayer.data.FreeUpSpace
import com.tmplayer.data.StorageChart
import com.tmplayer.data.StorageSplit
import com.tmplayer.ui.components.StorageDonutPanel
import com.tmplayer.data.Td
import com.tmplayer.data.cancel
import com.tmplayer.data.pause
import com.tmplayer.data.pauseAll
import com.tmplayer.data.resume
import com.tmplayer.data.resumeAll
import com.tmplayer.data.start
import com.tmplayer.i18n.L
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.components.BigEmpty
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ScrollToTop
import com.tmplayer.ui.components.TvConfirm
import com.tmplayer.ui.components.isTouch
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Focus
import com.tmplayer.ui.theme.LocalDarkTheme
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.Tv
import com.tmplayer.ui.theme.focusRing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One video on this device, as the screen knows it: what names it, and what it holds on disk.
 *
 * [bytes] is what is actually on the disk rather than the size the message advertised, so a video
 * that stopped half way says how much room it is really taking and not how much it wanted.
 * [totalBytes] is what it wanted, kept beside it so a part-loaded row can say "710 MB of 1.4 GB"
 * instead of leaving the viewer to wonder why the figures on this screen do not add up.
 *
 * Downloads and cached videos are both rows, in different tabs, and [cached] says which: a
 * download is in the Downloads folder until the viewer deletes it, and a cached video is what
 * playing left behind, which the next play may take. [state] is where a download's file stands
 * against the index; [fileId] is the TDLib id resolved against this session, for the rows TDLib
 * still holds.
 */
private data class DownloadRow(
    val key: String,
    val title: String,
    val bytes: Long,
    val totalBytes: Long,
    val complete: Boolean,
    val record: ResumeRecord,
    val chatTitle: String = "",
    val durationSec: Int = 0,
    val state: LocalDownloads.FileState = LocalDownloads.FileState.Present,
    val cached: Boolean = false,
    val fileId: Int = record.fileId,
) {
    /** Part loaded and not being fetched by anything: bytes sitting there for no one. */
    val partial: Boolean get() = !complete && bytes > 0

    /** Recorded as a download, with nothing left of it on the disk. */
    val missing: Boolean get() = !cached && state == LocalDownloads.FileState.Missing
}

/**
 * Everything this device has downloaded or is waiting for, what it costs, and the way to be rid
 * of it.
 *
 * Both form factors keep this list. The one-video-at-a-time arrangement on a television belongs
 * to the watch cache, which playing fills and the next play replaces; a download the viewer asked
 * for by name is kept on a TV exactly as it is on a phone, until it is deleted here. Keeping every
 * download until told otherwise is only a fair deal if the viewer can see them, so a phone reaches
 * this screen from the drawer and a television from the rail, and every control on it has to be
 * findable with a D-pad as well as a thumb.
 *
 * Every row is a card, and every card carries its buttons underneath it at full width with their
 * names written on them: a card has an edge, so it is obvious where one video stops and the next
 * begins, and a named button is a thumb-sized target that cannot be mistaken for its neighbour.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DownloadsScreen(
    onPlay: (ResumeRecord) -> Unit,
    onBack: () -> Unit,
    /** Opens on the cached videos rather than the downloads, for Settings' Cached videos row. */
    openOnCached: Boolean = false,
    /**
     * Opens with Free up space already up, for "Not enough space": a video was refused for room,
     * so the device counts as short and Low storage mode is offered while it is off.
     */
    openFreeUp: Boolean = false,
    /** The way on from an empty page: the chats, where every video has its Download. */
    onBrowse: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = remember { SettingsStore(context) }
    val toast = rememberToast()
    // The kids lock (CP57) leaves out the chats it does not allow here too: a download from one
    // of them would otherwise be a way round it.
    val allowedChats by com.tmplayer.data.ChatGate.allowed.collectAsStateWithLifecycle()
    val allHistory by settings.downloadHistory.collectAsStateWithLifecycle(initialValue = emptyList())
    val history = remember(allHistory, allowedChats) { com.tmplayer.data.KidsLock.records(allHistory, allowedChats) }
    val removeAfterWatching by settings.removeAfterWatching.collectAsStateWithLifecycle(initialValue = false)
    // What is arriving and what is behind it, which the finished-downloads record knows nothing
    // about until the file has landed. In the order the videos were asked for, which is the order
    // they will arrive in, so the list does not reshuffle itself as the figures move.
    val activeMap by OfflineDownloads.active.collectAsStateWithLifecycle()
    val active = remember(activeMap) { activeMap.values.sortedBy { it.order } }

    // Each waiting video's place in the queue, worked out once for the list rather than once for
    // every row in it. See where it is read, below.
    val queuePlaces = remember(active) {
        active.filter { it.stage == OfflineDownloads.Stage.Queued }
            .withIndex()
            .associate { (index, progress) -> progress.fileId to index }
    }

    // What watching left behind. None of it is a download and none of it was asked for, but every
    // byte of it is on the disk, and anything taking up room belongs where room is managed.
    val allCached by settings.cachedVideos.collectAsStateWithLifecycle(initialValue = emptyList())
    val cached = remember(allCached, allowedChats) { com.tmplayer.data.KidsLock.records(allCached, allowedChats) }

    var rows by remember { mutableStateOf<List<DownloadRow>>(emptyList()) }
    var cachedRows by remember { mutableStateOf<List<DownloadRow>>(emptyList()) }
    // Empty until the first measurement lands, never read during composition: reading the disk is
    // a blocking statvfs().
    var disk by remember { mutableStateOf(DiskInfo.EMPTY) }
    // The same measurement the Settings card draws, so the two panels cannot disagree about a
    // disk they are both looking at.
    var split by remember { mutableStateOf(StorageSplit.EMPTY) }
    var confirmingClearAll by remember { mutableStateOf(false) }
    var confirmingRemoveAfterWatching by remember { mutableStateOf(false) }
    // Which row the viewer pressed Delete on, and therefore what the dialog is about to remove.
    // The row rather than a bare boolean, because the dialog has to name the video: nothing here
    // may be deleted without being named first.
    var confirmingDelete by remember { mutableStateOf<DownloadRow?>(null) }
    // Which finished downloads are ticked, by the key their row is drawn with. Keys rather than
    // rows: the list behind them is rebuilt from TDLib whenever anything finishes, so a held row
    // would go stale the moment it mattered.
    var picked by remember { mutableStateOf<Set<String>>(emptySet()) }
    var picking by remember { mutableStateOf(false) }
    // Which tab is up. Opens on whichever has something in it: somebody who just queued three
    // videos wants the queue, and somebody opening this on a quiet phone wants what they have.
    var tab by rememberSaveable { mutableStateOf(if (openOnCached) CACHED else COMPLETED) }
    var landedOnATab by rememberSaveable { mutableStateOf(openOnCached) }
    LaunchedEffect(active.isNotEmpty()) {
        if (landedOnATab) return@LaunchedEffect
        if (active.isNotEmpty()) tab = ONGOING
        landedOnATab = true
    }
    var confirmingDeleteMany by remember { mutableStateOf(false) }

    // A phone and a tablet draw one page: what is downloading (only while something is), what is
    // downloaded, grouped by show, and Recently played folded away at the foot. The television
    // keeps its three tabs (see [TvDownloads]): a remote steps through one short list at a time
    // better than down one long page, and its tabs are the first thing it lands on.
    val onePage = isTouch()
    // Settings' Recently played row opens the page with that section unfolded and in view.
    var cachedOpen by rememberSaveable { mutableStateOf(openOnCached) }
    var freeingUp by remember { mutableStateOf(false) }
    // Deletes so far, for the television's page to put the remote back on its tab after each.
    var deleted by remember { mutableStateOf(0) }
    var confirmingClearCached by remember { mutableStateOf(false) }
    var confirmingClearPictures by remember { mutableStateOf(false) }
    var confirmingDeleteUnfinished by remember { mutableStateOf(false) }
    // Initially on, so the line that offers it does not flash in before the setting is read.
    val lowStorageOn by settings.lowStorageMode.collectAsStateWithLifecycle(initialValue = true)
    // Free up space opened by "Not enough space" opens once, when the figures are in.
    var freeUpOpened by rememberSaveable { mutableStateOf(false) }
    // The downloads that stopped part way or are still arriving, with what each holds here. What
    // "Delete unfinished downloads" removes, and what the chart's Unfinished part measures.
    val unfinished = remember(active) {
        active.filter { it.stage != OfflineDownloads.Stage.Moving && it.downloadedBytes > 0 }
    }
    val listState = rememberLazyListState()
    // Where the Recently played heading sits in the list, written as the list is laid out, so
    // the page can scroll to it when it opens on that section.
    val cachedHeadingAt = remember { intArrayOf(-1) }

    fun leavePicking() {
        picking = false
        picked = emptySet()
    }

    // Only the rows that are still on the list. A selection that outlived its videos would offer
    // to delete or share things that are no longer there.
    val chosen = remember(picked, rows) { rows.filter { it.key in picked } }

    // Every row, because every row is a download.
    val shown = rows

    // A selection belongs to the tab it was made on. Carried across, its bar would sit under a
    // list of downloads it says nothing about.
    LaunchedEffect(tab) { if (!onePage && tab != COMPLETED) leavePicking() }

    BackHandler(enabled = picking) { leavePicking() }
    // Nothing is known until the first pass over TDLib has finished, and an empty list drawn in
    // the meantime reads as "you have downloaded nothing" rather than as "still counting".
    var counted by remember { mutableStateOf(false) }

    /**
     * Measures a record against the disk, or drops it when there is nothing there any more.
     *
     * A row is kept as soon as it holds a byte, finished or not: half a video is still half a
     * gigabyte, and hiding it hides exactly the space the viewer came here looking for.
     */
    suspend fun measure(record: ResumeRecord): DownloadRow? {
        val row = DownloadRow(
            key = "kept_${record.chatId}_${record.messageId}",
            title = com.tmplayer.data.VideoCorrections.titleFor(record.chatId, record.messageId, record.title),
            bytes = 0,
            totalBytes = record.sizeBytes,
            complete = false,
            record = record,
            chatTitle = record.chatTitle,
            durationSec = record.durationSec,
        )
        // The index first: a download with a path is a file in the Downloads folder, measured on
        // the disk, and TDLib knows nothing about it any more.
        when (val state = LocalDownloads.stateOf(record)) {
            LocalDownloads.FileState.Present -> {
                val bytes = java.io.File(record.localPath!!).length()
                return row.copy(bytes = bytes, complete = true, state = state)
            }
            LocalDownloads.FileState.Missing -> return row.copy(state = state)
            LocalDownloads.FileState.Legacy -> Unit
        }
        // From before downloads had a folder: still in TDLib's cache, asked of TDLib. Against
        // this session's id, not the one saved with the row, which stops resolving after a restart.
        val fileId = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
            .getOrDefault(record.fileId)
        val bytes = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
        val availability = runCatching { Td.localFileAvailability(fileId) }
            .getOrDefault(LocalFileAvailability.Missing)
        // Nothing left of it: said, not hidden, so the viewer learns something took it.
        if (bytes <= 0 || availability == LocalFileAvailability.Missing) {
            return row.copy(state = LocalDownloads.FileState.Missing, fileId = fileId)
        }
        return row.copy(
            bytes = bytes,
            complete = availability == LocalFileAvailability.Complete,
            state = LocalDownloads.FileState.Legacy,
            fileId = fileId,
        )
    }

    /**
     * A video playing left behind, measured through TDLib, or null when nothing of it is left.
     * One that is also a download is the download's, and is listed there instead.
     */
    suspend fun measureCached(record: ResumeRecord, downloaded: Set<Pair<Long, Long>>): DownloadRow? {
        if ((record.chatId to record.messageId) in downloaded) return null
        val fileId = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
            .getOrDefault(record.fileId)
        val bytes = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
        if (bytes <= 0) return null
        val availability = runCatching { Td.localFileAvailability(fileId) }
            .getOrDefault(LocalFileAvailability.Missing)
        return DownloadRow(
            key = "cached_${record.chatId}_${record.messageId}",
            title = com.tmplayer.data.VideoCorrections.titleFor(record.chatId, record.messageId, record.title),
            bytes = bytes,
            totalBytes = record.sizeBytes,
            complete = availability == LocalFileAvailability.Complete,
            record = record,
            chatTitle = record.chatTitle,
            durationSec = record.durationSec,
            cached = true,
            fileId = fileId,
        )
    }

    /**
     * Re-measures the disk and rebuilds every row.
     *
     * All of it on [Dispatchers.IO]. Callers are `LaunchedEffect`s, which resume on the Main
     * dispatcher, and one pass is a `statvfs()`, several TDLib round trips per record and a
     * `walkTopDown` of five media directories with a `length()` on every file in them: left on
     * Main that is a frozen screen on entry and again every time a download finishes.
     *
     * The state is written once, at the end, back on the caller's dispatcher.
     */
    suspend fun refresh(known: List<ResumeRecord>) {
        data class Measured(
            val rows: List<DownloadRow>,
            val cached: List<DownloadRow>,
            val split: StorageSplit,
            val disk: DiskInfo,
        )

        val measured = withContext(Dispatchers.IO) {
            val readDisk = DiskSpace.read(context)
            val measuredSplit = runCatching { StorageSplit.measure(context) }
                .getOrDefault(StorageSplit.EMPTY)
            // Every record measured at once rather than one after another: they are independent
            // questions of TDLib, and fifty of them fifty waits deep reads as a hang.
            val keptRows = known
                .map { async { measure(it) } }
                .awaitAll()
                .filterNotNull()
            val downloadedMessages = known.map { it.chatId to it.messageId }.toSet()
            val cachedNow = runCatching { settings.cachedVideosNow() }.getOrDefault(emptyList())
                .map { async { measureCached(it, downloadedMessages) } }
                .awaitAll()
                .filterNotNull()

            Measured(keptRows, cachedNow, measuredSplit, readDisk)
        }

        rows = measured.rows
        cachedRows = measured.cached
        split = measured.split
        disk = measured.disk
        counted = true
    }

    // One trigger, not three. These keys all change together when a download finishes, and three
    // separate effects would interleave three passes of the audit above, each writing `rows` when
    // it finished, so the slowest could land last and put stale figures back on screen.
    LaunchedEffect(history, cached.map { it.fileId }, active.size) { refresh(history) }
    LaunchedEffect(counted) {
        if (!counted || !openFreeUp || freeUpOpened) return@LaunchedEffect
        freeUpOpened = true
        freeingUp = true
    }
    fun share(ticked: List<DownloadRow>) {
        scope.launch {
            // The records never knew whether their chat restricts saving content, and a channel can
            // turn it on after the fact, so Telegram is asked at the moment of handing them on.
            val these = ticked.filter { Td.maySave(it.record.chatId, it.record.messageId) }
            if (these.isEmpty()) {
                toast(ContentProtection.NOT_SAVABLE)
                return@launch
            }
            val files = these.mapNotNull { row ->
                val record = row.record
                // The download's own file when it has one; otherwise TDLib's, through the id this
                // session knows it by rather than the one saved with the row.
                val path = LocalDownloads.shareablePath(settings, record.chatId, record.messageId, row.fileId)
                path?.let { it to record.title }
            }
            val intent = ShareMedia.intentFor(context, files)
            if (intent == null) {
                // All of them part downloaded or gone. Saying so beats an empty share sheet,
                // which reads as the button being broken.
                toast(L.downloadsNothingToShare)
                return@launch
            }
            runCatching { context.startActivity(intent) }
                .onFailure { toast(L.downloadsNoShareApp) }
            leavePicking()
        }
    }

    /**
     * Removes one video, whichever of the three kinds it is.
     *
     * The record is only forgotten once the file has actually gone. Forgetting it regardless would
     * leave bytes on the disk with nothing on this screen pointing at them, which is the one state
     * this screen exists to prevent.
     *
     * @return whether it went, so a file that would not delete is said rather than left on the
     *   list without a word.
     */
    suspend fun removeOne(row: DownloadRow): Boolean = withContext(Dispatchers.IO) {
        val record = row.record
        // A download in the folder is deleted there, through the index; TDLib does not have it.
        if (!row.cached && record.localPath != null) {
            return@withContext LocalDownloads.delete(settings, record)
        }
        // The same resolution [measure] reads with: the saved id stops answering after a restart,
        // and a delete through it removes nothing while the row still leaves the list.
        val fileId = runCatching { Td.currentFileId(record.chatId, record.messageId, record.fileId) }
            .getOrDefault(record.fileId)
        runCatching { Td.deleteFile(fileId) }
        val left = runCatching { Td.localDownloadedBytes(fileId) }.getOrDefault(0L)
        if (left > 0) return@withContext false
        if (row.cached) {
            settings.forgetCachedVideo(record.chatId, record.messageId)
        } else {
            settings.forgetDownload(record.chatId, record.messageId)
        }
        true
    }

    /**
     * Puts a video into the download queue: a cached one, which moves into Downloads straight away,
     * or a part downloaded one, which finishes and then moves.
     */
    fun saveToDownloads(row: DownloadRow) {
        scope.launch {
            // Asked here for the same reason [share] asks: a cached video may come from a chat that
            // lets it be watched and nothing more, and the cache record cannot say so.
            if (!Td.maySave(row.record.chatId, row.record.messageId)) {
                toast(ContentProtection.NOT_SAVABLE)
                return@launch
            }
            OfflineDownloads.start(context, row.record.toMediaItem().copy(fileId = row.fileId), row.chatTitle)
            toast(if (row.cached) L.downloadsSavingTitle(title = row.title) else L.downloadsResumingTitle(title = row.title))
            if (row.cached) {
                tab = ONGOING
                // The page's Downloading section is at the top: show it arriving there.
                if (onePage) listState.animateScrollToItem(0)
            }
        }
    }

    /**
     * Free up space: every recently played video, through the cache's own rules, and the
     * unmatched files listed beside them. The cache's own sweep spares a file it cannot place and
     * one written in the last minutes; the ones listed here were measured and shown, so they go.
     */
    fun clearCached() {
        scope.launch {
            val freed = split.cachedBytes
            val strays = split.strays
            withContext(Dispatchers.IO) {
                runCatching { com.tmplayer.data.WatchCache.clearAll(context) }
                strays.forEach { runCatching { com.tmplayer.data.WatchCache.forget(context, it) } }
            }
            refresh(history)
            toast(L.settingsFreed(size = Translator.messages.formatter.bytes(freed)))
        }
    }

    /** Free up space: thumbnails, previews and the online posters, which come back as the viewer browses. */
    fun clearPictures() {
        scope.launch {
            val freed = split.pictureBytes
            withContext(Dispatchers.IO) {
                runCatching { Td.clearPicturesAndPreviews() }
                runCatching { com.tmplayer.online.OnlineMetadata.current?.purgeImages() }
            }
            refresh(history)
            toast(L.settingsFreed(size = Translator.messages.formatter.bytes(freed)))
        }
    }

    /**
     * Stops a download and deletes what it had fetched. Cancelling alone leaves the part file on
     * the disk with nothing naming it, which is space the viewer can no longer find. A file that
     * is also the recently played video is left to that, since the same bytes are its.
     */
    suspend fun dropUnfinished(fileId: Int) {
        OfflineDownloads.cancel(context, fileId)
        withContext(Dispatchers.IO) {
            runCatching { Td.cancelDownload(fileId) }
            if (cachedRows.none { it.fileId == fileId }) runCatching { Td.deleteFile(fileId) }
        }
    }

    /** Free up space: every unfinished download, stopped and its bytes deleted. */
    fun deleteUnfinished() {
        scope.launch {
            val these = unfinished.toList()
            val freed = these.sumOf { it.downloadedBytes }
            these.forEach { dropUnfinished(it.fileId) }
            refresh(history)
            toast(L.settingsFreed(size = Translator.messages.formatter.bytes(freed)))
        }
    }

    /** Free up space: Low storage mode on, which deletes nothing. */
    fun turnOnLowStorage() {
        scope.launch {
            runCatching { settings.setLowStorageMode(true) }
            toast(L.downloadsFreeLowStorageDone(mode = L.settingsLowStorage))
        }
    }

    fun delete(row: DownloadRow) {
        scope.launch {
            if (!removeOne(row)) toast(L.downloadsDeleteFailed(file = row.title))
            refresh(history)
        }
    }

    /** A row whose file is gone: there is nothing to confirm deleting, only a record to drop. */
    fun removeMissing(row: DownloadRow) {
        scope.launch {
            withContext(Dispatchers.IO) { settings.forgetDownload(row.record.chatId, row.record.messageId) }
            toast(L.downloadsRemovedTitle(title = row.title))
            refresh(history)
        }
    }

    fun deleteMany(these: List<DownloadRow>) {
        scope.launch {
            val stuck = these.filterNot { removeOne(it) }
            stuck.firstOrNull()?.let { toast(L.downloadsDeleteFailed(file = it.title)) }
            leavePicking()
            refresh(history)
        }
    }

    // The downloads as both lists draw them (the phone's page, the television's Downloaded tab).
    // The episodes of one show sit together under its name, in watching order, and every other
    // video stands on its own, newest first. The rows
    // and every action on them are the tabs' own; only the order and the headings are new.
    val shelf = remember(rows) {
        com.tmplayer.data.DownloadShelf.arrange(rows, { it.record.toMediaItem() }, { it.key }, { it.record.updatedAt })
    }
    val keptCard: @Composable (DownloadRow) -> Unit = { row ->
        DownloadCard(
            row = row,
            picking = picking,
            checked = row.key in picked,
            onPlay = { if (row.partial) saveToDownloads(row) else onPlay(row.record) },
            onShare = { share(listOf(row)) },
            onDelete = { if (row.missing) removeMissing(row) else confirmingDelete = row },
            onToggle = { picked = if (row.key in picked) picked - row.key else picked + row.key },
            onHold = {
                picking = true
                picked = picked + row.key
            },
        )
    }
    // What a press on each part of the storage chart's legend does: the part's own way to free it,
    // or the list it is on. The device's other apps and its free space are not TMPlayer's to act on.
    val chartActions: Map<StorageChart.Part, () -> Unit> = buildMap {
        if (rows.isNotEmpty()) {
            put(StorageChart.Part.Downloads) {
                tab = COMPLETED
                picking = true
            }
        }
        if (unfinished.isNotEmpty()) put(StorageChart.Part.Unfinished) { confirmingDeleteUnfinished = true }
        if (split.cachedBytes > 0) put(StorageChart.Part.Recent) { confirmingClearCached = true }
        if (split.strays.isNotEmpty()) {
            put(StorageChart.Part.Unmatched) {
                tab = CACHED
                if (onePage) {
                    cachedOpen = true
                    scope.launch {
                        withFrameNanos { }
                        if (cachedHeadingAt[0] > 0) runCatching { listState.animateScrollToItem(cachedHeadingAt[0]) }
                    }
                }
            }
        }
        if (split.pictureBytes > 0) put(StorageChart.Part.Pictures) { confirmingClearPictures = true }
    }
    val storageCard: @Composable () -> Unit = {
        StorageSummary(
            figures = split.chart(disk),
            measured = counted && disk.totalBytes > 0,
            actions = chartActions,
            onFreeUp = { freeingUp = true },
        )
    }
    // An unmatched file, measured and named by what the disk knows of it, with Delete.
    val strayCard: @Composable (com.tmplayer.data.WatchCacheRules.Stray) -> Unit = { stray ->
        StrayCard(stray) {
            scope.launch {
                val gone = withContext(Dispatchers.IO) { com.tmplayer.data.WatchCache.forget(context, stray) }
                if (gone) toast(L.settingsFreed(size = Translator.messages.formatter.bytes(stray.bytes)))
                refresh(history)
            }
        }
    }
    // What each of the television's tabs lists.
    val tabBody: LazyListScope.() -> Unit = body@{
        if (tab == ONGOING) {
            if (active.isNotEmpty()) {
                item {
                    QueueHeading(
                        title = queueHeading(active),
                        // Only worth offering on a real queue. With one video the card's
                        // own button says the same thing an inch further down the screen.
                        bulk = if (active.size > 1) {
                            if (active.any { it.busy }) L.commonPauseAll else L.commonResumeAll
                        } else {
                            null
                        },
                        onBulk = {
                            if (active.any { it.busy }) {
                                OfflineDownloads.pauseAll(context)
                            } else {
                                OfflineDownloads.resumeAll(context)
                            }
                        },
                    )
                }
                items(active, key = { "active_${it.fileId}" }) { progress ->
                    ActiveDownloadCard(
                        progress = progress,
                        // Where it stands among the videos still to be fetched, so a
                        // waiting row can say how long the wait is.
                        //
                        // Looked up from the map built once above rather than worked out
                        // per row: the progress ticker rebuilds this list once a second
                        // for as long as anything is downloading.
                        place = queuePlaces[progress.fileId] ?: -1,
                        onPause = { OfflineDownloads.pause(context, progress.fileId) },
                        onResume = { OfflineDownloads.resume(context, progress.fileId) },
                        onCancel = { scope.launch { dropUnfinished(progress.fileId) } },
                        onFreeUp = { freeingUp = true },
                    )
                }
            } else {
                item {
                    // Given a height of its own: BigEmpty fills what it is given, and
                    // inside a LazyColumn that is nothing, so it would collapse to a line.
                    Box(emptyStateHeight(0.7f)) {
                        BigEmpty(
                            L.downloadsEmptyDownloading,
                            icon = TmIcons.Download,
                        )
                    }
                }
            }
            return@body
        }

        if (tab == CACHED) {
            item {
                Text(
                    L.downloadsCachedTabNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = TEXT_EDGE, end = TEXT_EDGE, top = 16.dp, bottom = 4.dp),
                )
            }
            items(cachedRows, key = { it.key }) { row ->
                DownloadCard(
                    row = row,
                    picking = false,
                    checked = false,
                    onPlay = { onPlay(row.record) },
                    onShare = { saveToDownloads(row) },
                    onDelete = { confirmingDelete = row },
                    onToggle = {},
                    onHold = null,
                )
            }
            // The videos on the disk no record names: counted in the storage figures, so they are
            // listed too, each with a way to remove it (user, 2026-10-18).
            if (split.strays.isNotEmpty()) {
                item(key = "unmatched-note") {
                    Text(
                        L.downloadsUnmatchedNote,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = TEXT_EDGE, end = TEXT_EDGE, top = 20.dp, bottom = 4.dp),
                    )
                }
                items(split.strays, key = { "stray_" + it.path }) { stray -> strayCard(stray) }
            }
            if (counted && cachedRows.isEmpty() && split.strays.isEmpty()) {
                item {
                    Box(emptyStateHeight(0.6f)) {
                        BigEmpty(
                            L.downloadsEmptyCached,
                            icon = TmIcons.Download,
                        )
                    }
                }
            }
            return@body
        }

        item { storageCard() }
        item {
            RemoveAfterWatchingRow(removeAfterWatching) { on ->
                // Turning it on is asked first: from then on files go without a press.
                // Turning it off deletes nothing, so it just happens.
                if (on) {
                    confirmingRemoveAfterWatching = true
                } else {
                    scope.launch { settings.setRemoveAfterWatching(false) }
                }
            }
        }
        // Grouped by show as the phone's page is, so the two read as one list. The headings take
        // no focus: the remote steps from card to card and each heading scrolls in with its first.
        shelf.forEach { entry ->
            when (entry) {
                is com.tmplayer.data.DownloadShelf.Entry.Single -> item(key = entry.key) { keptCard(entry.row) }
                is com.tmplayer.data.DownloadShelf.Entry.Show -> {
                    item(key = entry.key) {
                        ShowHeading(
                            title = entry.title,
                            detail = listOf(
                                if (entry.pieces) L.gridPartsCount(entry.episodes.size) else L.seriesEpisodesCount(count = entry.episodes.size),
                                Translator.messages.formatter.bytes(entry.episodes.sumOf { it.bytes }),
                            ).joinToString(DOT),
                        )
                    }
                    items(entry.episodes, key = { it.key }) { keptCard(it) }
                }
            }
        }
        if (counted && shown.isEmpty()) {
            item {
                Box(emptyStateHeight(0.6f)) {
                    BigEmpty(
                        L.downloadsEmptyDownloaded(folder = LegacyDownloads.FOLDER),
                        icon = TmIcons.Download,
                        actionLabel = onBrowse?.let { L.browseGoToChats },
                        onAction = onBrowse,
                    )
                }
            }
        }
    }
    val pageBody: LazyListScope.() -> Unit = {
        var at = 0
        item(key = "storage") { storageCard() }
        at++
        // Only while something is on its way: a Downloading heading over nothing is a section to
        // read past on every visit.
        if (active.isNotEmpty()) {
            item(key = "h-active") {
                QueueHeading(
                    title = queueHeading(active),
                    bulk = if (active.size > 1) {
                        if (active.any { it.busy }) L.commonPauseAll else L.commonResumeAll
                    } else {
                        null
                    },
                    onBulk = {
                        if (active.any { it.busy }) OfflineDownloads.pauseAll(context) else OfflineDownloads.resumeAll(context)
                    },
                )
            }
            items(active, key = { "active_${it.fileId}" }) { progress ->
                ActiveDownloadCard(
                    progress = progress,
                    place = queuePlaces[progress.fileId] ?: -1,
                    onPause = { OfflineDownloads.pause(context, progress.fileId) },
                    onResume = { OfflineDownloads.resume(context, progress.fileId) },
                    onCancel = { scope.launch { dropUnfinished(progress.fileId) } },
                    onFreeUp = { freeingUp = true },
                )
            }
            at += 1 + active.size
        }
        item(key = "h-done") {
            SectionHeading(if (rows.isEmpty()) L.downloadsDownloaded else L.downloadsTabDownloadedCount(count = rows.size))
        }
        item(key = "remove-after") {
            RemoveAfterWatchingRow(removeAfterWatching) { on ->
                if (on) {
                    confirmingRemoveAfterWatching = true
                } else {
                    scope.launch { settings.setRemoveAfterWatching(false) }
                }
            }
        }
        at += 2
        shelf.forEach { entry ->
            when (entry) {
                is com.tmplayer.data.DownloadShelf.Entry.Single -> {
                    item(key = entry.key) { keptCard(entry.row) }
                    at++
                }
                is com.tmplayer.data.DownloadShelf.Entry.Show -> {
                    item(key = entry.key) {
                        ShowHeading(
                            title = entry.title,
                            detail = listOf(
                                if (entry.pieces) L.gridPartsCount(entry.episodes.size) else L.seriesEpisodesCount(count = entry.episodes.size),
                                Translator.messages.formatter.bytes(entry.episodes.sumOf { it.bytes }),
                            ).joinToString(DOT),
                        )
                    }
                    items(entry.episodes, key = { it.key }) { keptCard(it) }
                    at += 1 + entry.episodes.size
                }
            }
        }
        if (counted && rows.isEmpty()) {
            item(key = "e-done") {
                Box(emptyStateHeight(0.4f)) {
                    BigEmpty(
                        L.downloadsEmptyDownloaded(folder = LegacyDownloads.FOLDER),
                        icon = TmIcons.Download,
                        actionLabel = onBrowse?.let { L.browseGoToChats },
                        onAction = onBrowse,
                    )
                }
            }
            at++
        }
        cachedHeadingAt[0] = at
        item(key = "h-cached") {
            val held = cachedRows.size + split.strays.size
            CollapsibleHeading(
                title = if (held == 0) L.downloadsCached else L.downloadsTabCachedCount(count = held),
                detail = (cachedRows.sumOf { it.bytes } + split.strayBytes).takeIf { it > 0 }?.let { Translator.messages.formatter.bytes(it) },
                open = cachedOpen,
                onToggle = { cachedOpen = !cachedOpen },
            )
        }
        if (cachedOpen) {
            item(key = "n-cached") {
                Text(
                    if (counted && cachedRows.isEmpty() && split.strays.isEmpty()) L.downloadsEmptyCached else L.downloadsCachedTabNote,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = TEXT_EDGE, end = TEXT_EDGE, bottom = 4.dp),
                )
            }
            items(cachedRows, key = { it.key }) { row ->
                DownloadCard(
                    row = row,
                    picking = false,
                    checked = false,
                    onPlay = { onPlay(row.record) },
                    onShare = { saveToDownloads(row) },
                    onDelete = { confirmingDelete = row },
                    onToggle = {},
                    onHold = null,
                )
            }
            if (split.strays.isNotEmpty()) {
                item(key = "unmatched-note") {
                    Text(
                        L.downloadsUnmatchedNote,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = TEXT_EDGE, end = TEXT_EDGE, top = 16.dp, bottom = 4.dp),
                    )
                }
                items(split.strays, key = { "stray_" + it.path }) { stray -> strayCard(stray) }
            }
        }
    }
    val body = if (onePage) pageBody else tabBody
    // Opened from Settings' Recently played row: that section, unfolded, in view.
    LaunchedEffect(counted) {
        if (!counted || !openOnCached || !onePage) return@LaunchedEffect
        withFrameNanos { }
        if (cachedHeadingAt[0] > 0) runCatching { listState.scrollToItem(cachedHeadingAt[0]) }
    }
    val tabLabel = { which: Int ->
        when (which) {
            ONGOING -> if (active.isEmpty()) L.downloadsTabDownloading else L.downloadsTabDownloadingCount(count = active.size)
            COMPLETED -> if (rows.isEmpty()) L.downloadsTabDownloaded else L.downloadsTabDownloadedCount(count = rows.size)
            else -> if (cachedRows.isEmpty()) L.downloadsTabCached else L.downloadsTabCachedCount(count = cachedRows.size)
        }
    }

    if (usesFloatingChrome()) {
        PhoneDownloads(
            title = L.downloadsTitle,
            tab = tab,
            tabLabel = tabLabel,
            onTab = { tab = it },
            picking = picking,
            chosenCount = chosen.size,
            canSelect = rows.isNotEmpty(),
            allChosen = chosen.size >= shown.size,
            onBack = onBack,
            onSelect = { picking = true },
            onDeleteAll = { confirmingClearAll = true },
            onLeavePicking = { leavePicking() },
            onSelectAll = { picked = shown.map { it.key }.toSet() },
            onShareChosen = { share(chosen) },
            onDeleteChosen = { confirmingDeleteMany = true },
            listState = listState,
            body = body,
        )
    } else if (!isTouch()) {
        TvDownloads(
            tab = tab,
            tabLabel = tabLabel,
            onTab = { tab = it },
            picking = picking,
            chosenCount = chosen.size,
            canSelect = rows.isNotEmpty() && tab == COMPLETED,
            allChosen = chosen.size >= shown.size,
            onBack = onBack,
            onSelect = { picking = true },
            onDeleteAll = { confirmingClearAll = true },
            onLeavePicking = { leavePicking() },
            onSelectAll = { picked = shown.map { it.key }.toSet() },
            onShareChosen = { share(chosen) },
            onDeleteChosen = { confirmingDeleteMany = true },
            listState = listState,
            refocus = deleted,
            body = body,
        )
    } else {
        Scaffold(
            containerColor = Tone.background,
            // A television crops its outer few percent, and this screen is stock Material laid out to
            // the panel's edge, so on a TV the whole of it moves inside the overscan margin.
            modifier = if (isTouch()) {
                Modifier
            } else {
                Modifier.background(Tone.background).padding(horizontal = Tv.SafeH, vertical = Tv.SafeV)
            },
            topBar = {
                TopAppBar(
                    title = {
                        Text(
                            if (picking) {
                                if (chosen.isEmpty()) L.downloadsSelectVideos else L.downloadsSelected(count = chosen.size)
                            } else {
                                L.downloadsTitle
                            },
                        )
                    },
                    navigationIcon = {
                        // While picking, the arrow leaves the selection rather than the screen: that
                        // is what Back does here, and the two must not disagree.
                        val backFocus = remember { MutableInteractionSource() }
                        IconButton(
                            onClick = { if (picking) leavePicking() else onBack() },
                            interactionSource = backFocus,
                            modifier = Modifier.tvFocusRing(backFocus, CircleShape),
                        ) {
                            Icon(
                                if (picking) TmIcons.Close else TmIcons.ArrowBack,
                                contentDescription = if (picking) L.downloadsLeaveSelection else L.commonBack,
                            )
                        }
                    },
                    actions = {
                        if (picking) {
                            // Everything the list is showing, and never more than that.
                            TextAction(L.downloadsSelectAll, enabled = chosen.size < shown.size) {
                                picked = shown.map { it.key }.toSet()
                            }
                            return@TopAppBar
                        }
                        if (rows.isNotEmpty()) {
                            TextAction(L.downloadsSelect) { picking = true }
                        }
                        // Offered on the rows and nothing else: a screen headed "Downloads" must not
                        // carry a button that empties the cache and the previews too.
                        if (rows.isNotEmpty()) {
                            TextAction(L.downloadsDeleteAll) { confirmingClearAll = true }
                        }
                    },
                )
            },
            bottomBar = {
                // Only while picking, and only with something picked: a bar of disabled buttons is a
                // bar that has to be read before it can be ignored.
                if (!picking || chosen.isEmpty()) return@Scaffold
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, tonalElevation = 3.dp) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .navigationBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PrimaryAction(L.commonShare, TmIcons.Share) { share(chosen) }
                        SecondaryAction(
                            label = L.commonDelete,
                            icon = TmIcons.Delete,
                            onClick = { confirmingDeleteMany = true },
                            danger = true,
                        )
                    }
                }
            },
        ) { padding ->
            // A tablet: the phone's one page, under Material's bar.
            Column(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 24.dp),
                        content = body,
                    )
                    ScrollToTop(listState)
                }
            }
        }
    }

    // The app's one confirmation (TvConfirm): Material's dialog on a phone, the remote's panel
    // with Cancel focused on a television, where a stock dialog drew in a look of its own.
    confirmingDelete?.let { row ->
        TvConfirm(
            title = if (row.cached) L.downloadsDeleteCachedTitle else L.downloadsDeleteOneTitle,
            message = if (row.cached) {
                L.downloadsDeleteCachedBody(title = row.title, size = Translator.messages.formatter.bytes(row.bytes))
            } else {
                L.downloadsDeleteOneBody(title = row.title, size = Translator.messages.formatter.bytes(row.bytes))
            },
            confirmLabel = L.commonDelete,
            cancelLabel = L.downloadsKeepIt,
            onConfirm = {
                delete(row)
                confirmingDelete = null
                deleted++
            },
            onDismiss = { confirmingDelete = null },
        )
    }

    if (confirmingDeleteMany) {
        // One chosen reads as one: its name, "it" and "Keep it", not "them".
        val only = chosen.singleOrNull()
        TvConfirm(
            title = L.downloadsDeleteManyTitle(count = chosen.size),
            message = if (only != null) {
                L.downloadsDeleteOneBody(title = only.title, size = Translator.messages.formatter.bytes(only.bytes))
            } else {
                L.downloadsDeleteManyBody(size = Translator.messages.formatter.bytes(chosen.sumOf { it.bytes }))
            },
            confirmLabel = L.commonDelete,
            cancelLabel = if (only != null) L.downloadsKeepIt else L.downloadsKeepThem,
            onConfirm = {
                confirmingDeleteMany = false
                deleteMany(chosen)
                deleted++
            },
            onDismiss = { confirmingDeleteMany = false },
        )
    }

    // Free up space, on the page rather than in Settings: what can go, how much each frees, and a
    // way into the selection for the downloads themselves. Every line still asks before it clears.
    if (freeingUp) {
        val lines = com.tmplayer.data.FreeUpSpace.lines(
            lowStorageOn = lowStorageOn,
            short = com.tmplayer.data.FreeUpSpace.isShort(disk.freeBytes, disk.totalBytes, askedForRoom = openFreeUp),
            recentBytes = split.cachedBytes,
            recentCount = split.cachedCount,
            unfinishedBytes = unfinished.sumOf { it.downloadedBytes },
            unfinishedCount = unfinished.size,
            picturesBytes = split.pictureBytes,
            downloadCount = rows.size,
        )
        com.tmplayer.ui.components.ChoiceSheet(
            title = L.downloadsFreeUpSpace,
            note = L.downloadsFreeUpSpaceNote,
            onDismiss = { freeingUp = false },
            onClose = { freeingUp = false },
            ignoreRelease = !isTouch(),
        ) {
            val first = remember { FocusRequester() }
            ChoiceList {
                FreeUpLines(lines, first) { kind ->
                    freeingUp = false
                    when (kind) {
                        com.tmplayer.data.FreeUpSpace.Kind.LowStorage -> turnOnLowStorage()
                        com.tmplayer.data.FreeUpSpace.Kind.Recent -> confirmingClearCached = true
                        com.tmplayer.data.FreeUpSpace.Kind.Unfinished -> confirmingDeleteUnfinished = true
                        com.tmplayer.data.FreeUpSpace.Kind.Pictures -> confirmingClearPictures = true
                        com.tmplayer.data.FreeUpSpace.Kind.ChooseDownloads -> {
                            // The television's selection lives on its Downloaded tab.
                            tab = COMPLETED
                            picking = true
                        }
                    }
                }
            }
            com.tmplayer.ui.components.FocusOnOpen(first)
        }
    }

    if (confirmingDeleteUnfinished) {
        val freed = unfinished.sumOf { it.downloadedBytes }
        TvConfirm(
            title = L.downloadsFreeUnfinishedTitle(count = unfinished.size),
            message = L.downloadsFreeUnfinishedMessage(count = unfinished.size, size = Translator.messages.formatter.bytes(freed)),
            detail = L.mainNoRoomDetail,
            confirmLabel = L.commonDelete,
            onConfirm = {
                confirmingDeleteUnfinished = false
                deleteUnfinished()
            },
            onDismiss = { confirmingDeleteUnfinished = false },
        )
    }

    if (confirmingClearCached) {
        TvConfirm(
            title = L.settingsClearCacheTitle,
            message = L.settingsClearCacheMessage(count = split.cachedCount, size = Translator.messages.formatter.bytes(split.cachedBytes)),
            detail = L.settingsDownloadsUntouched,
            confirmLabel = L.commonClear,
            onConfirm = {
                confirmingClearCached = false
                clearCached()
            },
            onDismiss = { confirmingClearCached = false },
        )
    }

    if (confirmingClearPictures) {
        TvConfirm(
            title = L.downloadsFreePicturesTitle,
            message = L.downloadsFreePicturesMessage(size = Translator.messages.formatter.bytes(split.pictureBytes)),
            detail = L.settingsClearPicturesUntouched,
            confirmLabel = L.settingsClearPicturesConfirm,
            onConfirm = {
                confirmingClearPictures = false
                clearPictures()
            },
            onDismiss = { confirmingClearPictures = false },
        )
    }

    if (confirmingRemoveAfterWatching) {
        TvConfirm(
            title = L.confirmRemoveAfterWatchingTitle,
            message = L.confirmRemoveAfterWatchingMessage,
            detail = L.confirmRemoveAfterWatchingDetail,
            confirmLabel = L.confirmTurnOn,
            onConfirm = {
                confirmingRemoveAfterWatching = false
                scope.launch { settings.setRemoveAfterWatching(true) }
            },
            onDismiss = { confirmingRemoveAfterWatching = false },
        )
    }

    if (confirmingClearAll) {
        // The rows, and only the rows: the figure quoted here has to be what this button will
        // actually delete. Cache and previews are counted and cleared in Settings.
        val freed = rows.sumOf { it.bytes }
        TvConfirm(
            title = L.downloadsDeleteAllTitle,
            message = L.downloadsDeleteAllBody(count = rows.size, size = Translator.messages.formatter.bytes(freed)),
            confirmLabel = L.downloadsDeleteAll,
            cancelLabel = L.downloadsKeepThem,
            onConfirm = {
                confirmingClearAll = false
                scope.launch {
                    // The downloads, one at a time, and nothing else. Each is only forgotten
                    // once its bytes have actually gone.
                    val stuck = rows.filterNot { removeOne(it) }
                    stuck.firstOrNull()?.let { toast(L.downloadsDeleteFailed(file = it.title)) }
                    refresh(history)
                }
            },
            onDismiss = { confirmingClearAll = false },
        )
    }
}

/**
 * The ring that says where the remote is standing, on the stock Material widgets this screen is
 * built from.
 *
 * The app's own television controls answer focus with a filled row plus [focusRing]; the Material
 * buttons, tabs and icon buttons here own their fill and answer it with a state layer a few
 * percent of alpha deep, which from a sofa is no answer at all. So on a television this draws the
 * theme's accent ring around whichever widget holds focus, in that widget's own shape, and on a
 * phone it is a no-op: there is no roving focus to mark, and the screen must not change.
 *
 * [focusRing] on its own is not enough, because it deliberately draws nothing in the dark theme:
 * its callers swap their fill to the focus colour, which in the dark is a bright block that would
 * swallow a same-colour ring. Nothing here changes fill, so the dark theme gets the same 2.dp
 * accent ring drawn by hand.
 *
 * [interactions] must also be handed to the widget itself, through its interactionSource
 * parameter, so the state collected here is the widget's own focus rather than a parallel guess.
 */
@Composable
private fun Modifier.tvFocusRing(
    interactions: MutableInteractionSource,
    shape: Shape,
): Modifier {
    if (isTouch()) return this
    val focused by interactions.collectIsFocusedAsState()
    return this
        .focusRing(focused, shape)
        .then(
            if (focused && LocalDarkTheme.current) {
                Modifier.border(Focus.Ring, Tone.accent, shape)
            } else {
                Modifier
            },
        )
}

/**
 * "Remove after watching": one row, the whole of it the switch, so a remote lands on one thing and
 * OK flips it. The deleting itself happens in [com.tmplayer.data.RemoveAfterWatching], which runs
 * for the life of the process.
 */
@Composable
private fun RemoveAfterWatchingRow(checked: Boolean, onChange: (Boolean) -> Unit) {
    val s = LocalStrings.current
    if (!isTouch()) {
        // Settings' own switch row on a television, so the one setting on this screen fills
        // with the focus colour like every other setting does, rather than wearing a ring.
        com.tmplayer.ui.settings.ToggleRow(
            title = s.downloadsRemoveAfterWatching,
            subtitle = s.downloadsRemoveAfterWatchingBody,
            icon = TmIcons.Delete,
            checked = checked,
            onToggle = { onChange(!checked) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        return
    }
    val interactions = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Corner.Medium)
    val tick = com.tmplayer.ui.components.rememberToggleTick()
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CARD_EDGE)
            .clip(shape)
            .tvFocusRing(interactions, shape)
            .toggleable(
                value = checked,
                interactionSource = interactions,
                indication = LocalIndication.current,
                role = Role.Switch,
                onValueChange = { on -> tick(on); onChange(on) },
            )
            .padding(horizontal = CARD_PAD, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.downloadsRemoveAfterWatching, style = MaterialTheme.typography.titleMedium)
            Text(
                s.downloadsRemoveAfterWatchingBody,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Drawn, not pressed: the row is the control, so the switch takes no focus of its own.
        Switch(checked = checked, onCheckedChange = null)
    }
}

/**
 * [TextButton], with the ring a remote needs.
 *
 * Every plain text action on this screen goes through here: the top bar, the queue heading and
 * the dialog buttons, so none of them can be the one control focus disappears on. The dialogs
 * matter most: a viewer who cannot see whether Delete or Keep holds focus is one press away from
 * deleting the wrong thing. On a phone this is exactly the stock button.
 */
@Composable
private fun TextAction(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    if (!isTouch()) {
        TvPill(label, icon = null, enabled = enabled, onClick = onClick)
        return
    }
    val interactions = remember { MutableInteractionSource() }
    TextButton(
        onClick = onClick,
        enabled = enabled,
        interactionSource = interactions,
        modifier = Modifier.tvFocusRing(interactions, CircleShape),
    ) {
        Text(label)
    }
}

/** What the top section is called, which depends on whether anything is actually moving. */
private fun queueHeading(active: List<OfflineDownloads.Progress>): String {
    val waiting = active.count { it.stage == OfflineDownloads.Stage.Queued }
    return when {
        active.any { it.stage == OfflineDownloads.Stage.Running } && waiting > 0 ->
            L.downloadsHeadingRunningWaiting(count = waiting)
        active.any { it.stage == OfflineDownloads.Stage.Running } -> L.downloadsHeadingRunning
        active.all { it.stage == OfflineDownloads.Stage.NoWifi } -> L.downloadsWaitingWifi
        active.all { it.stage == OfflineDownloads.Stage.Offline } -> L.downloadsWaitingConnection
        active.all { it.stage == OfflineDownloads.Stage.Paused } -> L.downloadsPaused
        else -> L.downloadsHeadingQueued
    }
}

/** A section heading with the one control that acts on the whole of the section beside it. */
@Composable
private fun QueueHeading(title: String, bulk: String?, onBulk: () -> Unit) {
    Row(
        // The button's own 12 dp of padding leaves its word on the cards' inner edge too.
        modifier = Modifier.fillMaxWidth().padding(start = TEXT_EDGE, end = TEXT_EDGE - 12.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (bulk != null) {
            TextAction(bulk, onClick = onBulk)
        }
    }
}

/**
 * What this device holds, as the storage ring with its legend, and Free up space under it.
 *
 * The whole device rather than the downloads alone: a friend saw 2.5 GB used with nothing on the
 * Cached tab, and a panel that names only part of the space is how that happens. Every part of
 * TMPlayer's own share is named and measured, and each that can be freed is a press away
 * ([actions]). A phone has the ring over its legend; a television and a wide screen put them side
 * by side, so the card does not push the list off a 540 dp screen.
 */
@Composable
internal fun StorageSummary(
    figures: StorageChart.Figures,
    measured: Boolean,
    actions: Map<StorageChart.Part, () -> Unit>,
    onFreeUp: () -> Unit,
    selected: StorageChart.Part? = null,
) {
    val s = LocalStrings.current
    val tv = !isTouch()
    Card(
        Modifier.fillMaxWidth().padding(CARD_EDGE),
        shape = if (tv) RoundedCornerShape(Corner.Large) else MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = Tone.surface),
    ) {
        BoxWithConstraints(Modifier.padding(CARD_PAD)) {
            val wide = tv || maxWidth >= 560.dp
            Column {
                StorageDonutPanel(
                    figures = figures,
                    wide = wide,
                    measured = measured,
                    actions = actions,
                    ringSize = when {
                        tv -> 184.dp
                        wide -> 168.dp
                        else -> 196.dp
                    },
                    large = tv,
                    selected = selected,
                )
                CardActions {
                    SecondaryAction(s.downloadsFreeUpSpace, TmIcons.Delete, onFreeUp)
                }
            }
        }
    }
}

/**
 * The lines of Free up space, each saying what it removes and what that frees ([FreeUpSpace]
 * picks them). Focus lands on the first that can be pressed.
 */
@Composable
internal fun FreeUpLines(lines: List<FreeUpSpace.Line>, first: FocusRequester, onPick: (FreeUpSpace.Kind) -> Unit) {
    val s = LocalStrings.current
    val f = s.formatter
    val focusAt = lines.indexOfFirst { it.enabled }
    lines.forEachIndexed { index, line ->
        val title: String
        val detail: String
        val icon: ImageVector
        when (line.kind) {
            FreeUpSpace.Kind.LowStorage -> {
                title = s.downloadsFreeLowStorage(mode = s.settingsLowStorage)
                detail = s.downloadsFreeLowStorageDetail(size = f.bytes(com.tmplayer.player.LowStorageWindow.WINDOW_BYTES))
                icon = TmIcons.Archive
            }
            FreeUpSpace.Kind.Recent -> {
                title = s.downloadsFreeRecent
                detail = if (line.enabled) s.downloadsFreeRecentDetail(size = f.bytes(line.bytes)) else s.downloadsNothingToFree
                icon = TmIcons.Clock
            }
            FreeUpSpace.Kind.Unfinished -> {
                title = s.downloadsFreeUnfinished
                detail = s.downloadsFreeUnfinishedDetail(count = line.count, size = f.bytes(line.bytes))
                icon = TmIcons.Download
            }
            FreeUpSpace.Kind.Pictures -> {
                title = s.downloadsFreePictures
                detail = if (line.enabled) s.downloadsFreePicturesDetail(size = f.bytes(line.bytes)) else s.downloadsNothingToFree
                icon = TmIcons.Image
            }
            FreeUpSpace.Kind.ChooseDownloads -> {
                title = s.downloadsChooseToDelete
                detail = if (line.enabled) s.downloadsChooseToDeleteDetail else s.downloadsNothingToFree
                icon = TmIcons.Checklist
            }
        }
        com.tmplayer.ui.components.ChoiceLine(
            title,
            detail = detail,
            icon = icon,
            enabled = line.enabled,
            modifier = if (index == focusAt) Modifier.focusRequester(first) else Modifier,
        ) { onPick(line.kind) }
    }
}

/**
 * A video file on the disk that no record names any more, on its own card like every other row
 * here, with the one thing to do about it.
 */
@Composable
private fun StrayCard(stray: com.tmplayer.data.WatchCacheRules.Stray, onDelete: () -> Unit) {
    val s = LocalStrings.current
    RowCard {
        Text(stray.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        Text(
            listOf(s.formatter.bytes(stray.bytes), stray.fileName).joinToString(DOT),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        CardActions {
            SecondaryAction(s.commonDelete, TmIcons.Delete, onDelete, danger = true)
        }
    }
}

/** A section's name on the one page: Downloaded, over the downloads. */
@Composable
private fun SectionHeading(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = TEXT_EDGE, end = TEXT_EDGE, top = 16.dp, bottom = 4.dp),
    )
}

/** A show's name over its downloaded episodes, with how many there are and what they take. */
@Composable
private fun ShowHeading(title: String, detail: String) {
    Column(Modifier.fillMaxWidth().padding(start = TEXT_EDGE, end = TEXT_EDGE, top = 12.dp, bottom = 2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/**
 * Recently played's heading at the foot of the page, which opens and closes the section: those
 * videos are not downloads, so they stay out of the way until somebody asks for them.
 */
@Composable
private fun CollapsibleHeading(title: String, detail: String?, open: Boolean, onToggle: () -> Unit) {
    val s = LocalStrings.current
    val interactions = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(Corner.Medium)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = CARD_EDGE, vertical = 8.dp)
            .clip(shape)
            .tvFocusRing(interactions, shape)
            .clickable(interactionSource = interactions, indication = LocalIndication.current, role = Role.Button, onClick = onToggle)
            .padding(horizontal = CARD_PAD, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) {
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Icon(
            if (open) TmIcons.ChevronUp else TmIcons.ChevronDown,
            contentDescription = if (open) s.commonHide else s.commonShow,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}


/**
 * The shell every row on this screen is drawn in: a filled card with an edge and its own space.
 *
 * Both kinds of row use it, so a video arriving and a video that has arrived are plainly the same
 * sort of thing at different stages, and neither can be confused with the storage panel above them.
 */
@Composable
private fun RowCard(
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    onHold: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    // A clickable card is a focusable card, and on a television that is a trap: directional
    // search will not step from a focused parent onto the buttons drawn inside it, so a card
    // that takes D-pad focus in browse mode swallows the remote one row above Watch, with no
    // ring to say where it went. So the card only takes D-pad focus while a press on it means
    // something a remote wants, toggling its tick in a selection, and it wears the ring then;
    // the rest of the time the remote lands straight on the buttons, and the hold-to-select
    // shortcut stays what it always was, a touch gesture, with the Select button covering it.
    val dpadFocusable = onClick != null || isTouch()
    val phone = usesFloatingChrome()
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = CARD_EDGE, vertical = 6.dp)
            .tvFocusRing(interactions, RoundedCornerShape(Corner.Large))
            .then(
                if (onClick == null && onHold == null) {
                    Modifier
                } else {
                    Modifier
                        .focusProperties { canFocus = dpadFocusable }
                        .combinedClickable(
                            interactionSource = interactions,
                            indication = LocalIndication.current,
                            onClick = { onClick?.invoke() ?: onHold?.invoke() },
                            onLongClick = onHold,
                        )
                },
            ),
        // A phone's card is History's: its large shape, its fill, no edge but a chosen one.
        shape = if (phone) MaterialTheme.shapes.large else RoundedCornerShape(Corner.Large),
        // The same fill the Continue watching cards use, not the scheme's surfaceVariant: that
        // one sits a shade above the window on a dark theme, so a screenful reads as pale panels
        // floating on the background rather than as the app's own cards.
        colors = CardDefaults.cardColors(containerColor = Tone.surface),
        border = when {
            accent != null -> BorderStroke(2.dp, accent)
            phone -> null
            else -> BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
    ) {
        // [CARD_PAD] on every device, the storage card's and the switch row's own figure, so the
        // words of every card, the note over the list and the queue's heading start on one line.
        Column(Modifier.padding(CARD_PAD)) { content() }
    }
}

/**
 * The buttons under a card: side by side, equal, full width, and each with its name on it.
 *
 * A single button still fills the row rather than sitting at one end, because a target the width
 * of the card is one a thumb finds without looking.
 */
@Composable
private fun CardActions(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Spacer(Modifier.height(14.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        content = content,
    )
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.PrimaryAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    if (!isTouch()) {
        TvPill(label, icon, modifier = Modifier.weight(1f), onClick = onClick)
        return
    }
    val interactions = remember { MutableInteractionSource() }
    FilledTonalButton(
        onClick = onClick,
        interactionSource = interactions,
        modifier = Modifier.weight(1f).tvFocusRing(interactions, CircleShape),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.SecondaryAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    if (!isTouch()) {
        TvPill(label, icon, danger = danger, modifier = Modifier.weight(1f), onClick = onClick)
        return
    }
    val colour = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val interactions = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactions,
        modifier = Modifier.weight(1f).tvFocusRing(interactions, CircleShape),
        border = BorderStroke(1.dp, colour.copy(alpha = 0.5f)),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp), tint = colour)
        Spacer(Modifier.width(8.dp))
        Text(label, color = colour, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * An action with no word on it, sized as a square rather than sharing the row's width.
 *
 * For Delete, which reads well enough as a bin and should not carry the same visual weight as
 * Watch. The square also leaves Watch and Share the width their labels need, so neither of them
 * ellipsises on a narrow phone.
 */
@Composable
private fun IconOnlyAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    danger: Boolean = false,
) {
    if (!isTouch()) {
        TvPill(label, icon, danger = danger, showLabel = false, onClick = onClick)
        return
    }
    val colour = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val interactions = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        interactionSource = interactions,
        modifier = Modifier.size(48.dp).tvFocusRing(interactions, CircleShape),
        border = BorderStroke(1.dp, colour.copy(alpha = 0.5f)),
        contentPadding = PaddingValues(0.dp),
    ) {
        Icon(icon, contentDescription = label, modifier = Modifier.size(18.dp), tint = colour)
    }
}

/**
 * A finished video: what it is, what it costs, and the three things worth doing with it.
 *
 * While a selection is on, the whole card is one target that ticks and unticks, which is how a
 * phone's list of anything behaves and keeps three small buttons out of a tick target.
 */
@Composable
private fun DownloadCard(
    row: DownloadRow,
    picking: Boolean,
    checked: Boolean,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    onToggle: () -> Unit,
    onHold: (() -> Unit)?,
) {
    val s = LocalStrings.current
    val details = listOfNotNull(
        // A part-loaded video says both figures: "710 MB" alone reads as the size of the video,
        // while "710 MB of 1.4 GB" says what is on the disk and what would finish it.
        if (row.partial && row.totalBytes > row.bytes) {
            s.commonOfTotal(done = s.formatter.bytes(row.bytes), total = s.formatter.bytes(row.totalBytes))
        } else {
            s.formatter.bytes(row.bytes).takeIf { row.bytes > 0 }
        },
        s.formatter.duration(row.durationSec.toLong()).ifBlank { null },
        row.chatTitle.ifBlank { null },
        // A part-loaded file still occupies its bytes, and that is the row a viewer looking for
        // space is most likely to want gone.
        // A cached video was never asked for as a download, so it is cached, not downloaded.
        // A download that stopped part way says so, and what it holds here (user, 2026-10-18).
        if (row.partial) (if (row.cached) s.downloadsPartCached else s.downloadsUnfinishedOnDevice(size = s.formatter.bytes(row.bytes))) else null,
        if (row.missing) s.downloadsFileMissing else null,
    ).joinToString(DOT)

    val pickTick = com.tmplayer.ui.components.rememberToggleTick()
    RowCard(
        accent = if (checked) MaterialTheme.colorScheme.primary else null,
        onClick = if (picking) ({ pickTick(!checked); onToggle() }) else null,
        onHold = onHold,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (picking) {
                Checkbox(checked = checked, onCheckedChange = { pickTick(!checked); onToggle() })
                Spacer(Modifier.width(8.dp))
            }
            run {
                // The small poster History's rows and a chat's rows wear, on the phone, the
                // television and the desktop alike, so a download reads as the same kind of thing:
                // the picture, or the generated cover until there is one.
                VideoPosterThumb(
                    item = row.record.toMediaItem(),
                    fraction = if (row.partial && row.totalBytes > 0) (row.bytes.toFloat() / row.totalBytes).coerceIn(0f, 1f) else 0f,
                )
                Spacer(Modifier.width(14.dp))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (details.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        details,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        // Hidden while picking: a Delete button inside something the viewer is tapping to select
        // is a mistake waiting to be made.
        if (!picking) {
            CardActions {
                when {
                    // Something took the file. The record is all that is left to deal with.
                    row.missing -> SecondaryAction(s.commonRemove, TmIcons.Delete, onDelete, danger = true)
                    row.cached -> {
                        PrimaryAction(s.navWatch, TmIcons.Play, onPlay)
                        // The share slot's place: what a cached video wants is keeping.
                        SecondaryAction(s.downloadsSaveToDownloads, TmIcons.Download, onShare)
                        IconOnlyAction(s.commonDelete, TmIcons.Delete, onDelete, danger = true)
                    }
                    row.partial -> {
                        PrimaryAction(s.commonResume, TmIcons.Refresh, onPlay)
                        IconOnlyAction(s.commonDelete, TmIcons.Delete, onDelete, danger = true)
                    }
                    else -> {
                        PrimaryAction(s.navWatch, TmIcons.Play, onPlay)
                        SecondaryAction(s.commonShare, TmIcons.Share, onShare)
                        IconOnlyAction(s.commonDelete, TmIcons.Delete, onDelete, danger = true)
                    }
                }
            }
        }
    }
}

/**
 * A video still coming down, or waiting to, at the top of the list where the viewer went looking.
 *
 * Every stage a video can be in has a card, a line saying which stage that is, and the buttons
 * that move it to another one. Nothing here comes from the finished-downloads record, which the
 * service only writes on completion.
 */
@Composable
private fun ActiveDownloadCard(
    progress: OfflineDownloads.Progress,
    place: Int,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    /** Free up space, for a row that failed because the drive was full. */
    onFreeUp: (() -> Unit)? = null,
) {
    val s = LocalStrings.current
    val stage = progress.stage
    val failed = stage == OfflineDownloads.Stage.Failed
    val fraction = progress.fraction
    val size = if (progress.totalBytes > 0) {
        s.commonOfTotal(done = s.formatter.bytes(progress.downloadedBytes), total = s.formatter.bytes(progress.totalBytes))
    } else {
        s.formatter.bytes(progress.downloadedBytes)
    }
    val line = when (stage) {
        OfflineDownloads.Stage.Failed -> progress.failure.orEmpty()
        OfflineDownloads.Stage.Queued -> listOfNotNull(
            if (place <= 0) s.downloadsNextInQueue else s.downloadsPlaceInQueue(place = place + 1),
            s.formatter.bytes(progress.totalBytes).takeIf { progress.totalBytes > 0 },
            // Only worth saying when an earlier attempt left something behind, since a queued
            // video normally has nothing on disk and "0 B so far" is not news.
            s.downloadsAlreadyHere(percent = s.formatter.percent((fraction ?: 0f).toDouble()))
                .takeIf { progress.downloadedBytes > 0 },
        ).joinToString(DOT)
        OfflineDownloads.Stage.Paused -> listOfNotNull(
            s.downloadsPaused,
            fraction?.let { s.formatter.percent(it.toDouble()) },
            size,
        ).joinToString(DOT)
        OfflineDownloads.Stage.NoWifi -> listOfNotNull(
            s.downloadsWaitingWifi,
            fraction?.let { s.formatter.percent(it.toDouble()) },
            size,
        ).joinToString(DOT)
        OfflineDownloads.Stage.Offline -> listOfNotNull(
            s.downloadsWaitingConnection,
            fraction?.let { s.formatter.percent(it.toDouble()) },
            size,
        ).joinToString(DOT)
        OfflineDownloads.Stage.Moving ->
            if (progress.heldByPlayer) s.downloadsFinishesAfterPlayback else s.downloadsMovingIntoDownloads
        OfflineDownloads.Stage.Running -> listOfNotNull(
            fraction?.let { s.formatter.percent(it.toDouble()) },
            size,
            s.formatter.speed(progress.bytesPerSecond)
                .takeIf { progress.bytesPerSecond >= 1024 },
            s.formatter.eta(progress.remainingSeconds).ifBlank { null },
        ).joinToString(DOT)
    }

    // Stopped, waiting or failed with part of the file here: said plainly, with what it holds,
    // since that is space a viewer looking for room can get back (user, 2026-10-18).
    val held = stage != OfflineDownloads.Stage.Running && stage != OfflineDownloads.Stage.Moving &&
        progress.downloadedBytes > 0
    RowCard(accent = if (failed) MaterialTheme.colorScheme.error.copy(alpha = 0.5f) else null) {
        Text(
            progress.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (held) {
            Spacer(Modifier.height(2.dp))
            Text(
                s.downloadsUnfinishedOnDevice(size = s.formatter.bytes(progress.downloadedBytes)),
                style = MaterialTheme.typography.bodySmall,
                color = Tone.caution,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            line,
            style = MaterialTheme.typography.bodySmall,
            color = if (failed) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (!failed) {
            Spacer(Modifier.height(10.dp))
            // A bar for every stage, so the cards are one shape and the list does not jump as
            // each takes its turn. Indeterminate only while something is moving with no figure
            // to show for it yet.
            if (fraction == null && stage == OfflineDownloads.Stage.Running) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Corner.Small)),
                )
            } else {
                LinearProgressIndicator(
                    progress = { fraction ?: 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Corner.Small)),
                )
            }
        }
        CardActions {
            when (stage) {
                OfflineDownloads.Stage.Running ->
                    PrimaryAction(s.commonPause, TmIcons.Pause, onPause)
                OfflineDownloads.Stage.Paused ->
                    PrimaryAction(s.commonResume, TmIcons.Play, onResume)
                // Refused because the chat forbids keeping a copy: trying again is refused the
                // same way, so the only offer left is Dismiss below.
                // Refused for room: Try again would find the same full drive, so the first
                // offer is the one that makes room, and Try again stays beside it for after.
                OfflineDownloads.Stage.Failed ->
                    if (com.tmplayer.data.isNoSpaceFailure(progress.failure) && onFreeUp != null) {
                        PrimaryAction(s.downloadsFreeUpSpace, TmIcons.Delete, onFreeUp)
                        PrimaryAction(s.commonTryAgain, TmIcons.Refresh, onResume)
                    } else if (progress.failure != ContentProtection.NOT_SAVABLE) {
                        PrimaryAction(s.commonTryAgain, TmIcons.Refresh, onResume)
                    }
                // It will start itself the moment the signal is back, so the useful offer is the
                // other one: hold it, and do not.
                OfflineDownloads.Stage.Offline, OfflineDownloads.Stage.NoWifi ->
                    PrimaryAction(s.commonPause, TmIcons.Pause, onPause)
                // Nothing to pause that has not started. Cancel below fills the row on its own.
                OfflineDownloads.Stage.Queued -> Unit
                // Fetched already; the move is short, and stopping it half way gains nothing.
                OfflineDownloads.Stage.Moving -> Unit
            }
            SecondaryAction(
                label = if (failed) s.commonDismiss else s.commonCancel,
                icon = TmIcons.Close,
                onClick = onCancel,
                danger = true,
            )
        }
    }
}

/**
 * The height an empty tab's message is given. A phone's share of the screen centres it in the
 * space under the storage card. A television's would not fit: the card and the switch already take
 * most of a 540 dp screen, so a share of the whole list put the message's last line under the
 * bottom edge, and with nothing focusable down there the remote could never scroll to it. There
 * the message takes only the room it needs, straight under the switch.
 */
@Composable
private fun LazyItemScope.emptyStateHeight(fraction: Float): Modifier =
    if (isTouch()) Modifier.fillParentMaxHeight(fraction) else Modifier.padding(top = 8.dp)

/**
 * Downloads on a phone, drawn like the pages behind the picture it is reached from (History is the
 * model): its name along the top beside the round Back, one page of sections rather than tabs,
 * cards shaped like History's, and the floating bar along the foot with the picture ringed. Select and
 * Delete all ride in the small pill over the bar, so nothing to press is at the top.
 *
 * A selection takes the bar's place with one of its own: the count, Select all, Share and Delete,
 * with Close where Back was. Outside the app's bar (the screenshot fixture) the pill's buttons sit
 * in a sticky bar of their own.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneDownloads(
    title: String,
    tab: Int,
    tabLabel: (Int) -> String,
    onTab: (Int) -> Unit,
    picking: Boolean,
    chosenCount: Int,
    canSelect: Boolean,
    allChosen: Boolean,
    onBack: () -> Unit,
    onSelect: () -> Unit,
    onDeleteAll: () -> Unit,
    onLeavePicking: () -> Unit,
    onSelectAll: () -> Unit,
    onShareChosen: () -> Unit,
    onDeleteChosen: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    body: LazyListScope.() -> Unit,
) {
    val s = LocalStrings.current
    val hosted = LocalTouchChrome.current != null
    val actions: (@Composable RowScope.() -> Unit)? = if (canSelect && !picking) {
        {
            FloatingBarIcon(TmIcons.Checklist, s.downloadsSelect, onSelect)
            // Behind the dots: a button that empties the list should take two presses, not one.
            BarOverflow(listOf(s.downloadsDeleteAll to onDeleteAll))
        }
    } else {
        null
    }
    TouchPage(TouchPageSpec(current = null, inProfile = true, hidden = picking, actions = actions))

    val compact = rememberCompactChrome()
    val navigationBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // The list's end clears whatever floats over it: the selection's bar, the app's bar, or the
    // sticky bar standing in for it.
    val bottom = when {
        picking || (!hosted && actions != null) -> navigationBar + FloatingChrome.clearance(compact)
        else -> maxOf(LocalBottomChrome.current, navigationBar) + 16.dp
    }
    val scroll = TopAppBarDefaults.enterAlwaysScrollBehavior()

    Box(Modifier.fillMaxSize().background(Tone.background)) {
        Scaffold(
            containerColor = Tone.background,
            modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
            topBar = { FloatingPageTitle({ Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, scroll) },
            // The list runs to the foot of the window, under the bars that float over it.
            contentWindowInsets = WindowInsets.statusBars,
        ) { padding ->
            // One page, no tabs: the sections follow one another down the list.
            Column(Modifier.fillMaxSize().padding(padding)) {
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = bottom),
                        content = body,
                    )
                    ScrollToTop(listState)
                }
            }
        }

        FloatingBackButton(
            icon = if (picking) TmIcons.Close else TmIcons.ArrowBack,
            // While picking, the button leaves the selection rather than the screen: that is what
            // Back does here, and the two must not disagree.
            label = if (picking) s.downloadsLeaveSelection else s.commonBack,
            onClick = if (picking) onLeavePicking else onBack,
            modifier = Modifier.align(Alignment.TopStart),
        )

        if (picking) {
            StickyActionBar(Modifier.align(Alignment.BottomCenter), compact = compact, fill = true) {
                Text(
                    if (chosenCount == 0) s.downloadsSelectVideos else s.downloadsSelected(count = chosenCount),
                    style = MaterialTheme.typography.titleSmall,
                    color = Tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 14.dp),
                )
                // Everything the list is showing, and never more than that.
                TextButton(onClick = onSelectAll, enabled = !allChosen) { Text(s.downloadsSelectAll, maxLines = 1) }
                FloatingBarIcon(TmIcons.Share, s.commonShare, onShareChosen, enabled = chosenCount > 0)
                FloatingBarIcon(TmIcons.Delete, s.commonDelete, onDeleteChosen, tint = MaterialTheme.colorScheme.error, enabled = chosenCount > 0)
            }
        } else if (!hosted && actions != null) {
            StickyActionBar(Modifier.align(Alignment.BottomCenter), compact = compact, content = actions)
        }
    }
}

/**
 * Downloads on a television, laid out like the app's other full pages there (Settings, a chat's
 * videos): the name in the large heading on the left with the round Back before it, the three
 * tabs as the History page's chips with room between them, and the actions as the same pills the
 * rest of the television uses, filled with the focus colour rather than ringed.
 *
 * Stock Material's app bar and tab row used to draw this page: a small title, every tab in the
 * accent, nothing focused on arrival, and a square ring around a focused tab that butted against
 * the selected tab's line.
 */
@Composable
private fun TvDownloads(
    tab: Int,
    tabLabel: (Int) -> String,
    onTab: (Int) -> Unit,
    picking: Boolean,
    chosenCount: Int,
    canSelect: Boolean,
    allChosen: Boolean,
    onBack: () -> Unit,
    onSelect: () -> Unit,
    onDeleteAll: () -> Unit,
    onLeavePicking: () -> Unit,
    onSelectAll: () -> Unit,
    onShareChosen: () -> Unit,
    onDeleteChosen: () -> Unit,
    listState: androidx.compose.foundation.lazy.LazyListState,
    /** Bumped after a delete, whose row took the remote with it: focus goes back to the tab. */
    refocus: Int = 0,
    body: LazyListScope.() -> Unit,
) {
    val s = LocalStrings.current
    val tabFocus = remember { List(3) { FocusRequester() } }
    LaunchedEffect(refocus) {
        if (refocus == 0) return@LaunchedEffect
        withFrameNanos { }
        runCatching { tabFocus[tab].requestFocus() }
    }
    // The remote lands on the tab being shown, as the rail lands on the page being shown: before
    // this nothing held focus until the first press, which then went to Back.
    LaunchedEffect(Unit) {
        repeat(3) {
            withFrameNanos { }
            if (runCatching { tabFocus[tab].requestFocus() }.getOrDefault(false)) return@LaunchedEffect
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(Tone.background)
            // The rows below keep 16dp of their own each side, so the page owes that much less to
            // the overscan margin and the heading, tabs and rows all start on one line.
            // The page starts right below the top bar.
            .padding(start = Tv.SafeH - 16.dp, end = Tv.SafeH - 16.dp, top = 4.dp),
    ) {
        // Under the top bar, whose Back goes up to the bar, the page keeps a compact one line
        // title (the bar marks the account's picture, not Downloads) and no back button but the
        // one that leaves a selection.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (picking) {
                TvPill(
                    label = if (picking) s.downloadsLeaveSelection else s.commonBack,
                    icon = if (picking) TmIcons.Close else TmIcons.ArrowBack,
                    showLabel = false,
                    onClick = if (picking) onLeavePicking else onBack,
                )
                Spacer(Modifier.width(16.dp))
            }
            Column(Modifier.weight(1f)) {
                TvText(
                    when {
                        !picking -> s.downloadsTitle
                        chosenCount == 0 -> s.downloadsSelectVideos
                        else -> s.downloadsSelected(count = chosenCount)
                    },
                    style = TvTheme.typography.titleLarge,
                    color = Tone.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    picking -> {
                        TvPill(s.downloadsSelectAll, TmIcons.Checklist, enabled = !allChosen, onClick = onSelectAll)
                        if (chosenCount > 0) {
                            TvPill(s.commonShare, TmIcons.Share, onClick = onShareChosen)
                            TvPill(s.commonDelete, TmIcons.Delete, danger = true, onClick = onDeleteChosen)
                        }
                    }
                    canSelect -> {
                        TvPill(s.downloadsSelect, TmIcons.Checklist, onClick = onSelect)
                        TvPill(s.downloadsDeleteAll, TmIcons.Delete, danger = true, onClick = onDeleteAll)
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        Row(
            Modifier.padding(horizontal = 16.dp).focusRestorer(tabFocus[tab]),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TvPill(tabLabel(ONGOING), TmIcons.Download, selected = tab == ONGOING, modifier = Modifier.focusRequester(tabFocus[ONGOING])) { onTab(ONGOING) }
            TvPill(tabLabel(COMPLETED), TmIcons.CheckCircle, selected = tab == COMPLETED, modifier = Modifier.focusRequester(tabFocus[COMPLETED])) { onTab(COMPLETED) }
            TvPill(tabLabel(CACHED), TmIcons.Clock, selected = tab == CACHED, modifier = Modifier.focusRequester(tabFocus[CACHED])) { onTab(CACHED) }
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().keepTopInSight(listState),
                contentPadding = PaddingValues(bottom = Tv.SafeV),
                content = body,
            )
            ScrollToTop(listState)
        }
    }
}

/**
 * A pill on the television's Downloads page: a tab, an action on a card or in the heading.
 *
 * Drawn as the browse pages draw theirs (BrowseScreen's chips and pills): the step above the page
 * at rest, a tint when it is the chosen tab, the focus colour under the remote, and the accent
 * ring in daylight where that fill alone is too pale. [danger] fills red under the remote.
 */
@Composable
private fun TvPill(
    label: String,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    danger: Boolean = false,
    enabled: Boolean = true,
    /** False draws the icon alone; the label is still what a screen reader says. */
    showLabel: Boolean = true,
    onClick: () -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val focused by interactions.collectIsFocusedAsState()
    val background by animateColorAsState(
        targetValue = when {
            focused && danger -> Danger
            focused -> Tone.focusFill
            selected -> Tone.accent.copy(alpha = 0.16f)
            else -> Tone.surfaceHigh
        },
        animationSpec = tween(140),
        label = "downloadsPill",
    )
    val foreground = when {
        focused && danger -> Color.White
        focused -> Tone.onFocusFill
        danger -> Danger
        selected -> Tone.accent
        else -> Tone.text
    }
    Row(
        modifier
            .height(48.dp)
            .clip(CircleShape)
            .background(background)
            .focusRing(focused && !danger, CircleShape)
            .semantics { if (selected) this.selected = true }
            .clickable(interactionSource = interactions, indication = null, role = Role.Button) { if (enabled) onClick() }
            .alpha(if (enabled) 1f else 0.5f)
            // An icon on its own gets even padding, so the pill comes out round rather than wide.
            .padding(horizontal = if (showLabel) 18.dp else 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = if (showLabel) null else label, tint = foreground, modifier = Modifier.size(22.dp))
        }
        if (showLabel) {
            TvText(
                label,
                style = TvTheme.typography.bodyLarge,
                fontWeight = if (selected || focused) FontWeight.SemiBold else FontWeight.Normal,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The three tabs, as indices, because that is what [TabRow] counts in. */
private const val ONGOING = 0
private const val COMPLETED = 1
private const val CACHED = 2

/** The separator these rows join their figures with, spaced as the rest of the app spaces it. */
private const val DOT = "  ·  "

/** How far a card on this screen sits in from the sides of the list. */
private val CARD_EDGE = 16.dp

/** How far a card's words sit in from the card's own edge. */
private val CARD_PAD = 16.dp

/** Where words outside a card start, on the line the words inside the cards start on. */
private val TEXT_EDGE = CARD_EDGE + CARD_PAD
