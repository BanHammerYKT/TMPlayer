package com.tmplayer.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.tmplayer.data.LearntSeries
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSection
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.icons.TmIcons

/**
 * Settings, Remembered languages: each series the player has learnt, by name, with what it
 * opens with ("Sound: Malayalam, Subtitles: English") and a Forget line under it; Forget all at
 * the top. A forget acts at once and the list redraws without it, so a remote stays in the sheet
 * and walks on to the next one. Empty, the sheet says how the list fills.
 */
@Composable
fun LearntLanguagesSheet(
    learnt: List<LearntSeries>,
    onForget: (LearntSeries) -> Unit,
    onForgetAll: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalStrings.current
    val first = remember { FocusRequester() }
    ChoiceSheet(
        title = s.settingsLearntLanguages,
        note = if (learnt.isEmpty()) s.settingsLearntLanguagesEmpty else s.settingsLearntLanguagesNote,
        onDismiss = onClose,
        onClose = onClose,
    ) {
        if (learnt.isNotEmpty()) {
            ChoiceList {
                ChoiceLine(
                    title = s.settingsLearntLanguagesForgetAll,
                    detail = s.settingsLearntLanguagesCount(learnt.size),
                    icon = TmIcons.Delete,
                    modifier = Modifier.focusRequester(first),
                    onClick = onForgetAll,
                )
                learnt.forEach { series ->
                    ChoiceSection(series.name)
                    ChoiceLine(
                        title = s.settingsLearntLanguagesForget,
                        detail = series.line(),
                        icon = TmIcons.Close,
                    ) { onForget(series) }
                }
            }
            FocusOnOpen(first, key = learnt.size)
        }
    }
}
