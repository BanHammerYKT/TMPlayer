package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.key
import com.tmplayer.data.BookmarkJump
import com.tmplayer.data.ContentProtection
import com.tmplayer.data.DetailAction
import com.tmplayer.data.DetailActions
import com.tmplayer.data.DetailContext
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.MediaItem
import com.tmplayer.data.OfflineDownloads
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.valueOrNull
import com.tmplayer.platform.Background
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.desktop.DownloadIndex
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.desktop.setWatchLater
import com.tmplayer.desktop.setWatched
import com.tmplayer.ui.browse.DETAIL_PANE_WIDTH
import com.tmplayer.ui.browse.DetailRow
import com.tmplayer.ui.browse.MediaDetailPanel
import com.tmplayer.ui.browse.detailRow
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.nav.BackHandler
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.FloatingTone
import com.tmplayer.ui.theme.floatingBorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File

/**
 * The detail pane (R5) over whatever page is showing, when [ShellState.detail] asks for one: the
 * shared [MediaDetailPanel] along the right edge, over a scrim. Esc, Back or a click on the scrim
 * closes it, and focus goes back to the poster it came from.
 *
 * What the shell draws where the page is too narrow to keep beside the pane ([DetailPaneSide]),
 * and what the render tests draw over a page of their own.
 */
@Composable
fun BoxScope.DetailPaneHost(state: ShellState) {
    val request = state.detail ?: return
    BackHandler { state.closeDetail() }
    Box(
        Modifier
            .matchParentSize()
            .background(FloatingTone.scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { state.closeDetail() },
    )
    PaneSheet(state, request, Modifier.align(Alignment.CenterEnd))
}

/**
 * The detail pane docked as a side panel beside the page, with no scrim: the page stays in view
 * and in reach, a click on another poster swaps what the pane shows, and Esc, Back, its close
 * button or a click on an empty part of the page hide it (see [closeDetailOnEmptyClick]).
 */
@Composable
fun DetailPaneSide(state: ShellState) {
    val request = state.detail ?: return
    BackHandler { state.closeDetail() }
    PaneSheet(state, request, Modifier)
}

@Composable
private fun PaneSheet(state: ShellState, request: DetailRequest, modifier: Modifier) {
    Surface(
        shape = RoundedCornerShape(topStart = Corner.ExtraLarge, bottomStart = Corner.ExtraLarge),
        color = FloatingTone.sheet,
        border = floatingBorder(),
        modifier = modifier
            .fillMaxHeight()
            .width(DETAIL_PANE_WIDTH)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    state.closeDetail()
                    true
                } else {
                    false
                }
            },
    ) {
        // A new video is a new pane: nothing of the last one's state carries over.
        key(request.item.id) { DetailPane(state, request) }
    }
}

/** From this width of page the pane docks beside it: the pane and a row of posters still fit. */
internal val DOCK_FROM = DETAIL_PANE_WIDTH + 560.dp

