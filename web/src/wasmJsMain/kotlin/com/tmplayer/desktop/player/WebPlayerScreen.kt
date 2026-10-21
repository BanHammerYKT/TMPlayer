package com.tmplayer.desktop.player

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.tmplayer.data.EpisodeNeighbours
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.ResumeRules
import com.tmplayer.data.ResumeState
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.desktop.os.OsInfo
import com.tmplayer.desktop.ui.PlayRequest
import com.tmplayer.platform.Background
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.web.player.WebMedia
import com.tmplayer.web.player.WebPlaybackEngine
import com.tmplayer.web.player.videoFullscreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The player in the browser, drawn with the desktop's own pieces: [VideoGestures] over the
 * picture, [PlayerOverlay] with its timebar, play and pause, the seek buttons, volume and the
 * audio and subtitle pickers, [PlayerMenu] behind them, [FeedbackLayer] for the seek flashes and
 * the spinner, [ResumeNotice] after a resume, and [PlayerKeys] for the keyboard. The desktop's
 * PlayerScreen.kt around them leans on mpv, the cast client and the file system, so this is the
 * browser's smaller version of it, over [WebPlaybackEngine], for the beta's basic path: play,
 * seek, pick tracks, resume where the video was left. Episodes, bookmarks, the sleep timer and the
 * rest of the desktop's player come after the beta (docs/WEB.md).
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun WebPlayerScreen(
    request: PlayRequest,
    settings: SettingsStore,
    prefs: DesktopPrefs,
    watched: WatchedStore?,
    fullscreen: Boolean,
    onToggleFullscreen: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val item = request.item
    val engine = remember(item) { WebPlaybackEngine() }
    val status by engine.state.collectAsState()
    val tracks by engine.tracks.collectAsState()
    val failure by engine.failure.collectAsState()
    val scope = rememberCoroutineScope()
    val focus = remember { FocusRequester() }
    var menu by remember { mutableStateOf<MenuAt?>(null) }
    var controlsUp by remember { mutableStateOf(true) }
    var overControls by remember { mutableStateOf(false) }
    var lastInput by remember { mutableStateOf(0L) }
    var flash by remember { mutableStateOf<Flash?>(null) }
    var flashes by remember { mutableStateOf(0L) }
    var showRemaining by remember { mutableStateOf(false) }
    var resumedFrom by remember { mutableStateOf<Long?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    var showShortcuts by remember { mutableStateOf(false) }

    fun showFlash(kind: Flash.Kind, text: String) {
        flashes++
        flash = Flash(kind, text, flashes)
    }

    fun poke() {
        lastInput = com.tmplayer.platform.monotonicMillis()
        controlsUp = true
    }

    fun saveResume(leaving: Boolean) {
        val st = engine.state.value
        if (!st.opened || st.durationMs <= 0) return
        val position = st.positionMs
        val duration = st.durationMs
        val done = ResumeRules.watched(position, duration)
        val description = ResumeRecord.encode(
            fileId = item.fileId,
            title = item.title,
            chatTitle = request.chatTitle,
            sizeBytes = item.sizeBytes,
            durationSec = item.durationSec.takeIf { it > 0 } ?: (duration / 1000).toInt(),
            updatedAt = com.tmplayer.platform.nowMillis(),
            state = ResumeState(speed = st.speed),
            fileName = item.fileName,
            caption = item.caption,
        )
        Background.scope.launch {
            runCatching {
                if (done) {
                    settings.clearResumePosition(item.chatId, item.messageId, item)
                    if (leaving && watched != null) {
                        com.tmplayer.desktop.setWatched(watched, settings, item, request.chatTitle, watched = true)
                    }
                } else {
                    settings.saveResumePosition(item.chatId, item.messageId, position, duration, description)
                }
            }
        }
    }

    // Opening: the saved place unless Start over, a few seconds early as Settings has it.
    LaunchedEffect(item) {
        val start = if (request.startFromBeginning) {
            runCatching { settings.clearResumePosition(item.chatId, item.messageId, item) }
            0L
        } else {
            runCatching { settings.resumeStartFor(item) }.getOrDefault(0L)
        }
        if (start > 0) resumedFrom = start
        val desktop = prefs.now
        engine.open(
            WebMedia(item),
            start,
            OpenPrefs(volume = desktop.volume, muted = desktop.muted),
        )
        focus.requestFocus()
    }
    DisposableEffect(engine) {
        onDispose {
            saveResume(leaving = true)
            engine.close()
            if (fullscreen) videoFullscreen(false)
        }
    }
    // The place saved as it plays, so a closed tab or a crash loses a few seconds at most.
    LaunchedEffect(engine) {
        while (true) {
            delay(RESUME_TICK_MS)
            if (engine.state.value.playing) saveResume(leaving = false)
        }
    }
    // The controls go after a few seconds of playing without input, as on the desktop.
    LaunchedEffect(status.playing, lastInput, menu, overControls) {
        if (!status.playing || menu != null || overControls) {
            controlsUp = true
            return@LaunchedEffect
        }
        delay(CONTROLS_IDLE_MS)
        controlsUp = false
    }
    LaunchedEffect(fullscreen) { videoFullscreen(fullscreen) }

    fun togglePlay() {
        engine.togglePlay()
        poke()
    }

    fun seekBy(delta: Long) {
        engine.seekBy(delta)
        showFlash(if (delta < 0) Flash.Kind.SeekBack else Flash.Kind.SeekForward, SeekMath.clock(kotlin.math.abs(delta)))
        poke()
    }

    fun setVolume(v: Int) {
        engine.setVolume(v)
        if (status.muted && v > 0) engine.setMuted(false)
        prefs.update { it.copy(volume = v, muted = false) }
        showFlash(Flash.Kind.Volume, "$v%")
    }

    fun toggleMute() {
        val on = !status.muted
        engine.setMuted(on)
        prefs.update { it.copy(muted = on) }
        showFlash(Flash.Kind.Volume, if (on) s.playerMuted else "${status.volume}%")
    }

    fun cycleTrack(type: TrackType, forward: Boolean) {
        val next = tracks.cycle(type, forward)
        engine.selectTrack(type, next?.id)
        showFlash(Flash.Kind.Text, next?.label ?: s.playerSubtitlesOff)
    }

    fun setSpeed(speed: Float) {
        engine.setSpeed(speed)
        showFlash(Flash.Kind.Text, SeekMath.speedLabel(speed))
    }

    fun dispatch(action: PlayerAction) {
        when (action) {
            PlayerAction.TogglePlay -> togglePlay()
            PlayerAction.Play -> engine.play()
            PlayerAction.Pause -> engine.pause()
            is PlayerAction.SeekBy -> seekBy(action.deltaMs)
            is PlayerAction.JumpToTenth -> SeekMath.tenth(status.durationMs, action.tenth)?.let { engine.seekTo(it) }
            PlayerAction.JumpToEnd -> if (status.durationMs > 0) engine.seekTo(status.durationMs)
            is PlayerAction.FrameStep -> if (!status.playing) engine.frameStep(action.forward)
            is PlayerAction.VolumeBy -> setVolume(SeekMath.volume(status.volume, action.delta))
            PlayerAction.ToggleMute -> toggleMute()
            PlayerAction.ToggleFullscreen -> onToggleFullscreen()
            PlayerAction.ExitFullscreen -> if (fullscreen) onToggleFullscreen()
            PlayerAction.SubtitleNext -> cycleTrack(TrackType.Subtitle, true)
            PlayerAction.SubtitlePrevious -> cycleTrack(TrackType.Subtitle, false)
            PlayerAction.SubtitleToggle -> cycleTrack(TrackType.Subtitle, true)
            PlayerAction.AudioNext -> cycleTrack(TrackType.Audio, true)
            PlayerAction.AudioPrevious -> cycleTrack(TrackType.Audio, false)
            PlayerAction.SpeedUp -> setSpeed(SeekMath.fineSpeed(status.speed, up = true))
            PlayerAction.SpeedDown -> setSpeed(SeekMath.fineSpeed(status.speed, up = false))
            PlayerAction.SpeedReset -> setSpeed(1f)
            PlayerAction.CycleShape -> engine.setScale(status.scale.next())
            PlayerAction.Stats -> showDetails = !showDetails
            PlayerAction.ShortcutSheet -> showShortcuts = !showShortcuts
            PlayerAction.Back, PlayerAction.Quit -> onClose()
            else -> Unit
        }
    }

    PlayerTheme {
        Box(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    poke()
                    if (event.key == androidx.compose.ui.input.key.Key.Escape && (showShortcuts || showDetails || menu != null)) {
                        showShortcuts = false
                        showDetails = false
                        menu = null
                        return@onPreviewKeyEvent true
                    }
                    val press = KeyPress(
                        key = event.key,
                        shift = event.isShiftPressed,
                        ctrl = event.isCtrlPressed,
                        alt = event.isAltPressed,
                        meta = event.isMetaPressed,
                    )
                    val context = KeyContext(fullscreen = fullscreen, speedIsNormal = status.speed == 1f, mac = OsInfo.isMac)
                    val action = PlayerKeys.actionFor(press, context) ?: return@onPreviewKeyEvent false
                    dispatch(action)
                    true
                }
                .focusRequester(focus)
                .focusable()
                .onPointerEvent(PointerEventType.Move, PointerEventPass.Initial) { poke() }
                .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { poke() },
        ) {
            VideoGestures(
                onClick = {
                    togglePlay()
                    focus.requestFocus()
                },
                onDoubleClick = onToggleFullscreen,
                onRightClick = { at -> menu = MenuAt(MenuPage.Main, MenuAt.Anchor.Cursor, at) },
                onMouseBack = onClose,
                scope = scope,
            )
            val title = remember(item) { com.tmplayer.data.SeriesShelf.episodeOf(item).title.ifBlank { item.title } }
            val episode = remember(item) { EpisodeNeighbours.tagOf(item) }
            val subtitle = listOfNotNull(episode?.label, request.chatTitle.takeIf { it.isNotBlank() }).joinToString("  ·  ")
            PlayerOverlay(
                visible = controlsUp || !status.playing || menu != null,
                status = status,
                title = title,
                subtitle = subtitle,
                episodes = Episodes(current = episode),
                tracks = tracks,
                downloaded = null,
                showRemaining = showRemaining,
                fullscreen = fullscreen,
                menu = menu,
                ignoreClicks = false,
                miniPlayerAvailable = false,
                alwaysOnTopAvailable = false,
                fromTelegram = true,
                savable = false,
                watchedLabel = null,
                onHoverControls = { overControls = it },
                onBack = onClose,
                onTogglePlay = ::togglePlay,
                onSeekBy = ::seekBy,
                onSeekTo = {
                    engine.seekTo(it)
                    poke()
                },
                onEpisode = {},
                onToggleRemaining = { showRemaining = !showRemaining },
                onVolume = ::setVolume,
                onToggleMute = ::toggleMute,
                onToggleFullscreen = onToggleFullscreen,
                onMiniPlayer = {},
                onOpenMenu = { menu = it },
                onCloseMenu = {
                    menu = null
                    focus.requestFocus()
                },
                onMenuAction = { action ->
                    poke()
                    when (action) {
                        is MenuAction.Do -> dispatch(action.action)
                        is MenuAction.Track -> {
                            engine.selectTrack(action.type, action.track?.id)
                            if (action.type == TrackType.Subtitle && action.track == null) showFlash(Flash.Kind.Text, s.playerSubtitlesOff)
                        }
                        is MenuAction.Speed -> setSpeed(action.speed)
                        is MenuAction.Shape -> engine.setScale(action.scale)
                        MenuAction.ToggleVolumeBoost -> engine.setVolumeBoost(!status.volumeBoost)
                        MenuAction.ToggleNightMode -> engine.setNightMode(!status.nightMode)
                        is MenuAction.SubtitleDelay -> engine.setSubtitleDelay(
                            if (action.direction == 0) 0L else status.subtitleDelayMs + action.direction * SUBTITLE_STEP_MS,
                        )
                        MenuAction.StartOver -> engine.seekTo(0)
                        MenuAction.Details -> showDetails = true
                        MenuAction.Shortcuts -> showShortcuts = true
                        MenuAction.OpenElsewhere, MenuAction.Download -> OpenExternal.browse(GET_APP)
                        else -> Unit
                    }
                },
                subtitlesButton = true,
            )
            // One loader at a time: the centre button turns into the spinner while the controls are
            // up, and this one stands in for it only while they are down, as on the desktop.
            val controlsShown = controlsUp || !status.playing || menu != null
            FeedbackLayer(flash, spinner = failure == null && (status.buffering || !status.opened) && !controlsShown, chip = null)
            resumedFrom?.let { from ->
                if (status.opened) {
                    ResumeNotice(
                        from = from,
                        lifted = controlsUp,
                        onStartOver = {
                            engine.seekTo(0)
                            resumedFrom = null
                        },
                        onTimeout = { resumedFrom = null },
                    )
                }
            }
            if (showDetails) DetailsPanel(rows = { engine.details() }, onClose = { showDetails = false })
            if (showShortcuts) ShortcutSheet(onClose = { showShortcuts = false }, mac = OsInfo.isMac)
            failure?.let { kind -> FailureSheet(kind, onRetry = engine::retry, onClose = onClose) }
        }
    }
}

