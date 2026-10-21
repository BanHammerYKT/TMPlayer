package com.tmplayer.ui.online

import androidx.compose.runtime.Composable
import com.tmplayer.online.MetaTrailer

private fun openTab(url: String): Unit = js("void window.open(url, '_blank', 'noopener')")

/** Opens a trailer's YouTube page in a new tab of the browser the app is in. */
@Composable
internal fun rememberTrailerOpener(): (MetaTrailer) -> Boolean = { trailer ->
    openTab(trailer.watchUrl)
    true
}
