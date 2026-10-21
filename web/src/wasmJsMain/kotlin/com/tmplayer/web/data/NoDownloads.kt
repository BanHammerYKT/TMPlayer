package com.tmplayer.web.data

import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner

/**
 * The download queue's runner in the browser: none. Keeping a video is the apps' (a page cannot
 * write a file the viewer keeps), so a request is answered with the refusal the rows show.
 */
object NoDownloads : DownloadRunner {
    @Suppress("UNUSED_PARAMETER")
    override fun download(request: DownloadRequest) {}

    @Suppress("UNUSED_PARAMETER")
    override fun cancel(fileId: Int) {}

    @Suppress("UNUSED_PARAMETER")
    override fun pause(fileId: Int) {}
}
