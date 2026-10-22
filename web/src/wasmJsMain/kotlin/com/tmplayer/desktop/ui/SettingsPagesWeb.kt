// The rows of Settings' pages in the browser, drawn with the desktop's own row pieces
// (SettingsRows.kt) in the order desktop/.../ui/SettingsPages.kt has them, so a setting reads and
// saves the same store on every platform. What a page cannot do is left out where it would be,
// with the reason beside it; the pages themselves are listed in com.tmplayer.web.Main
// (settingsCategories).
package com.tmplayer.desktop.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AppLockSession
import com.tmplayer.data.AppLockSettings
import com.tmplayer.data.BackupRead
import com.tmplayer.data.BackupService
import com.tmplayer.data.BackupWords
import com.tmplayer.data.Feedback
import com.tmplayer.data.LogExport
import com.tmplayer.data.RestorePlan
import com.tmplayer.data.ResumeRewind
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.SizeFilter
import com.tmplayer.data.SupportReminder
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.UiScale
import com.tmplayer.data.WhatsNew
import com.tmplayer.desktop.os.DesktopLog
import com.tmplayer.desktop.os.OpenExternal
import com.tmplayer.i18n.Translator
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.player.SubtitleStyle
import com.tmplayer.player.TouchPrefs
import com.tmplayer.player.TrackPreferences
import com.tmplayer.ui.about.About
import com.tmplayer.ui.components.JumpTarget
import com.tmplayer.ui.components.TmAlertDialog
import com.tmplayer.ui.components.jumpTarget
import com.tmplayer.ui.components.rememberToast
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.i18n.languageRowDetail
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.lock.AppLockWords
import com.tmplayer.ui.onboarding.LanguageList
import com.tmplayer.ui.onboarding.SetupStep
import com.tmplayer.ui.theme.Corner
import com.tmplayer.ui.theme.Tone
import com.tmplayer.ui.theme.rememberUiScale
import com.tmplayer.web.js.TmHost
import kotlinx.coroutines.await
import kotlinx.coroutines.launch

/**
 * Appearance and language: the desktop's rows but the window's own title bar, which a page does
 * not have (the browser draws its own).
 */
@Composable
internal fun AppearanceRows(state: ShellState) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val theme by settings.themeChoice.collectAsState(initial = ThemeChoice.Default)
    val pureBlack by settings.pureBlack.collectAsState(initial = false)
    val accent by settings.accent.collectAsState(initial = com.tmplayer.data.Accent.Default)
    val customAccent by settings.customAccent.collectAsState(initial = null)
    val typeface by settings.typeface.collectAsState(initial = com.tmplayer.data.AppTypeface.Default)
    val highContrast by settings.highContrast.collectAsState(initial = false)
    var undo by remember { mutableStateOf<com.tmplayer.data.AppearanceSnapshot?>(null) }
    LaunchedEffect(undo) {
        if (undo != null) {
            kotlinx.coroutines.delay(10_000L)
            undo = null
        }
    }
    val language by settings.language.collectAsState(initial = "")
    val uiScale by rememberUiScale(settings)
    val compactNav by settings.compactNav.collectAsState(initial = false)
    var pickingLanguage by remember { mutableStateOf(false) }

    Setting(s.settingsAppearancePreview, "") {
        com.tmplayer.ui.components.AppearancePreview(Modifier.widthIn(max = 420.dp))
    }
    Setting(
        s.settingsTheme,
        if (theme == ThemeChoice.System) s.themeSystemDesktopDetail else theme.description,
        icon = TmIcons.Theme,
    ) {
        Segments(ThemeChoice.entries, theme, { it.label }) { scope.launch { settings.setThemeChoice(it) } }
    }
    com.tmplayer.ui.components.RevealUnder(theme.offersPureBlack, Modifier.layoutId(SettingsPart.Row)) {
        Toggle(s.settingsPureBlack, s.themeBlackDetail, pureBlack, icon = TmIcons.PureBlack) { on ->
            scope.launch { settings.setPureBlack(on) }
        }
    }
    Setting(s.settingsAccent, s.settingsAccentDetail, icon = TmIcons.Accent) {
        com.tmplayer.ui.components.AccentSwatches(
            accent,
            { picked -> scope.launch { settings.setAccent(picked) } },
            size = 26.dp,
            custom = customAccent,
            onCustom = { argb -> scope.launch { settings.setCustomAccent(argb) } },
        )
    }
    Setting(s.settingsTypeface, s.settingsTypefaceDetail, icon = TmIcons.TextSize) {
        Segments(com.tmplayer.data.AppTypeface.entries, typeface, { it.label }) { scope.launch { settings.setTypeface(it) } }
    }
    Toggle(s.settingsHighContrast, s.settingsHighContrastDetail, highContrast, icon = TmIcons.Theme) { on ->
        scope.launch { settings.setHighContrast(on) }
    }
    Action(s.settingsLanguage, s.languageRowDetail(language), TmIcons.Language) { pickingLanguage = true }
    Setting(s.interfaceSize, s.interfaceSizeDetail, icon = TmIcons.TextSize) {
        Segments(UiScale.entries, uiScale, { it.label }) { scope.launch { settings.setUiScale(it) } }
    }
    Toggle(
        s.settingsCompactNav,
        if (compactNav) s.settingsCompactNavOn("desktop") else s.settingsCompactNavOff("desktop"),
        compactNav,
        icon = TmIcons.Grid,
    ) { on -> scope.launch { settings.setCompactNav(on) } }
    val taken = undo
    Action(
        if (taken == null) s.settingsAppearanceDefaults else s.settingsAppearanceUndo,
        if (taken == null) s.settingsAppearanceDefaultsDetail else s.settingsAppearanceDefaultsDone,
        TmIcons.Refresh,
    ) {
        if (taken == null) {
            scope.launch { undo = settings.resetAppearance() }
        } else {
            undo = null
            scope.launch { settings.restoreAppearance(taken) }
        }
    }

    if (pickingLanguage) LanguagePopup(settings, onClose = { pickingLanguage = false })
}

