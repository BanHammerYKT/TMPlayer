package com.tmplayer.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.data.TdTabs
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.window.ComposeViewport
import androidx.compose.material3.MaterialTheme
import com.tmplayer.data.BookmarkStore
import com.tmplayer.data.ChatGate
import com.tmplayer.data.HiddenChats
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.Td
import com.tmplayer.data.ThemeChoice
import com.tmplayer.data.VideoCorrections
import com.tmplayer.data.WatchedStore
import com.tmplayer.data.pureBlackGround
import com.tmplayer.desktop.DesktopPaths
import com.tmplayer.desktop.DesktopServices
import com.tmplayer.desktop.player.WebPlayerScreen
import com.tmplayer.desktop.ui.DesktopExtras
import com.tmplayer.desktop.ui.DesktopShell
import com.tmplayer.desktop.ui.LocalVideoBehindPage
import com.tmplayer.desktop.ui.ProvideFocusVisibility
import com.tmplayer.desktop.ui.SettingsCategory
import com.tmplayer.desktop.ui.ShellState
import com.tmplayer.desktop.ui.settingsCategories
import com.tmplayer.desktop.ui.trackFocusVisibility
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.platform.Background
import com.tmplayer.platform.Credentials
import com.tmplayer.platform.DeviceInfo
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.UiScaled
import com.tmplayer.ui.theme.rememberUiScale
import com.tmplayer.ui.theme.withFamily
import com.tmplayer.web.data.LocalDataStore
import com.tmplayer.web.data.NoDownloads
import com.tmplayer.web.js.TmHost
import com.tmplayer.web.js.text
import com.tmplayer.web.ui.WebFonts
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The page's entry point (CP92). The UI catalogs and the text font first, since nothing can be
 * drawn without them; then TDLib starts (tdweb, through :core's own Td) and Compose takes the
 * page over with the desktop app's own window content, [DesktopShell]: its sign in, its top bar
 * and floating tabs, Home, Chats, a chat's grid, the detail pane, Search and Settings. Only the
 * platform is the browser's: the preference store over localStorage, tdweb under Td, and the
 * player's picture (WebPlayerScreen over web/player).
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    com.tmplayer.web.ui.installBrowserDefaults()
    val scope = MainScope()
    scope.launch {
        val settings = SettingsStore(LocalDataStore("tm.settings"))
        val watched = WatchedStore(LocalDataStore("tm.watched"))
        val bookmarks = BookmarkStore(LocalDataStore("tm.bookmarks"))
        DesktopServices.settings = settings
        DesktopServices.watched = watched
        // Every language the browser is set to, in order, as the apps read the system's list.
        val system = runCatching { TmHost.languages() }.getOrDefault("").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf(TmHost.language()) }
        val saved = runCatching { settings.language.first() }.getOrDefault("")
        val tag = Languages.resolve(saved, system)
        // TDLib first: its wasm loads and its database opens while the strings and the font come,
        // rather than after them.
        Td.start(
            DesktopPaths,
            DeviceInfo(model = deviceModel(), systemVersion = "", appVersion = BuildInfo.VERSION),
            Credentials(BuildInfo.TG_API_ID, BuildInfo.TG_API_HASH),
        )
        val english = async { TmHost.fetchText("i18n/en.json").text() }
        val chosen = async { if (tag != Languages.ENGLISH) TmHost.fetchText("i18n/$tag.json").text() else "" }
        val text = async { WebFonts.main() }
        val firstNames = WebFonts.firstFor(tag)
        val first = firstNames.map { name -> async { WebFonts.bytes("fonts/$name.ttf")?.let { WebFonts.family(name, it) } } }
        Translator.provide(Languages.ENGLISH, english.await())
        chosen.await().takeIf { it.isNotBlank() }?.let { Translator.provide(tag, it) }
        Translator.select(saved, system)
        TmHost.setLanguage(Translator.messages.tag, Translator.messages.rtl)
        val family = text.await()
        val firstFallbacks = first.mapNotNull { it.await() }
        com.tmplayer.web.player.WebPlayer.installStrings()

        ChatGate.follow(settings)
        HiddenChats.follow(settings)
        VideoCorrections.follow(settings)
        // Posters, backdrops, title logos and overviews, as on the apps (web/.../data/WebMetadata.kt).
        com.tmplayer.online.OnlineMetadata.current = com.tmplayer.web.data.webOnlineMetadata()
        // The decoded pictures' share: Skia decodes them at full size here, as on the desktop, and
        // the default of a dozen posters' worth meant a row scrolled away and back drew them again.
        com.tmplayer.data.Thumbnails.budget(pictureBudget())
        Background.scope.launch {
            settings.language.collect { chosen ->
                val want = Languages.resolve(chosen, system)
                if (want != Languages.ENGLISH && !Translator.hasCatalog(want)) {
                    runCatching { TmHost.fetchText("i18n/$want.json").text() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { Translator.provide(want, it) }
                }
                Translator.select(chosen, system)
                TmHost.setLanguage(Translator.messages.tag, Translator.messages.rtl)
            }
        }
        // Settings: every page the apps have that means something in a browser
        // (desktop/ui/SettingsPagesWeb.kt has the rows). Left out: Setup choices, whose answers
        // are the desktop's (performance, crash reports) or live on Library and Storage here.
        // Notifications are the checks run while the tab is open (DesktopUiWeb.kt, DesktopChecks).
        settingsCategories = listOf(
            SettingsCategory.Appearance,
            SettingsCategory.Playback,
            SettingsCategory.Subtitles,
            SettingsCategory.Mouse,
            SettingsCategory.Library,
            SettingsCategory.Notifications,
            SettingsCategory.Storage,
            SettingsCategory.Lock,
            SettingsCategory.About,
        )
        com.tmplayer.desktop.ui.settingsSummaryOverride = { category ->
            if (category == SettingsCategory.Notifications) com.tmplayer.i18n.L.webNotifySummary else null
        }

        val shell = ShellState(
            settings,
            NoDownloads,
            watched,
            services = { DesktopExtras(DesktopServices.prefs, null, null, null) },
            bookmarks = bookmarks,
        )
        ComposeViewport(document.getElementById("app")!!) {
            WebTheme(settings, family) {
                val resolver = LocalFontFamilyResolver.current
                LaunchedEffect(Unit) {
                    // The script the UI language needs first, then the others as fallbacks Skia
                    // reaches for a character Noto Sans does not have, each only once text in
                    // its script has come from Telegram (WebFonts.due): the East Asian ones are
                    // megabytes each, which most viewers never need.
                    for (f in firstFallbacks) runCatching { resolver.preload(f) }
                    val loaded = HashSet(firstNames)
                    while (loaded.size < WebFonts.FALLBACKS.size) {
                        // The language shown now, which Settings may have changed since the start.
                        for (name in WebFonts.due(Translator.messages.tag)) {
                            if (!loaded.add(name)) continue
                            val bytes = WebFonts.bytes("fonts/$name.ttf") ?: continue
                            runCatching { resolver.preload(WebFonts.family(name, bytes)) }
                        }
                        delay(2_000)
                    }
                }
                // The wait for Telegram is a blank ground, as the page's own splash is: words only
                // if it runs long (user, 2026-10-21).
                CompositionLocalProvider(LocalVideoBehindPage provides true) {
                    Box(Modifier.fillMaxSize().trackFocusVisibility()) {
                        DesktopShell(shell) { request, close ->
                            WebPlayerScreen(
                                request = request,
                                settings = settings,
                                prefs = DesktopServices.prefs,
                                watched = watched,
                                fullscreen = shell.fullscreen,
                                onToggleFullscreen = { shell.fullscreen = !shell.fullscreen },
                                onClose = {
                                    shell.fullscreen = false
                                    close()
                                },
                            )
                        }
                        // Another tab holds the account (TdTabs): this one waits over its own
                        // screens, kept as they were, until the viewer uses it here again.
                        val elsewhere by TdTabs.elsewhere.collectAsState()
                        if (elsewhere) OtherTab(onUseHere = TdTabs::useHere)
                    }
                }
            }
        }
        TmHost.ready()
        TmHost.registerWorker()
    }
}

