package com.tmplayer.online

/**
 * Online subtitles are not in the browser build yet (docs/WEB.md, "After the beta"): the JVM
 * class downloads to files. This is the one fact the shared screens ask of it.
 */
class OnlineSubtitles private constructor() {
    companion object {
        val available: Boolean get() = false
    }
}
