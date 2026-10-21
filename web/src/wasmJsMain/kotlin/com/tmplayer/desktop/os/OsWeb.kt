// The operating system as the desktop's screens ask about it, answered by the browser.
package com.tmplayer.desktop.os

import com.tmplayer.web.js.TmHost
import java.io.File

/** Which computer the page runs on, for the shortcut labels (Cmd on a Mac, Ctrl elsewhere). */
object OsInfo {
    private val platform: String by lazy { runCatching { TmHost.platform().lowercase() }.getOrDefault("") }

    val isMac: Boolean get() = "mac" in platform && !TmHost.isIos()
    val isWindows: Boolean get() = "win" in platform
    val isLinux: Boolean get() = !isMac && !isWindows
    val osTag: String get() = if (isMac) "macos" else if (isWindows) "windows" else "linux"
}

/** Links open in a new tab; files are not the page's to open. */
object OpenExternal {
    fun browse(url: String) = TmHost.openUrl(if (url.contains("://") || url.startsWith("mailto:")) url else "https://$url")

    fun mail(mailto: String) = TmHost.openUrl(mailto)

    @Suppress("UNUSED_PARAMETER")
    fun open(file: File) {}

    @Suppress("UNUSED_PARAMETER")
    fun reveal(file: File) {}
}

/** A page cannot hand out or take a path on the disk. */
object FolderPicker {
    @Suppress("UNUSED_PARAMETER")
    suspend fun pick(title: String, startIn: File?): File? = null
}

object FilePicker {
    @Suppress("UNUSED_PARAMETER")
    suspend fun open(title: String, vararg extensions: String): File? = null

    @Suppress("UNUSED_PARAMETER")
    suspend fun save(title: String, suggestedName: String, extension: String): File? = null
}

/** The log is the browser console's. */
object DesktopLog {
    @Suppress("UNUSED_PARAMETER")
    fun recentLines(max: Int): List<String> = emptyList()
}