/**
 * Playback, as the browser's player reads it (WebPlayerScreen): what plays next, a click's first
 * stop, the soundtrack's language, how long the controls stay and how far back a resume starts.
 * Left out: Download before playing (the browser keeps no downloads), Low storage mode (always on
 * here, said on the Storage page), Downmix and Software decoding (mpv's, while the browser picks
 * its own decoder and mixes the sound itself) and Even volume (the phone's leveller, which the
 * browser's player does not have; its menu has Night mode and Volume boost, kept for every video).
 */
@Composable
internal fun PlaybackRows(state: ShellState, jumps: Map<SetupStep, JumpTarget>) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val autoplay by settings.autoplayNext.collectAsState(initial = true)
    val detailFirst by settings.detailFirst.collectAsState(initial = true)
    val trackPrefs by settings.trackPreferences.collectAsState(initial = TrackPreferences())
    val touchPrefs by settings.touchPrefs.collectAsState(initial = TouchPrefs())
    val resumeRewindMs by settings.resumeRewindMs.collectAsState(initial = ResumeRewind.DEFAULT_MS)

    Toggle(s.settingsAutoplay, if (autoplay) s.settingsAutoplayOn else s.settingsAutoplayOff, autoplay, icon = TmIcons.Play) {
        scope.launch { settings.setAutoplayNext(it) }
    }
    Toggle(
        s.settingsDetailFirst,
        if (detailFirst) s.settingsDetailFirstClickOn else s.settingsDetailFirstClickOff,
        detailFirst,
        icon = TmIcons.Info,
    ) { on -> scope.launch { settings.setDetailFirst(on) } }
    AudioLanguageSetting(settings, trackPrefs, scope)
    run {
        val choices = TouchPrefs.TIMEOUT_CHOICES_MS
        Setting(
            s.settingsHideControls,
            s.settingsHideControlsDetail(TouchPrefs.timeoutLabel(touchPrefs.controlsTimeoutMs)),
            icon = TmIcons.VisibilityOff,
        ) {
            Stepper(
                onLess = { scope.launch { settings.updateTouchPrefs { it.copy(controlsTimeoutMs = TouchPrefs.step(choices, it.controlsTimeoutMs, -1)) } } },
                onMore = { scope.launch { settings.updateTouchPrefs { it.copy(controlsTimeoutMs = TouchPrefs.step(choices, it.controlsTimeoutMs, 1)) } } },
                canLess = touchPrefs.controlsTimeoutMs != choices.first(),
                canMore = touchPrefs.controlsTimeoutMs != choices.last(),
            )
        }
    }
    Setting(s.settingsResumeRewind, s.settingsResumeRewindDetail, icon = TmIcons.History) {
        Segments(ResumeRewind.CHOICES_MS, resumeRewindMs, { ResumeRewind.label(it) }) {
            scope.launch { settings.setResumeRewindMs(it) }
        }
    }
}

