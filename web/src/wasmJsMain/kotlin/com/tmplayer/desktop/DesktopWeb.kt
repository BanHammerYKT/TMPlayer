// The browser's stand ins for what the desktop's screens reach for outside themselves
// (web/desktop-sources.txt lists the screens). The desktop keeps files, a download folder, a
// self updater and crash reports; a page has none of them, so each is here as the screens see
// it: nothing downloaded, nothing to update, nothing to report.
package com.tmplayer.desktop

import com.tmplayer.data.Connectivity
import com.tmplayer.data.DiskInfo
import com.tmplayer.data.NetworkStatus
import com.tmplayer.data.ResumeRecord
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.web.js.TmHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** The build's version, as the web build writes it (web/build.gradle.kts, generateBuildInfo). */
internal object BuildInfo {
    const val VERSION = com.tmplayer.web.BuildInfo.VERSION
    const val SENTRY_DSN = ""
}

/** The places the desktop keeps things. In the browser they are names only: tdweb keeps its own. */
object DesktopPaths : com.tmplayer.platform.Paths {
    override val databaseDir: File = File("/tdlib")
    override val downloadsDir: File = File("/downloads")
    override val filesDir: File = File("/files")
    val settingsFile: File = File("/config/settings.preferences_pb")
    val prefsFile: File = File("/config/desktop.properties")

    /** The browser does not say how much room a drive has; the storage quota is the page's. */
    override fun disk(): DiskInfo = DiskInfo(0, 0)
    override fun downloadsDisk(): DiskInfo = DiskInfo(0, 0)
}

/** The Downloads folder's index. The browser keeps no downloads (docs/WEB.md, "After the beta"). */
object DownloadIndex {
    @Suppress("UNUSED_PARAMETER")
    suspend fun fileFor(settings: SettingsStore, chatId: Long, messageId: Long): File? = null

    @Suppress("UNUSED_PARAMETER")
    suspend fun delete(settings: SettingsStore, record: ResumeRecord): Boolean = false

    @Suppress("UNUSED_PARAMETER")
    suspend fun presentIds(settings: SettingsStore): Set<String> = emptySet()

    @Suppress("UNUSED_PARAMETER")
    fun bytesOnDisk(records: List<ResumeRecord>): Long = 0L
}

/** The watch cache: in the browser it is tdweb's own storage (web/tdweb/sparse-fs.js). */
class DesktopWatchCache private constructor()

/** The self updater: a page is updated by being reloaded, so there is none. */
class SelfUpdate private constructor()

/** The desktop's process wide services, as many as the browser has. */
object DesktopServices {
    val prefs: DesktopPrefs by lazy { DesktopPrefs(DesktopPaths.prefsFile) }
    val updates: UpdateScheduler? get() = null
    val watchCache: DesktopWatchCache? get() = null
    val selfUpdate: SelfUpdate? get() = null

    /** The page's stores, set at start (com.tmplayer.web.Main), for Send logs to scrub names with. */
    var settings: SettingsStore? = null
    var watched: com.tmplayer.data.WatchedStore? = null
}

/** Whether there is a network, from the browser's own online and offline events. */
object DesktopConnectivity : Connectivity {
    private val _status = MutableStateFlow(NetworkStatus.Unknown)
    override val status: StateFlow<NetworkStatus> = _status.asStateFlow()
    override val metered: StateFlow<Boolean> = MutableStateFlow(false).asStateFlow()
    private var started = false

    fun start() {
        if (started) return
        started = true
        _status.value = if (TmHost.online()) NetworkStatus.Online else NetworkStatus.Offline
        TmHost.onOnline { on -> _status.value = if (on) NetworkStatus.Online else NetworkStatus.Offline }
    }
}

/** Storage housekeeping: tdweb's storage keeps itself (web/tdweb/sparse-fs.js). */
object DesktopStorage {
    @Suppress("UNUSED_PARAMETER")
    suspend fun housekeeping(cache: DesktopWatchCache?) {}
}

/** Crash reports: the browser build sends none. */
object DesktopCrashReports {
    val available: Boolean = false

    @Suppress("UNUSED_PARAMETER")
    fun start(enabled: Boolean) {}
    fun stop() {}
    fun discardPending() {}
}

/** Copying a download out: there are no downloads in the browser. */
object DesktopExport {
    fun freeName(folder: File, name: String): File = File(folder, name)
}
