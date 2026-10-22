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
 * The YouTube app before anything else, on a television and on a phone: its own client plays
 * every trailer, which YouTube's embedded player in a WebView does not on many TVs (a press that
 * seemed to do nothing) nor on some phones (sound over a black screen on a POCO F5 with WebView
 * 157, 2026-10-22). Only the `vnd.youtube:` link, which only a YouTube app answers, so a device
 * without one goes on to the in-app player rather than to a browser.
 */
@Composable
internal fun rememberTrailerAppFirst(): ((MetaTrailer) -> Boolean)? {
    val context = LocalContext.current
    return remember(context) {
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
