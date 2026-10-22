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
import androidx.compose.ui.input.pointer.isShiftPressed
import com.tmplayer.data.ChatRepository
import com.tmplayer.data.EpisodeNeighbours
import com.tmplayer.data.MediaName
import com.tmplayer.data.Td
import com.tmplayer.data.TrackChoice
import com.tmplayer.i18n.Translator
import com.tmplayer.player.PlaybackSpeed
import com.tmplayer.player.SpeedMemory
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.SyncDelays
import com.tmplayer.player.TouchPrefs
import com.tmplayer.player.TrackPreferences
import com.tmplayer.player.TrackRules
import com.tmplayer.player.VideoScale
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
 * seek, pick tracks, resume where the video was left, play on to the next episode, with Settings'
 * playback, subtitle and mouse choices read as the desktop reads them. Bookmarks, the sleep timer
 * and the rest of the desktop's player come after the beta (docs/WEB.md).
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
    // The video on screen: the one asked for, then the next episode when one plays on from it.
    var item by remember(request) { mutableStateOf(request.item) }
    val engine = remember(item) { WebPlaybackEngine() }
    val seriesKey = remember(item) { (item.fileName.ifBlank { item.title }).let { MediaName.parse(it).title.ifBlank { it } } }
    val speedScopes = remember(item, seriesKey) { SpeedMemory.scopesOf(item, seriesKey) }
    val touchPrefs by settings.touchPrefs.collectAsState(initial = TouchPrefs())
    val desktopPrefs by prefs.state.collectAsState()
    var episodes by remember(item) { mutableStateOf(Episodes(current = EpisodeNeighbours.tagOf(item))) }
    var autoplayNext by remember { mutableStateOf(true) }
    // The end of a video: the next episode counting down, or the end card.
    var phase by remember(item) { mutableStateOf<Phase>(Phase.Playing) }
    var speedBeforeHold by remember(item) { mutableStateOf<Float?>(null) }
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
    // The first frame is in: the video opened and either plays or was held paused. Latched, so a
    // later stall shows the buffering spinner and never the opening card again.
    var started by remember(item) { mutableStateOf(false) }
    // Where the video opens, for the opening card's "Finding 13:54"; kept apart from the resume
    // notice, which can go while the card is still up.
    var openingStartMs by remember(item) { mutableStateOf(0L) }
    LaunchedEffect(item, status.opened, status.buffering, status.playing) {
        if (status.opened && (!status.buffering || !status.playing)) started = true
    }

    fun showFlash(kind: Flash.Kind, text: String) {
        flashes++
        flash = Flash(kind, text, flashes)
    }

    fun poke() {
        lastInput = com.tmplayer.platform.monotonicMillis()
        controlsUp = true
    }

    // For the video and engine that were playing, which a switch to the next episode has already
    // replaced by the time the old engine is let go.
    fun saveResume(leaving: Boolean, of: com.tmplayer.data.MediaItem? = null, from: WebPlaybackEngine? = null) {
        val item = of ?: item
        val st = (from ?: engine).state.value
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
        openingStartMs = start
        val desktop = prefs.now
        autoplayNext = runCatching { settings.autoplayNextNow() }.getOrDefault(true)
        showRemaining = runCatching { settings.touchPrefsNow().showRemaining }.getOrDefault(false)
        // Settings as the desktop's player opens with them: the file's saved tracks, then the
        // series', then Settings' languages; the remembered speed, picture shape, sound and
        // subtitle look, and this file's own subtitle offset.
        val resumed = if (start > 0) runCatching { settings.resumeRecord(item.chatId, item.messageId)?.state }.getOrNull() else null
        val trackPlan = TrackRules.plan(
            resumed,
            runCatching { settings.trackChoice(seriesKey) }.getOrDefault(TrackChoice()),
            runCatching { settings.trackPreferencesNow() }.getOrDefault(TrackPreferences()),
            Translator.messages.tag,
        )
        engine.open(
            WebMedia(item),
            start,
            OpenPrefs(
                audioLanguage = trackPlan.audioLanguage,
                subtitles = trackPlan.subtitles,
                speed = runCatching { settings.openingSpeed(resumed?.speed, speedScopes) }
                    .getOrDefault(resumed?.speed ?: PlaybackSpeed.DEFAULT)
                    .coerceIn(SeekMath.MIN_SPEED, SeekMath.MAX_SPEED),
                scale = runCatching { VideoScale.from(settings.videoScaleNow()) }.getOrDefault(VideoScale.Fit),
                volume = desktop.volume,
                muted = desktop.muted,
                volumeBoost = runCatching { settings.volumeBoostNow() }.getOrDefault(false),
                nightMode = runCatching { settings.nightModeNow() }.getOrDefault(false),
                subtitleStyle = runCatching { settings.subtitleStyleNow() }.getOrDefault(SubtitleStyle()),
                delays = runCatching { settings.syncDelays(item.chatId, item.messageId) }.getOrDefault(SyncDelays()),
                resume = resumed,
            ),
        )
        focus.requestFocus()
    }
    // The episodes either side, for the bar's buttons and Autoplay next: the chat searched for the
    // show's name, as the desktop's TelegramPlayerMedia.episodes does.
    LaunchedEffect(item) {
        val playing = item
        com.tmplayer.data.PlayQueue.stepsFor(playing)?.let {
            episodes = it
            return@LaunchedEffect
        }
        val show = EpisodeNeighbours.showOf(playing) ?: return@LaunchedEffect
        if (playing.chatId == 0L) return@LaunchedEffect
        val found = runCatching {
            val session = Td.awaitAuthorizedSession()
            val repository = ChatRepository(session.client)
            val topic = playing.topicId.takeIf { it != 0 }
                ?: runCatching { repository.topicOf(playing.chatId, playing.messageId) }.getOrDefault(0)
            EpisodeNeighbours.lookUp(playing, show) { query, cursors ->
                runCatching { repository.mediaPage(playing.chatId, cursors, query = query, topicId = topic) }.getOrNull()
            }
        }.getOrNull()
        if (found != null) episodes = found
    }
    // Subtitle style changed in Settings or the menu reaches the video playing.
    LaunchedEffect(engine) {
        settings.subtitleStyle.collect { style ->
            if (style != engine.state.value.subtitleStyle) engine.setSubtitleStyle(style)
        }
    }
    DisposableEffect(engine) {
        val playing = item
        val leaving = engine
        onDispose {
            saveResume(leaving = true, of = playing, from = leaving)
            leaving.close()
        }
    }
    val fullscreenNow by androidx.compose.runtime.rememberUpdatedState(fullscreen)
    DisposableEffect(Unit) { onDispose { if (fullscreenNow) videoFullscreen(false) } }

    // The end: the next episode after a short count when Autoplay next is on, else the end card.
    LaunchedEffect(engine, status.ended) {
        if (!status.ended || failure != null) return@LaunchedEffect
        val next = episodes.next
        if (next == null || !autoplayNext) {
            phase = Phase.Finished
            return@LaunchedEffect
        }
        for (second in AUTOPLAY_COUNTDOWN_SEC downTo 1) {
            phase = Phase.Countdown(next, second)
            delay(1_000)
            // Cancel or Play now answered it.
            if (phase != Phase.Countdown(next, second)) return@LaunchedEffect
        }
        item = next
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
        // Settings, Playback: how long they stay; "Never" keeps them up.
        val after = touchPrefs.controlsTimeoutMs
        if (after <= 0) return@LaunchedEffect
        delay(after)
        controlsUp = false
    }
    LaunchedEffect(fullscreen) { videoFullscreen(fullscreen) }
    // In full screen the pointer goes with the controls while a video plays, as on the desktop.
    val pointerHidden = fullscreen && started && status.playing && !controlsUp && menu == null
    LaunchedEffect(pointerHidden) { hidePointer(pointerHidden) }
    DisposableEffect(Unit) { onDispose { hidePointer(false) } }

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
        // For the series, the chat or the topic, as the desktop keeps it: 1x forgets it.
        val scopes = speedScopes
        Background.scope.launch { runCatching { settings.rememberSpeed(scopes, speed) } }
    }

    fun setScale(scale: VideoScale) {
        engine.setScale(scale)
        showFlash(Flash.Kind.Text, scale.label)
        Background.scope.launch { runCatching { settings.setVideoScale(scale.name) } }
    }

    /** Volume boost and Night mode from the menu: on the sound playing, and kept for every video. */
    fun toggleVolumeBoost() {
        val on = !status.volumeBoost
        engine.setVolumeBoost(on)
        showFlash(Flash.Kind.Text, if (on) s.playerVolumeBoostOn else s.playerVolumeBoostOff)
        Background.scope.launch { runCatching { settings.setVolumeBoost(on) } }
    }

    fun toggleNightMode() {
        val on = !status.nightMode
        engine.setNightMode(on)
        showFlash(Flash.Kind.Text, if (on) s.playerNightModeOn else s.playerNightModeOff)
        Background.scope.launch { runCatching { settings.setNightMode(on) } }
    }

    fun switchTo(next: com.tmplayer.data.MediaItem) {
        if (next.id == item.id) return
        phase = Phase.Playing
        item = next
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
            PlayerAction.CycleShape -> setScale(status.scale.next())
            PlayerAction.Stats -> showDetails = !showDetails
            PlayerAction.ShortcutSheet -> showShortcuts = !showShortcuts
            PlayerAction.Back, PlayerAction.Quit -> onClose()
            PlayerAction.MiniPlayer -> if (engine.canPip()) engine.pip()
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
                .onPointerEvent(PointerEventType.Press, PointerEventPass.Initial) { poke() }
                // The pointer leaving the page takes the controls down at once, as on the desktop.
                .onPointerEvent(PointerEventType.Exit, PointerEventPass.Initial) {
                    if (status.playing && menu == null) controlsUp = false
                }
                // The wheel: volume, or a seek with Settings, Mouse's switch (Shift the other).
                .onPointerEvent(PointerEventType.Scroll) { event ->
                    val delta = event.changes.firstOrNull()?.scrollDelta ?: return@onPointerEvent
                    SeekMath.wheelAction(delta.x, delta.y, event.keyboardModifiers.isShiftPressed, desktopPrefs.wheelSeeks)?.let {
                        poke()
                        dispatch(it)
                    }
                },
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
                // Holding the button plays fast at Settings, Mouse's speed, and lets go back.
                onHold = { on ->
                    val hold = touchPrefs.holdSpeed
                    if (on && hold > 0f && status.playing && speedBeforeHold == null) {
                        speedBeforeHold = status.speed
                        engine.setSpeed(hold)
                        showFlash(Flash.Kind.Text, "${TouchPrefs.holdLabel(hold)}  ▶▶")
                    } else if (!on) {
                        speedBeforeHold?.let { engine.setSpeed(it) }
                        speedBeforeHold = null
                    }
                },
            )
            val title = remember(item) { com.tmplayer.data.SeriesShelf.episodeOf(item).title.ifBlank { item.title } }
            val episode = remember(item) { EpisodeNeighbours.tagOf(item) }
            val subtitle = listOfNotNull(episode?.label, request.chatTitle.takeIf { it.isNotBlank() }).joinToString("  ·  ")
            PlayerOverlay(
                visible = controlsUp || !status.playing || menu != null,
                status = status,
                title = title,
                subtitle = subtitle,
                episodes = episodes,
                tracks = tracks,
                downloaded = null,
                showRemaining = showRemaining,
                fullscreen = fullscreen,
                menu = menu,
                ignoreClicks = false,
                miniPlayerAvailable = started && engine.canPip(),
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
                onEpisode = ::switchTo,
                onToggleRemaining = {
                    showRemaining = !showRemaining
                    val value = showRemaining
                    Background.scope.launch { runCatching { settings.updateTouchPrefs { it.copy(showRemaining = value) } } }
                },
                onVolume = ::setVolume,
                onToggleMute = ::toggleMute,
                onToggleFullscreen = onToggleFullscreen,
                onMiniPlayer = { dispatch(PlayerAction.MiniPlayer) },
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
                        is MenuAction.Shape -> setScale(action.scale)
                        MenuAction.ToggleVolumeBoost -> toggleVolumeBoost()
                        MenuAction.ToggleNightMode -> toggleNightMode()
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
            // One loader at a time: the opening card until the first frame, then the centre button
            // turns into the spinner while the controls are up, and this one stands in for it only
            // while they are down, as on the desktop.
            val controlsShown = controlsUp || !status.playing || menu != null
            FeedbackLayer(flash, spinner = started && failure == null && status.buffering && !controlsShown, chip = null)
            // The opening card, the shared PlayerLoader the desktop and the phone draw, over the
            // page's video (under the canvas) until the video has a frame to show.
            LoaderSheet(
                phase = if (!started && failure == null) Phase.Loading(s.playerOpeningVideo) else Phase.Playing,
                item = item,
                downloaded = null,
                bufferedMs = { engine.state.value.let { (it.bufferedMs - it.positionMs).coerceAtLeast(0) } },
                isDownload = false,
                onBack = onClose,
                onRetry = engine::retry,
                startMs = openingStartMs,
                // What the page measures: its reader's bytes, the video opened and seeking, and
                // the buffer ahead of the start point.
                signals = {
                    val st = engine.state.value
                    val start = openingStartMs
                    com.tmplayer.player.OpeningProgress.Signals(
                        readBytes = engine.readBytes,
                        opened = st.opened,
                        seekDone = if (start > 0) st.opened && !st.seekPending && st.bufferedMs > st.positionMs else null,
                        bufferedMs = if (st.opened) (st.bufferedMs - st.positionMs).coerceAtLeast(0) else -1L,
                        bufferTargetMs = WEB_START_BUFFER_MS,
                        bytesPerSec = if (item.durationSec > 0) item.sizeBytes / item.durationSec else 0L,
                    )
                },
            )
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
            // The next episode's count, or the end card, as the desktop's player draws them.
            if (failure == null) {
                StatusSheet(
                    phase = phase,
                    title = title,
                    subtitle = subtitle,
                    onRetry = engine::retry,
                    onBack = onClose,
                    onWatchAgain = {
                        phase = Phase.Playing
                        engine.seekTo(0)
                        engine.play()
                    },
                    onPlayNext = ::switchTo,
                    onCancelNext = { phase = Phase.Finished },
                    nextLabel = { episodes.labelFor(it) },
                )
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
private const val AUTOPLAY_COUNTDOWN_SEC = 5
private const val SUBTITLE_STEP_MS = 100L

/** What the page's video wants ahead of the start point before it plays, as the card's buffer. */
private const val WEB_START_BUFFER_MS = 1_000L

/**
 * Hides the pointer over the page or gives it back: a class on Compose's canvas, which sits in a
 * shadow root of the app's element, and a style added there once that sets it to no cursor at
 * all. Compose for Web sets the cursor only to its own named ones, none of them blank.
 */
@JsFun(
    """(hidden) => {
        const app = document.getElementById('app');
        if (!app) return;
        const roots = [document];
        for (const el of app.querySelectorAll('*')) if (el.shadowRoot) roots.push(el.shadowRoot);
        for (const root of roots) {
            const canvas = root.querySelector('canvas');
            if (!canvas) continue;
            if (root !== document && !root.getElementById('tm-no-pointer')) {
                const style = document.createElement('style');
                style.id = 'tm-no-pointer';
                style.textContent = 'canvas.tm-no-pointer { cursor: none !important; }';
                root.appendChild(style);
            }
            canvas.classList.toggle('tm-no-pointer', hidden);
            canvas.style.cursor = hidden ? 'none' : canvas.style.cursor === 'none' ? '' : canvas.style.cursor;
        }
    }""",
)
private external fun hidePointer(hidden: Boolean)
