package com.tmplayer.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.tmplayer.data.VideoDecoder
import com.tmplayer.ui.components.ChoiceLine
import com.tmplayer.ui.components.ChoiceList
import com.tmplayer.ui.components.ChoiceSheet
import com.tmplayer.ui.components.FocusOnOpen
import com.tmplayer.ui.i18n.LocalStrings

/** Settings, Decoder (Android): Automatic, or Prefer software video, each with what it is for. */
@Composable
fun DecoderSheet(current: VideoDecoder, onPick: (VideoDecoder) -> Unit, onClose: () -> Unit) {
    val s = LocalStrings.current
    val focus = remember { FocusRequester() }
    ChoiceSheet(title = s.settingsDecoder, onDismiss = onClose, onClose = onClose) {
        ChoiceList {
            VideoDecoder.entries.forEach { decoder ->
                val selected = decoder == current
                ChoiceLine(
                    title = decoder.label,
                    detail = decoder.detail,
                    selected = selected,
                    modifier = if (selected) Modifier.focusRequester(focus) else Modifier,
                ) {
                    onPick(decoder)
                    onClose()
                }
            }
        }
        FocusOnOpen(focus)
    }
}