@Composable
private fun DetailPane(state: ShellState, request: DetailRequest) {
    val s = LocalStrings.current
    val item = request.item
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val progress by state.settings.watchProgress.collectAsState(initial = emptyMap())
    val watchedList by state.watched.watched.collectAsState(initial = emptyMap())
    val laterKeys by state.watched.watchLater.keys.collectAsState(initial = emptySet())
    val history by state.settings.downloadHistory.collectAsState(initial = emptyList())
    val downloads by OfflineDownloads.active.collectAsState()
    val key = SettingsStore.progressKey(item.chatId, item.messageId)
    val point = progress[key]
    val finished = key in watchedList
    val record = history.firstOrNull { it.chatId == item.chatId && it.messageId == item.messageId }
    val queued = downloads[item.fileId]?.busy == true
    val title = request.chatTitle.ifBlank { state.chatTitleOf(item.chatId) }
    val chat = state.chatOf(item.chatId)
    val free by produceState(0L, item.id) {
        value = withContext(Dispatchers.IO) { DiskInfo.of(DesktopPaths.downloadsDir).freeBytes }
    }
    val first = remember(item.id) { FocusRequester() }
    val bookmarks by remember(item.chatId, item.messageId) {
        state.bookmarks?.bookmarks(item.chatId, item.messageId) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }.collectAsState(initial = emptyList())
    // The viewer's corrections to this file, and whether a provider's match is showing for it.
    val corrections by com.tmplayer.data.VideoCorrections.state.collectAsState()
    val correction = corrections[item.id]
    val matched = com.tmplayer.ui.online.rememberMatched(item) != null
    // The comments under a channel post (CP81).
    val comments = com.tmplayer.ui.browse.rememberCommentCount(item)

    val actions = DetailActions.of(
        DetailContext(
            canBeSaved = item.canBeSaved,
            resumeMs = point?.positionMs ?: 0L,
            finished = finished,
            watchLater = key in laterKeys,
            inDownloads = record != null,
            cached = item.onDevice,
            downloading = queued,
            canSelect = request.selectThis != null,
            // Another app is the desktop's one hand off: there is no share sheet, so Share is dropped below.
            canHandOff = true,
            hasClipboard = true,
            outsideChat = request.outsideChat && chat != null,
            canCorrect = true,
            grouped = item.versions.size > 1,
            separated = correction?.separate == true,
            matched = matched,
            wrongMatch = correction?.wrongMatch == true,
            comments = comments,
            // A copy of the finished download in a folder the viewer picks (CP82).
            canExport = true,
        ),
    ).filterNot { it == DetailAction.Share }

    fun close() = state.closeDetail()
    // The lines that close the pane before their work is done run on the process's scope: the
    // pane's own is cancelled as it leaves, which dropped the copied link and the hand off.
    val outlives = Background.scope
    // "Remove from Downloads?" is asked over the pane, which stays open until it is answered.
    var confirmingRemove by remember(item.id) { mutableStateOf(false) }
    // "Rename" is asked over the pane too, which closes once the title is saved.
    var renaming by remember(item.id) { mutableStateOf(false) }
    // The comments' videos open over the pane, which stays until they close.
    var commentsOpen by remember(item.id) { mutableStateOf(false) }

    fun run(action: DetailAction) {
        if (action == DetailAction.RemoveDownload) {
            confirmingRemove = record != null
            return
        }
        if (action == DetailAction.Rename) {
            renaming = true
            return
        }
        if (action == DetailAction.CommentVideos) {
            commentsOpen = true
            return
        }
        close()
        when (action) {
            DetailAction.Resume, DetailAction.Play -> state.openPlayer(item, startFromBeginning = false)
            DetailAction.StartOver -> state.openPlayer(item, startFromBeginning = true)
            DetailAction.MarkWatched, DetailAction.MarkUnwatched -> {
                val mark = action == DetailAction.MarkWatched
                outlives.launch {
                    runCatching { setWatched(state.watched, state.settings, item, title, mark) }
                    toast(if (mark) s.watchedMarkedWatched(item.title) else s.watchedMarkedUnwatched(item.title))
                }
            }
            DetailAction.AddWatchLater, DetailAction.RemoveWatchLater -> {
                val add = action == DetailAction.AddWatchLater
                outlives.launch {
                    runCatching { setWatchLater(state.watched, item, title, add) }
                    toast(if (add) s.detailWatchLaterAdded(item.title) else s.detailWatchLaterRemoved(item.title))
                }
            }
            DetailAction.Download -> {
                OfflineDownloads.start(state.downloads, item, title)
                toast(s.downloadsDownloadingTitle(item.title))
            }
            DetailAction.SaveToDownloads -> {
                OfflineDownloads.start(state.downloads, item, title)
                toast(s.downloadsSavingTitle(item.title))
            }
            DetailAction.InDownloads -> state.go(Destination.Downloads)
            DetailAction.RemoveDownload -> Unit
            DetailAction.CancelDownload -> OfflineDownloads.cancel(state.downloads, item.fileId)
            DetailAction.SelectVideos -> request.selectThis?.invoke()
            DetailAction.Share -> Unit
            DetailAction.OpenElsewhere -> outlives.launch {
                if (record == null && !Td.maySave(item.chatId, item.messageId)) {
                    toast(ContentProtection.NOT_SAVABLE)
                    return@launch
                }
                val file = record?.localPath?.let(::File)?.takeIf { it.isFile }
                    ?: runCatching { Td.localFilePath(Td.currentFileId(item.chatId, item.messageId, item.fileId)) }
                        .getOrNull()?.let(::File)
                if (file == null) toast(s.downloadsFileGone) else OpenExternal.open(file)
            }
            DetailAction.CopyLink -> outlives.launch {
                val link = runCatching {
                    Td.client.getMessageLink(item.chatId, item.messageId, 0, 0, "", false, false).valueOrNull?.link
                }.getOrNull()
                if (link.isNullOrBlank()) {
                    toast(s.commonNoLinks)
                } else {
                    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(link), null)
                    toast(s.commonLinkCopied)
                }
            }
            DetailAction.OpenChat -> chat?.let(state::openChat)
            DetailAction.SplitCopy, DetailAction.JoinCopies -> {
                val apart = action == DetailAction.SplitCopy
                outlives.launch { runCatching { state.settings.correct(listOf(item)) { it.copy(separate = apart) } } }
            }
            DetailAction.WrongMatch, DetailAction.UseMatch -> {
                val wrong = action == DetailAction.WrongMatch
                outlives.launch {
                    runCatching { state.settings.correct(com.tmplayer.data.VideoCorrections.filesOf(item)) { it.copy(wrongMatch = wrong) } }
                }
            }
            DetailAction.Rename, DetailAction.CommentVideos -> Unit
            DetailAction.ExportCopy -> outlives.launch {
                val file = record?.localPath?.let(::File)?.takeIf { it.isFile }
                if (file == null) {
                    toast(s.downloadsFileGone)
                    return@launch
                }
                // Asked again at the copy: the chat may have restricted saving since.
                if (!Td.maySave(item.chatId, item.messageId)) {
                    toast(ContentProtection.NOT_SAVABLE)
                    return@launch
                }
                val folder = com.tmplayer.desktop.os.FolderPicker.pick(s.keepExportFolder, DesktopPaths.downloadsDir.parentFile) ?: return@launch
                toast(s.keepExporting(item.title))
                val said = withContext(Dispatchers.IO) {
                    runCatching {
                        val target = com.tmplayer.desktop.DesktopExport.freeName(folder, file.name)
                        java.nio.file.Files.copy(file.toPath(), target.toPath())
                        s.keepExportedFolder(folder.path)
                    }.getOrElse { s.keepExportFailed(com.tmplayer.data.Failures.humanise(it)) }
                }
                toast(said)
            }
        }
    }

    val freeLabel = DiskInfo.freeLabel(free)
    val rows: List<DetailRow> = actions.map { action ->
        val row = detailRow(action, s, point, chat?.title ?: title, onSelect = { run(action) }, free = freeLabel)
            // The desktop's own copy goes to a folder, not a gallery.
            .let { if (it.action == DetailAction.ExportCopy) it.copy(label = s.keepExportFolder, detail = s.keepExportFolderDetail, icon = TmIcons.Folder) else it }
        // The desktop's own words where it has them: the queue lives on the Downloads page.
        if (action == DetailAction.CancelDownload) row.copy(label = s.downloadsCancel) else row
    }

    Column(Modifier.fillMaxWidth()) {
        // The pane's own close sits at its start, pointing the way the pane goes: at the end it
        // stood beside the window's close button, and one was taken for the other.
        Row(
            Modifier.fillMaxWidth().padding(start = 8.dp, end = 24.dp, top = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TipIconButton(TmIcons.ChevronRight, s.detailClose, { close() }, shortcut = "Esc")
            Text(
                s.detailHeading,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f).padding(start = 4.dp).semantics { heading() },
            )
        }
        // A film posted more than once opens on the copy that would play, and a copy chosen from
        // its list is remembered for the video before the pane turns to it.
        fun turnTo(copy: MediaItem) = state.openDetail(
            DetailRequest(copy, state.chatTitleOf(copy.chatId).ifBlank { request.chatTitle }, request.outsideChat, request.selectThis, request.onClosed),
        )
        LaunchedEffect(item.id) {
            if (item.versions.size <= 1) return@LaunchedEffect
            val pick = runCatching { state.settings.copyToPlay(item) }.getOrNull() ?: return@LaunchedEffect
            if (pick.id != item.id) turnTo(pick)
        }
        // Keyed by the video, so "More like this" opening another starts it at the top.
        androidx.compose.runtime.key(item.id) {
            MediaDetailPanel(
                item = item,
                chatTitle = title,
                watched = point,
                finished = finished,
                rows = rows,
                firstAction = first,
                contentPadding = PaddingValues(start = 24.dp, end = 24.dp, bottom = 24.dp),
                onOpenItem = { other ->
                    state.openDetail(DetailRequest(other, state.chatTitleOf(other.chatId), outsideChat = true, onClosed = request.onClosed))
                },
                bookmarks = bookmarks,
                // The player takes the position on opening this video, in place of its resume point.
                onOpenBookmark = { mark ->
                    close()
                    BookmarkJump.request(item.chatId, item.messageId, mark.positionMs)
                    state.openPlayer(item, startFromBeginning = false)
                },
                onPickCopy = { copy ->
                    scope.launch {
                        runCatching { state.settings.setCopyPick(copy) }
                        turnTo(copy)
                    }
                },
                copyStarted = { SettingsStore.progressKey(it.chatId, it.messageId) in progress },
                // Play's everyday companions under it, the rest at the foot, as on the phone.
                actionsAtFoot = true,
            )
        }
    }
    LaunchedEffect(item.id) { runCatching { first.requestFocus() } }

    if (commentsOpen) {
        val commentScope = rememberCoroutineScope()
        val list = remember(item.id) { com.tmplayer.ui.browse.PagedVideos.comments(commentScope, item.chatId, item.messageId) }
        MoreVideosDialog(
            title = s.detailCommentsVideos,
            note = s.detailCommentsCount(comments),
            list = list,
            empty = s.detailCommentsNone,
            round = false,
            onPlay = { video -> close(); com.tmplayer.ui.browse.LinkRequests.play(video) },
            onDismiss = { commentsOpen = false },
        )
    }

    if (renaming) {
        RenameDialog(
            initial = correction?.title ?: com.tmplayer.data.SeriesShelf.filmTitle(item),
            onSave = { title ->
                renaming = false
                // Every copy of the tile, so the title holds whichever one it shows.
                outlives.launch {
                    runCatching { state.settings.correct(com.tmplayer.data.VideoCorrections.filesOf(item)) { it.copy(title = title) } }
                }
                close()
            },
            onDismiss = { renaming = false },
        )
    }

    if (confirmingRemove) {
        ConfirmDialog(
            title = s.gridRemoveDownloadTitle,
            message = s.gridRemoveDownloadMessage(item.title),
            confirmLabel = s.commonRemove,
            onDismiss = { confirmingRemove = false },
            onConfirm = {
                confirmingRemove = false
                val kept = record
                scope.launch {
                    if (kept != null) {
                        toast(if (DownloadIndex.delete(state.settings, kept)) s.downloadsRemoved(item.title) else s.downloadsInUse)
                    }
                    close()
                }
            },
        )
    }
}

