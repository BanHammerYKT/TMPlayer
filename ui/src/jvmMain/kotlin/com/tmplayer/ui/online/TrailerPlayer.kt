package com.tmplayer.ui.online

import androidx.compose.runtime.Composable
import com.tmplayer.online.MetaTrailer

/** The desktop has no embedded player: a trailer opens in the browser, which plays it well. */
@Composable
internal fun rememberInAppTrailers(): Boolean = false

/** Nothing comes before the browser on a desktop. */
@Composable
internal fun rememberTrailerAppFirst(): ((MetaTrailer) -> Boolean)? = null

@Composable
@Suppress("UNUSED_PARAMETER")
internal fun TrailerPlayer(
    trailer: MetaTrailer,
    onClose: () -> Unit,
    onOpenElsewhere: () -> Unit,
    fallback: @Composable () -> Unit,
) = Unit