/**
 * Subtitles: how they look and in which language. Online subtitles are the apps' for now: their
 * client saves the subtitle files it fetches, which the browser build has nowhere to keep.
 */
@Composable
internal fun SubtitlesRows(state: ShellState) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val subtitleStyle by settings.subtitleStyle.collectAsState(initial = SubtitleStyle())
    val trackPrefs by settings.trackPreferences.collectAsState(initial = TrackPreferences())
    var styling by remember { mutableStateOf(false) }

    Action(s.settingsSubtitleStyle, subtitleStyle.summary(), TmIcons.Subtitles) { styling = true }
    SubtitleLanguageSettings(settings, trackPrefs, scope)
    LearntLanguagesSetting(settings, scope)

    if (styling) SubtitleStyleDialog(settings, subtitleStyle, onClose = { styling = false })
}

/** The mouse in the player: holding the button to play fast, and what the wheel does. */
@Composable
internal fun MouseRows(state: ShellState) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val touchPrefs by settings.touchPrefs.collectAsState(initial = TouchPrefs())
    val desktop by state.extras.prefs.state.collectAsState()

    Setting(s.settingsHoldSpeed, s.settingsHoldSpeedDetailDesktop, icon = SettingsGlyphs.Faster) {
        Segments(TouchPrefs.HOLD_CHOICES, touchPrefs.holdSpeed, { TouchPrefs.holdLabel(it) }) { choice ->
            scope.launch { settings.updateTouchPrefs { it.copy(holdSpeed = choice) } }
        }
    }
    Toggle(
        s.settingsWheelSeeks,
        if (desktop.wheelSeeks) s.settingsWheelSeeksOn else s.settingsWheelSeeksOff,
        desktop.wheelSeeks,
        icon = SettingsGlyphs.Mouse,
    ) { on -> state.extras.prefs.update { it.copy(wheelSeeks = on) } }
}

private class Confirm(val title: String, val message: String, val detail: String?, val action: () -> Unit)

/** The largest file Restore reads: no backup comes near it, and a video picked by mistake is not read. */
private const val BACKUP_MAX_BYTES = 32.0 * 1024 * 1024

/**
 * Library, the desktop's rows: which videos show, what opens first, the lists, hidden chats, the
 * backup (saved to the browser's downloads and read back from a picked file) and Posters and
 * details.
 */
