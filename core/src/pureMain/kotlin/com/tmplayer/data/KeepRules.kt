package com.tmplayer.data

/** The rules for keeping a copy of a video that need no Telegram client, shared with the browser build. */
object KeepRules {
    /**
     * Whether a copy may leave the app for the device's own gallery or files: only a download the
     * chat allows saving. See KeepVideo for forwarding.
     */
    fun mayExport(canBeSaved: Boolean, inDownloads: Boolean): Boolean = canBeSaved && inDownloads
}