/**
 * The desktop's theme (DesktopTheme in desktop/.../Main.kt) for the page: the Settings choice,
 * "System" following the browser's prefers-color-scheme live, Pure black, the accent and the
 * interface size, in the app's own text font. The page's ground and the browser's bar follow it.
 */
@Composable
private fun WebTheme(settings: SettingsStore, family: FontFamily?, content: @Composable () -> Unit) {
    val choice by settings.themeChoice.collectAsState(initial = ThemeChoice.Default)
    val systemDark by produceState(TmHost.prefersDark()) { TmHost.onDarkChange { value = it } }
    val dark = choice.isDark(systemDark)
    val pureBlack by settings.pureBlack.collectAsState(initial = false)
    val black = pureBlackGround(choice, pureBlack, systemDark)
    val scale by rememberUiScale(settings)
    val accent by settings.accent.collectAsState(initial = com.tmplayer.data.Accent.Default)
    val customAccent by settings.customAccent.collectAsState(initial = null)
    val highContrast by settings.highContrast.collectAsState(initial = false)
    val typeface by settings.typeface.collectAsState(initial = com.tmplayer.data.AppTypeface.Default)
    TmMaterialTheme(dark = dark, black = black, accent = accent, customAccent = customAccent, highContrast = highContrast, typeface = typeface) {
        val ground = MaterialTheme.colorScheme.background
        LaunchedEffect(ground, dark) {
            val rgb = ground.toArgb() and 0xFFFFFF
            TmHost.setThemeColor("#" + rgb.toString(16).padStart(6, '0'), dark)
        }
        val themed = remember(family) { family }
        if (themed != null && typeface == com.tmplayer.data.AppTypeface.System) {
            MaterialTheme(colorScheme = MaterialTheme.colorScheme, shapes = MaterialTheme.shapes, typography = MaterialTheme.typography.withFamily(themed)) {
                ProvideFocusVisibility { UiScaled(scale, content) }
            }
        } else {
            ProvideFocusVisibility { UiScaled(scale, content) }
        }
    }
}