@Composable
internal fun LibraryRows(state: ShellState, version: String, chatList: List<com.tmplayer.data.ChatSummary>, jumps: Map<SetupStep, JumpTarget>) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val minSize by settings.minSizeBytes.collectAsState(initial = SizeFilter.DEFAULT_MIN)
    val maxSize by settings.maxSizeBytes.collectAsState(initial = SizeFilter.DEFAULT_MAX)
    val openLast by settings.openLastChat.collectAsState(initial = false)
    val lastChatId by settings.lastChatId.collectAsState(initial = 0L)
    val rememberSearches by settings.rememberSearches.collectAsState(initial = true)
    val resumeSync by settings.resumeSync.collectAsState(initial = false)
    val history by settings.continueWatching.collectAsState(initial = emptyList())
    val storedFavourites by settings.favorites.collectAsState(initial = emptySet())
    val allowedChats by com.tmplayer.data.ChatGate.allowed.collectAsState()
    val favourites = remember(storedFavourites, allowedChats) { com.tmplayer.data.KidsLock.favourites(storedFavourites, allowedChats) }
    val watchedList by state.watched.history.collectAsState(initial = emptyList())
    val hiddenChats by settings.hiddenChats.collectAsState(initial = emptyList())
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    var confirmClearWatched by remember { mutableStateOf(false) }
    var restorePlan by remember { mutableStateOf<RestorePlan?>(null) }
    val backups = remember(settings, state.watched, version) {
        BackupService(settings, state.watched, OnlineMetadata.current?.store, version, bookmarks = state.bookmarks)
    }

    Setting(s.settingsSmallest, SizeFilter.label(minSize), icon = TmIcons.FilterHorizontal) {
        Stepper(
            onLess = { scope.launch { settings.setMinSizeBytes(SizeFilter.clampMin(SizeFilter.step(minSize, -1), maxSize)) } },
            onMore = { scope.launch { settings.setMinSizeBytes(SizeFilter.clampMin(SizeFilter.step(minSize, 1), maxSize)) } },
        )
    }
    Setting(s.settingsLargest, SizeFilter.label(maxSize), icon = TmIcons.FilterHorizontal) {
        Stepper(
            onLess = { scope.launch { settings.setMaxSizeBytes(SizeFilter.clampMax(SizeFilter.step(maxSize, -1), minSize)) } },
            onMore = { scope.launch { settings.setMaxSizeBytes(SizeFilter.clampMax(SizeFilter.step(maxSize, 1), minSize)) } },
        )
    }
    if (!SizeFilter.isDefault(minSize, maxSize)) {
        Action(
            s.settingsSizeLimitsReset,
            s.settingsSizeLimitsResetDetail(SizeFilter.describe(SizeFilter.DEFAULT_MIN, SizeFilter.DEFAULT_MAX)),
            TmIcons.Refresh,
            value = s.commonReset,
            end = RowEnd.None,
        ) {
            scope.launch {
                settings.setMaxSizeBytes(SizeFilter.DEFAULT_MAX)
                settings.setMinSizeBytes(SizeFilter.DEFAULT_MIN)
            }
        }
    }

    val lastChatTitle = remember(chatList, lastChatId) { chatList.firstOrNull { it.id == lastChatId }?.title }
    Toggle(
        s.settingsOpenLastChat,
        when {
            lastChatTitle != null -> s.settingsOpenLastChatNamed(lastChatTitle)
            lastChatId != 0L -> s.settingsOpenLastChatUnnamed
            else -> s.settingsOpenLastChatOff
        },
        openLast,
        icon = TmIcons.History,
    ) { scope.launch { settings.setOpenLastChat(it) } }
    if (openLast && lastChatId != 0L) {
        Action(s.settingsForgetLastChat, s.settingsForgetLastChatDetail, TmIcons.Close, end = RowEnd.None) {
            scope.launch {
                settings.forgetLastChat()
                toast(s.settingsForgotLastChat)
            }
        }
    }

    Group(s.settingsLists)
    Toggle(
        s.settingsRememberSearches,
        if (rememberSearches) s.settingsRememberSearchesOn else s.settingsRememberSearchesOff,
        rememberSearches,
        icon = TmIcons.Search,
    ) { on -> scope.launch { settings.setRememberSearches(on) } }
    Toggle(
        s.settingsResumeSync,
        if (resumeSync) s.settingsResumeSyncOn else s.settingsResumeSyncOff,
        resumeSync,
        icon = TmIcons.History,
    ) { on ->
        scope.launch {
            settings.setResumeSync(on)
            // The switch and its line say it is on; nothing more to say.
            if (on) com.tmplayer.data.ResumeSync.syncSoon(settings, afterMs = 0)
        }
    }
    if (hiddenChats.isEmpty()) {
        Setting(s.settingsHiddenChats, s.settingsHiddenChatsNone, icon = TmIcons.VisibilityOff) {}
    } else {
        Setting(s.settingsHiddenChats, s.settingsHiddenChatsNote, icon = TmIcons.VisibilityOff) {}
        hiddenChats.forEach { chat ->
            Action(chat.title.ifBlank { chat.id.toString() }, s.settingsHiddenChatOne, TmIcons.Chats, value = s.settingsHiddenChatsShow, end = RowEnd.None) {
                // The line leaving this list is the answer.
                scope.launch { settings.showChat(chat.id) }
            }
        }
        if (hiddenChats.size > 1) {
            Action(s.settingsHiddenChatsShowAll, s.settingsHiddenChatsDetail(hiddenChats.size), TmIcons.Checklist, end = RowEnd.None) {
                // The list emptying is the answer.
                scope.launch { settings.showAllChats() }
            }
        }
    }
    if (favourites.isNotEmpty()) Action(s.settingsClearFavourites, s.settingsClearFavouritesDetail(favourites.size), TmIcons.Close, end = RowEnd.None) {
        confirm = Confirm(
            title = s.settingsFavouritesClearTitle,
            message = s.settingsFavouritesClearMessage(favourites.size),
            detail = s.settingsFavouritesClearDetail,
        ) {
            scope.launch {
                val count = favourites.size
                settings.clearFavorites(allowedChats)
                toast(s.settingsFavouritesCleared(count))
            }
        }
    }
    if (history.isNotEmpty()) Action(s.settingsClearHistory, s.settingsClearHistoryDetail(history.size), TmIcons.Close, end = RowEnd.None) {
        confirm = Confirm(
            title = s.settingsContinueClearTitle,
            message = s.settingsContinueClearMessage,
            detail = s.settingsContinueClearDetail,
        ) {
            scope.launch {
                val count = history.size
                settings.clearWatchHistory()
                toast(s.settingsContinueCleared(count))
            }
        }
    }
    if (watchedList.isNotEmpty()) {
        Action(s.settingsClearWatched, s.settingsClearWatchedDetail(watchedList.size), TmIcons.Close, end = RowEnd.None) {
            confirmClearWatched = true
        }
    }

    // To a file and back: settings, lists and resume points, never the Telegram sign in. The page
    // hands the file to the browser's downloads, and reads one the viewer picks.
    Group(s.backupSection)
    Action(s.backupBackUp, s.backupBackUpDetail, TmIcons.Download) {
        scope.launch {
            val name = backups.fileName()
            val saved = runCatching { TmHost.saveText(name, backups.export(), "application/json") }.getOrDefault(false)
            toast(if (saved) s.backupSavedTo(name) else s.backupFailed)
        }
    }
    Action(s.backupRestore, s.backupRestoreDetail, TmIcons.Refresh) {
        // Asked for in the press itself: a browser opens its file picker only from one.
        val picked = TmHost.pickText(".json,application/json", BACKUP_MAX_BYTES)
        scope.launch {
            val text = runCatching { picked.await<kotlin.js.JsString>().toString() }.getOrDefault("")
            if (text.isEmpty()) return@launch
            when (val read = backups.read(text)) {
                is BackupRead.Ok -> {
                    val plan = backups.plan(read.backup)
                    if (plan.nothing) toast(s.backupRestoreNothing) else restorePlan = plan
                }
                else -> BackupWords.refusal(read)?.let { toast(it) }
            }
        }
    }

    OnlineMetadataGroup()

    if (confirmClearWatched) {
        ClearWatchedDialog(state, watchedList.size, scope, onDismiss = { confirmClearWatched = false })
    }
    restorePlan?.let { plan ->
        ConfirmDialog(
            title = s.backupRestoreTitle,
            message = BackupWords.restoreMessage(plan),
            detail = s.backupRestoreUntouched,
            confirmLabel = s.backupRestoreConfirm,
            destructive = false,
            onConfirm = {
                restorePlan = null
                scope.launch {
                    val done = runCatching { backups.restore(plan.backup) }.isSuccess
                    toast(if (done) s.backupRestored else s.backupReadFailed)
                }
            },
            onDismiss = { restorePlan = null },
        )
    }
    confirm?.let { asked ->
        ConfirmDialog(
            title = asked.title,
            message = asked.message,
            detail = asked.detail,
            confirmLabel = s.commonClear,
            onConfirm = {
                confirm = null
                asked.action()
            },
            onDismiss = { confirm = null },
        )
    }
}

