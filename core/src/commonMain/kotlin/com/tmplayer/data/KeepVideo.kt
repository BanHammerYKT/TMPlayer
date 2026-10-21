package com.tmplayer.data

/**
 * Keeping a copy of a video outside TMPlayer's own folder (CP82): the phone's gallery, or a folder
 * on the desktop. Forwarding a post from inside the app was part of this until 2026-10-21, when it
 * was taken out; forwarding stays Telegram's own job.
 */
object KeepVideo {

    /**
     * Whether a finished download may be copied out to the phone's gallery or a folder: only
     * where it could be saved at all (CP04's gate), and only once it is whole in Downloads.
     */
    fun mayExport(canBeSaved: Boolean, inDownloads: Boolean): Boolean = KeepRules.mayExport(canBeSaved, inDownloads)
}
