package com.tmplayer.ui.online

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.tmplayer.online.MetaTrailer

/**
 * Opens a trailer in the YouTube app (`vnd.youtube:`), else whatever opens a YouTube link. False
 * when nothing on the device can, which on a television without YouTube is common: the caller
 * then shows the link as a QR code for a phone.
 */
@Composable
internal fun rememberTrailerOpener(): (MetaTrailer) -> Boolean {
    val context = LocalContext.current
    return remember(context) {
        { trailer: MetaTrailer ->
            fun open(uri: String): Boolean = try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: ActivityNotFoundException) {
                false
            } catch (e: SecurityException) {
                false
            }
            open(trailer.appUri) || open(trailer.watchUrl)
        }
    }
}

/**
 * On a television, the YouTube app before anything else: its own client is made for the remote
 * and plays every trailer, which YouTube's embedded player in a WebView does not on many TVs (a
 * press that seemed to do nothing). Only the `vnd.youtube:` link, which only a YouTube app
 * answers, so a TV without one goes on to the in-app player rather than to a browser it may not
 * have. Null on a phone, which keeps the trailer inside the app.
 */
@Composable
internal fun rememberTrailerAppFirst(): ((MetaTrailer) -> Boolean)? {
    val context = LocalContext.current
    return remember(context) {
        if (!com.tmplayer.data.FormFactor.isTv(context)) return@remember null
        { trailer: MetaTrailer ->
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(trailer.appUri)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            } catch (e: ActivityNotFoundException) {
                false
            } catch (e: SecurityException) {
                false
            }
        }
    }
}