/**
 * Notifications, as far as a browser tab can give them: the browser's own permission first, then
 * the switches of the checks the page runs while it is open (DesktopChecks in DesktopUiWeb.kt).
 * Left out: finished downloads, low storage and updates, which a page does not have.
 */
@Composable
internal fun NotificationRows(state: ShellState) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    var permission by remember { mutableStateOf(runCatching { TmHost.notifyPermission() }.getOrDefault("unsupported")) }
    // A choice made in the browser's own site settings shows here while the page is open.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_500)
            permission = runCatching { TmHost.notifyPermission() }.getOrDefault(permission)
        }
    }
    val supportCounters by settings.supportCounters.collectAsState(initial = SupportReminder.Counters())

    Action(
        s.webNotifyAllow,
        when (permission) {
            "granted" -> s.webNotifyAllowOn
            "denied" -> s.webNotifyAllowBlocked
            "unsupported" -> s.webNotifyAllowUnsupported
            else -> s.webNotifyAllowAsk
        },
        TmIcons.Bell,
        end = RowEnd.None,
        enabled = permission == "default",
    ) {
        // Asked for in the press itself, which browsers need before they show their prompt.
        val asked = TmHost.askNotify()
        scope.launch {
            permission = runCatching { asked.await<kotlin.js.JsString>().toString() }.getOrDefault("denied")
            if (permission == "denied") toast(s.webNotifyAllowBlocked)
        }
    }
    val rows = buildList {
        add(Triple(com.tmplayer.data.Notice.NewEpisodes, s.settingsAlertEpisodes, s.webNotifyEpisodesDetail))
        add(Triple(com.tmplayer.data.Notice.NewVideos, s.settingsNewVideos, s.webNotifyNewVideosDetail))
        add(Triple(com.tmplayer.data.Notice.MissingPieces, s.settingsAlertPieces, s.settingsAlertPiecesDetail))
        if (SupportReminder.enabled && !supportCounters.finished) {
            add(Triple(com.tmplayer.data.Notice.Support, s.settingsSponsorReminders, s.settingsSponsorRemindersDetail))
        }
    }
    for ((notice, title, detail) in rows) {
        val on by settings.noticeOn(notice).collectAsState(initial = notice.defaultOn)
        Toggle(title, detail, on, icon = TmIcons.Bell) { next ->
            scope.launch { settings.setNoticeOn(notice, next) }
            // Turning one on is the moment to ask the browser, as the phone asks Android.
            if (next && permission == "default") {
                val asked = TmHost.askNotify()
                scope.launch { permission = runCatching { asked.await<kotlin.js.JsString>().toString() }.getOrDefault(permission) }
            }
        }
    }
}