/**
 * The desktop's [com.tmplayer.ui.components.SheetHost]: what a phone shows in its bottom sheet (a
 * video's copies, with the picture over the list) as a card in the middle of the window, over the
 * window's dim, as wide as a reading column and no taller than its content or most of the window.
 * Esc or a click outside closes it.
 */
val DesktopSheetHost = com.tmplayer.ui.components.SheetHost { onDismiss, _, content -> DesktopSheet(onDismiss, content) }

@Composable
internal fun DesktopSheet(onDismiss: () -> Unit, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val tall = maxHeight * 0.85f
            Column(
                Modifier
                    .widthIn(max = SHEET_WIDTH)
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .heightIn(max = tall)
                    .clip(com.tmplayer.ui.theme.Floating.DialogShape)
                    .background(FloatingTone.sheet)
                    .border(floatingBorder(), com.tmplayer.ui.theme.Floating.DialogShape),
                content = content,
            )
        }
    }
}

private val SHEET_WIDTH = 600.dp

/**
 * Closes the docked detail pane on a click that lands on an empty part of the page. A click a
 * card or a button takes is consumed by it and leaves the pane alone (a card swaps what it
 * shows), and so does a drag or a scroll.
 */
internal fun Modifier.closeDetailOnEmptyClick(state: ShellState, docked: Boolean): Modifier =
    if (!docked) this else pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val shown = state.detail ?: return@awaitEachGesture
            val up = waitForUpOrCancellation(pass = PointerEventPass.Final) ?: return@awaitEachGesture
            if ((up.position - down.position).getDistance() > viewConfiguration.touchSlop) return@awaitEachGesture
            if (state.detail === shown) state.closeDetail()
        }
    }