/** What the page says when no engine can play the file, or the bytes stopped coming. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.FailureSheet(kind: String, onRetry: () -> Unit, onClose: () -> Unit) {
    val s = LocalStrings.current
    Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 460.dp).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val stopped = kind == "stopped"
            Text(
                if (stopped) s.playerStalledMidTitle else s.webNeedsAppTitle,
                color = Color.White,
                fontSize = 20.sp,
                textAlign = TextAlign.Center,
            )
            Text(
                when (kind) {
                    "stopped" -> s.webStoppedLoading
                    "heavy" -> s.webNeedsAppTooHeavy
                    else -> s.webNeedsAppCodec
                },
                color = Color.White.copy(alpha = 0.8f),
                textAlign = TextAlign.Center,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!stopped) Button(onClick = { OpenExternal.browse(GET_APP) }) { Text(s.webNeedsAppGet) }
                OutlinedButton(onClick = onRetry) { Text(s.commonTryAgain, color = Color.White) }
                OutlinedButton(onClick = onClose) { Text(s.commonBack, color = Color.White) }
            }
        }
    }
}

private const val GET_APP = "https://tmplayer.org/download/"
private const val RESUME_TICK_MS = 5_000L
private const val CONTROLS_IDLE_MS = 3_000L
private const val SUBTITLE_STEP_MS = 100L