/** App lock, as on the desktop: the PIN and its switches live in the settings store. */
@Composable
internal fun LockRows(state: ShellState, onPin: (DesktopPinFlow) -> Unit, onKidsChats: () -> Unit) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val appLock by settings.appLock.collectAsState(initial = AppLockSettings())

    if (!appLock.pinSet) {
        Action(s.lockSetPin, s.lockSetPinDetail, TmIcons.Lock) { onPin(DesktopPinFlow.Set) }
    } else {
        Action(s.lockChangePin, s.lockChangePinDetail, TmIcons.Lock) { onPin(DesktopPinFlow.ChangeCheck) }
        Toggle(s.lockOnOpen, s.lockOnOpenDetail, appLock.onOpen, icon = TmIcons.VisibilityOff) { on ->
            if (on) AppLockSession.unlock()
            scope.launch { settings.setLockOnOpen(on) }
        }
    }
    Toggle(s.lockKids, AppLockWords.kidsDetail(appLock), appLock.kids, icon = TmIcons.Group) { on ->
        when {
            !appLock.pinSet -> onPin(DesktopPinFlow.SetForKids)
            !on -> scope.launch { settings.setKidsLock(false) }
            else -> {
                scope.launch { settings.setKidsLock(true) }
                if (appLock.kidsChats.isEmpty()) onKidsChats()
            }
        }
    }
    Action(s.lockAllowedChats, s.lockAllowedChatsDetail(count = appLock.kidsChats.size), TmIcons.List) { onKidsChats() }
    if (appLock.pinSet) {
        Action(s.lockRemovePin, s.lockRemovePinDetail, TmIcons.Close) { onPin(DesktopPinFlow.Remove) }
    }
    Text(
        s.lockForgot,
        style = MaterialTheme.typography.bodySmall,
        color = Tone.muted,
        modifier = Modifier.padding(horizontal = SETTING_INSET),
    )
    Action(s.settingsDevices, s.settingsDevicesDetail, TmIcons.Phone) { com.tmplayer.ui.settings.DevicesScreenState.open = true }
}

