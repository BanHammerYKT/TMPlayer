package com.tmplayer.web

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import com.tmplayer.ui.components.LocalBrandBadge
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
    val scope = MainScope()
    scope.launch {
        val settings = SettingsStore(LocalDataStore("tm.settings"))
        val watched = WatchedStore(LocalDataStore("tm.watched"))
        val bookmarks = BookmarkStore(LocalDataStore("tm.bookmarks"))
        val system = listOf(TmHost.language())
        val saved = runCatching { settings.language.first() }.getOrDefault("")
        val tag = Languages.resolve(saved, system)
        Translator.provide(Languages.ENGLISH, TmHost.fetchText("i18n/en.json").text())
        if (tag != Languages.ENGLISH) {
            TmHost.fetchText("i18n/$tag.json").text().takeIf { it.isNotBlank() }?.let { Translator.provide(tag, it) }
        }
        Translator.select(saved, system)
        val family = WebFonts.main()
        val firstFallbacks = WebFonts.firstFor(tag).mapNotNull { name ->
            WebFonts.bytes("fonts/$name.ttf")?.let { WebFonts.family(name, it) }
        }
        com.tmplayer.web.player.WebPlayer.installStrings()

        Td.start(
            DesktopPaths,
            DeviceInfo(model = deviceModel(), systemVersion = "", appVersion = BuildInfo.VERSION),
            Credentials(BuildInfo.TG_API_ID, BuildInfo.TG_API_HASH),
        )
        ChatGate.follow(settings)
        HiddenChats.follow(settings)
        VideoCorrections.follow(settings)
        Background.scope.launch {
            settings.language.collect { chosen ->
                val want = Languages.resolve(chosen, system)
                if (want != Languages.ENGLISH && !Translator.hasCatalog(want)) {
                    runCatching { TmHost.fetchText("i18n/$want.json").text() }.getOrNull()?.takeIf { it.isNotBlank() }?.let { Translator.provide(want, it) }
                }
                Translator.select(chosen, system)
            }
        }
        // Settings in the beta: Appearance (the theme) and Sign out. The rest is the apps' for now.
        settingsCategories = listOf(SettingsCategory.Appearance)

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
                    // The script the UI language needs first, then the emoji and every other
                    // script, as fallbacks Skia reaches for a character Noto Sans does not have.
                    for (f in firstFallbacks) runCatching { resolver.preload(f) }
                    for (name in WebFonts.FALLBACKS) {
                        val bytes = WebFonts.bytes("fonts/$name.ttf") ?: continue
                        runCatching { resolver.preload(WebFonts.family(name, bytes)) }
                    }
                }
                CompositionLocalProvider(
                    LocalVideoBehindPage provides true,
                    LocalBrandBadge provides Translator.messages.text("web.beta"),
                ) {
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

/** "Chrome on Windows", "Safari on iPhone": the name Telegram's Devices list shows for this session. */
private fun deviceModel(): String = runCatching { TmHost.deviceKind() }.getOrDefault("Browser").ifBlank { "Browser" }
