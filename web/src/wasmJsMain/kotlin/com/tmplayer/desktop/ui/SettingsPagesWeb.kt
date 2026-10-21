// The rows of Settings' pages in the browser beta: the theme, drawn with the desktop's own row
// pieces (SettingsRows.kt), as AppearanceRows in desktop/.../ui/SettingsPages.kt draws it. The
// other pages are the apps' until after the beta (docs/WEB.md), and the browser shows only
// Appearance (settingsCategories, set in com.tmplayer.web.Main).
package com.tmplayer.desktop.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.Modifier
import com.tmplayer.data.ThemeChoice
import com.tmplayer.ui.components.JumpTarget
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.icons.TmIcons
import com.tmplayer.ui.onboarding.SetupStep
import kotlinx.coroutines.launch

@Composable
internal fun AppearanceRows(state: ShellState) {
    val s = LocalStrings.current
    val settings = state.settings
    val scope = rememberCoroutineScope()
    val theme by settings.themeChoice.collectAsState(initial = ThemeChoice.Default)
    val pureBlack by settings.pureBlack.collectAsState(initial = false)
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
}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun PlaybackRows(state: ShellState, jumps: Map<SetupStep, JumpTarget>) {}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun SubtitlesRows(state: ShellState) {}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun MouseRows(state: ShellState) {}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun LibraryRows(state: ShellState, version: String, chatList: List<com.tmplayer.data.ChatSummary>, jumps: Map<SetupStep, JumpTarget>) {}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun NotificationRows(state: ShellState) {}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun LockRows(state: ShellState, onPin: (DesktopPinFlow) -> Unit, onKidsChats: () -> Unit) {}

@Suppress("UNUSED_PARAMETER")
@Composable
internal fun AboutRows(state: ShellState, version: String, onShowAbout: () -> Unit) {}