/**
 * About and help. No version and updates group: the page is always the newest build, and a reload
 * fetches it. No crash reports switch: the browser build sends none. The proxy row says where a
 * proxy goes instead, since tdweb reaches Telegram over the browser's own secure connection.
 */
@Composable
internal fun AboutRows(state: ShellState, version: String, onShowAbout: () -> Unit) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val toast = rememberToast()
    val supportCounters by settings.supportCounters.collectAsState(initial = SupportReminder.Counters())
    var supporting by remember { mutableStateOf(false) }
    var whatsNew by remember { mutableStateOf(false) }
    var reporting by remember { mutableStateOf(false) }
    var proxyNote by remember { mutableStateOf(false) }

    Group(s.settingsHelp)
    Action(s.proxyTitle, s.webProxyDetail, TmIcons.Wifi) { proxyNote = true }
    Action(s.settingsTour, s.settingsTourBody, TmIcons.Help) { scope.launch { settings.openTour() } }
    Action(s.settingsWhatsNew, s.settingsWhatsNewDetail(WhatsNew.VERSION), TmIcons.StarOutline) { whatsNew = true }
    Action(s.settingsChangelog, s.settingsChangelogDetail, TmIcons.List, end = RowEnd.External) {
        OpenExternal.browse(WhatsNew.CHANGELOG)
    }
    Action(s.settingsReportProblem, s.settingsReportProblemDetail, TmIcons.Bug) { reporting = true }
    // The page's last log lines (RecentLog), scrubbed of names and ids, to the browser's downloads.
    Action(s.logsSave, s.logsSaveDetail, TmIcons.Logs, end = RowEnd.None) {
        scope.launch {
            val lines = DesktopLog.recentLines(LogExport.MAX_LINES)
            if (lines.isEmpty()) {
                toast(s.logsEmpty)
                return@launch
            }
            val name = LogExport.fileName()
            val saved = runCatching {
                val known = LogExport.knownNames(settings, state.watched)
                TmHost.saveText(name, LogExport.report(webFeedbackFacts(), lines, known), "text/plain")
            }.getOrDefault(false)
            toast(if (saved) s.logsSavedTo(name) else s.logsFailed)
        }
    }
    Action(s.settingsPrivacy, s.settingsPrivacyComputer, TmIcons.Lock, end = RowEnd.External) {
        OpenExternal.browse(About.PRIVACY)
    }
    Action(s.settingsLawfulUse, s.settingsLawfulUseDetail, TmIcons.Gavel, end = RowEnd.External) {
        OpenExternal.browse(About.LEGAL)
    }
    if (SupportReminder.enabled) {
        Action(
            if (supportCounters.supporter) About.SUPPORT_THANKS_TITLE else About.SUPPORT_TITLE,
            s.settingsSupportDetail,
            TmIcons.Favorite,
        ) { supporting = true }
        // The coffee button in the corner (web/.../ui/WebOffers.kt): its x hides it for the
        // session, this for good. Gone with the rest once the viewer said they support.
        if (!supportCounters.finished) {
            val coffeeButton by com.tmplayer.web.ui.WebOffers.coffeeButtonOn.collectAsState()
            Toggle(s.webCoffeeSetting, s.webCoffeeSettingDetail, coffeeButton, icon = TmIcons.Favorite) { on ->
                com.tmplayer.web.ui.WebOffers.setCoffeeButton(on)
            }
        }
    }
    Action(s.settingsAbout, s.settingsAboutDetail(version), TmIcons.Info) { onShowAbout() }
    Text(
        s.settingsPrivacyNote,
        style = MaterialTheme.typography.bodyMedium,
        color = Tone.muted,
        modifier = Modifier.padding(start = SETTING_INSET, end = SETTING_INSET, top = 12.dp),
    )

    if (supporting) SupportPopup(from = "settings", onClose = { supporting = false })
    if (whatsNew) WhatsNewPopup(onClose = { whatsNew = false })
    if (reporting) FeedbackPopup(onClose = { reporting = false })
    if (proxyNote) {
        TmAlertDialog(
            onDismissRequest = { proxyNote = false },
            title = { Text(s.proxyTitle) },
            text = { Text(s.webProxyUnavailable, Modifier.widthIn(max = 520.dp)) },
            confirmButton = { TextButton(onClick = { proxyNote = false }) { Text(s.commonClose) } },
        )
    }
}