/** The page while another tab holds the account: nothing under it can be pressed. */
@Composable
private fun OtherTab(onUseHere: () -> Unit) {
    val s = com.tmplayer.ui.i18n.LocalStrings.current
    androidx.compose.material3.Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(s.webOtherTabTitle, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                Text(s.webOtherTabBody, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(20.dp))
                Button(onClick = onUseHere, modifier = Modifier.height(48.dp)) { Text(s.webUseHere) }
            }
        }
    }
}

/** "Chrome on Windows", "Safari on iPhone": the name Telegram's Devices list shows for this session. */
private fun deviceModel(): String = runCatching { TmHost.deviceKind() }.getOrDefault("Browser").ifBlank { "Browser" }

/**
 * The bytes the decoded pictures may take in this tab: ten screens of pixels, between 96 and 192
 * MB. A phone (an iPhone is 1170 by 2532) lands near 120 MB, a laptop at the floor and a 4K
 * screen at the ceiling, where one carousel picture alone is 33 MB.
 */
private fun pictureBudget(): Int {
    val ratio = kotlinx.browser.window.devicePixelRatio.takeIf { it > 0.0 } ?: 1.0
    val screen = kotlinx.browser.window.screen
    val pixels = screen.width * ratio * screen.height * ratio
    return (pixels * 4 * PICTURE_SCREENS).toLong().coerceIn(96L shl 20, 192L shl 20).toInt()
}

/** How many screens of decoded pictures [pictureBudget] keeps. */
private const val PICTURE_SCREENS = 10
