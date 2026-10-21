package com.tmplayer.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/**
 * The name of an icon-only control, on a hover: [TipBubble] over it (under it with
 * [TipSide.Below], for a control near the top of the window), with the key that does the same
 * beside the name where there is one.
 *
 * [modifier] is the control's place in its parent: the tip's box is what the parent lays out.
 */
@Composable
fun IconTip(
    label: String,
    modifier: Modifier = Modifier,
    shortcut: String? = null,
    side: TipSide = TipSide.Above,
    content: @Composable () -> Unit,
) {
    // Compose for Web has no TooltipArea yet: the control alone, its label read by its own
    // content description. The browser build marks the canvas, not a DOM element, so no title.
    androidx.compose.foundation.layout.Box(modifier) { content() }
}