/** Settings, then Language: every language named in itself, the page switching as one is picked. */
@Composable
fun LanguagePopup(settings: SettingsStore, onClose: () -> Unit) {
    val s = LocalStrings.current
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.languagePickerTitle) },
        text = {
            Column(
                Modifier.widthIn(max = 720.dp).heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(s.languagePickerNote, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                LanguageList(settings, columns = 3)
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },
    )
}

/** "What's new": the highlights of [WhatsNew.VERSION] and a link to the whole changelog. */
@Composable
fun WhatsNewPopup(onClose: () -> Unit) {
    val s = LocalStrings.current
    TmAlertDialog(
        onDismissRequest = onClose,
        title = { Text(s.whatsnewTitle(WhatsNew.VERSION)) },
        text = {
            Column(Modifier.widthIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(s.whatsnewSubtitle, style = MaterialTheme.typography.bodyMedium, color = Tone.muted)
                for (line in WhatsNew.highlights) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(TmIcons.CheckCircle, contentDescription = null, tint = Tone.accent, modifier = Modifier.size(20.dp))
                        Text(line, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        dismissButton = { TextButton(onClick = { OpenExternal.browse(WhatsNew.CHANGELOG) }) { Text(s.whatsnewFullChangelog) } },
        confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },
    )
}

/** What a report from the page says about it: the web build's version, the browser and the system. */
fun webFeedbackFacts(): Feedback.Facts = Feedback.Facts(
    version = "web " + com.tmplayer.web.BuildInfo.VERSION,
    // The bug template has no browser option, so the reporter picks it.
    platform = Feedback.Platform.Other,
    device = com.tmplayer.web.td.WebAuth.deviceModel(),
    language = Translator.messages.tag,
)

/** "Report a problem": the prefilled bug report, an email instead, and the report as a QR code. */
@Composable
fun FeedbackPopup(onClose: () -> Unit) {
    val s = LocalStrings.current
    val facts = remember { webFeedbackFacts() }
    val issue = remember(facts) { Feedback.issueUrl(facts) }
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = issue) {
        value = runCatching { com.tmplayer.ui.auth.QrCode.render(issue, 400) }.getOrNull()
    }
    TmAlertDialog(
        onDismissRequest = onClose,
        icon = { Icon(TmIcons.Bug, contentDescription = null, tint = Tone.accent) },
        title = { Text(s.feedbackTitle) },
        text = {
            Row(Modifier.widthIn(max = 640.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(s.feedbackBodyDesktop, style = MaterialTheme.typography.bodyMedium)
                    Button(onClick = { OpenExternal.browse(issue); onClose() }) { Text(s.feedbackOpenGithub) }
                    OutlinedButton(onClick = { OpenExternal.mail(Feedback.mailto(facts)); onClose() }) { Text(s.feedbackEmail) }
                    Text(s.feedbackEmailDetail(Feedback.EMAIL), style = MaterialTheme.typography.bodySmall, color = Tone.muted)
                }
                Box(
                    Modifier.size(180.dp).clip(RoundedCornerShape(Corner.Large)).background(Color.White),
                    contentAlignment = Alignment.Center,
                ) {
                    bitmap?.let { Image(it, contentDescription = s.feedbackQr, modifier = Modifier.fillMaxSize().padding(8.dp)) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(s.commonClose) } },
    )
}
