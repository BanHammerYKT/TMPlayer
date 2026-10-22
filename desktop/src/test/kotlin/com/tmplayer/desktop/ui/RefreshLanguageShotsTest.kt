package com.tmplayer.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.Bookmark
import com.tmplayer.data.CourseProgress
import com.tmplayer.data.DetailAction
import com.tmplayer.data.DownloadRequest
import com.tmplayer.data.DownloadRunner
import com.tmplayer.data.MediaItem
import com.tmplayer.data.SeriesShelf
import com.tmplayer.data.SettingsStore
import com.tmplayer.data.ShelfEntry
import com.tmplayer.data.UpdateScheduler
import com.tmplayer.data.WatchPoint
import com.tmplayer.data.WatchedStore
import com.tmplayer.desktop.DesktopPrefs
import com.tmplayer.desktop.DesktopWatchCache
import com.tmplayer.desktop.WatchedWords
import com.tmplayer.desktop.player.MenuAt
import com.tmplayer.desktop.player.MenuPage
import com.tmplayer.desktop.player.PlaybackStatus
import com.tmplayer.desktop.player.PlayerMenu
import com.tmplayer.desktop.player.PlayerTheme
import com.tmplayer.desktop.setWatchLater
import com.tmplayer.desktop.setWatched
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.online.MetaQuery
import com.tmplayer.online.MetaResult
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.ui.browse.CourseHeader
import com.tmplayer.ui.browse.LocalSeriesWatch
import com.tmplayer.ui.browse.MediaDetailScreen
import com.tmplayer.ui.browse.SeriesPanel
import com.tmplayer.ui.browse.SeriesWatch
import com.tmplayer.ui.browse.detailRow
import com.tmplayer.ui.i18n.LocalStrings
import com.tmplayer.ui.i18n.ProvideStrings
import com.tmplayer.ui.lock.PinPad
import com.tmplayer.ui.lock.PinPurpose
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface as SkSurface
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The screens of the cinematic refresh and the batch of features beside it, in the catalogs that
 * run longest (German, French, Polish, Russian, Ukrainian, Malayalam, Bengali, Hindi), right to
 * left (Arabic, Persian) and in the en-XA pseudo-locale: Settings with its new rows, the PIN pad,
 * the poster grid with the posters notice and the invite, a course's header, a title's side panel
 * and page, a show's page, History's and Downloads' tabs and the player's menu. Each frame is
 * drawn so a missing string or a layout that throws fails here; with TMPLAYER_SHOTS=<dir> every
 * language is drawn and written there as `refresh-<tag>-<name>.png`, without it English and
 * Arabic only.
 */
class RefreshLanguageShotsTest {

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-refresh").resolve(SettingsStore.FILE_NAME).toFile()),
    )
    private val watched = WatchedStore(
        WatchedStore.openDataStore(Files.createTempDirectory("tm-refresh-watched").resolve(WatchedStore.FILE_NAME).toFile()),
    )
    private val dir = Files.createTempDirectory("tm-refresh-extras").toFile()
    private val shell = ShellState(
        settings,
        object : DownloadRunner {
            override fun download(request: DownloadRequest) = Unit
            override fun cancel(fileId: Int) = Unit
            override fun pause(fileId: Int) = Unit
        },
        watched,
        services = {
            DesktopExtras(
                prefs = DesktopPrefs(dir.resolve("desktop.properties")),
                updates = UpdateScheduler(settings.updatePrefs, check = { _, _ -> false }, onSkip = {}),
                watchCache = DesktopWatchCache(settings, { dir.resolve("cache") }),
            )
        },
    )

    private fun item(id: Long, chatId: Long, name: String, colour: Int) = MediaItem(
        chatId = chatId, messageId = id, fileId = id.toInt(), title = name, sizeBytes = 885L * 1024 * 1024,
        durationSec = 1_496 + (id % 10).toInt() * 120, mimeType = "video/x-matroska", thumbnailFileId = 0,
        miniThumbnail = jpeg(colour), date = 1_790_000_000 + id.toInt(), fileName = name, width = 1920, height = 1080,
    )

    private val bunny = item(500, 2, "Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv", 0xFF46A758.toInt())
    private val episodes = (1..8).map { n ->
        item(600L + n, 4, "Harbour.Lights.S0${if (n > 5) 2 else 1}E%02d.1080p.WEB-DL.mkv".format(if (n > 5) n - 5 else n), 0xFF2AABEE.toInt())
    }
    private val lessons = (1..6).map { n -> item(700L + n, 9, "Compilers Lecture %02d - Parsing and grammars.mp4".format(n), 0xFFF5A524.toInt()) }
    private val clips = listOf("Coast walk, day 2", "Chickpea salad recipe", "Build a small shelf")
        .mapIndexed { at, name -> item(800L + at, 3, "$name.mp4", 0xFF8E4EC6.toInt() + at * 0x102030) }

    private val watch = SeriesWatch(
        point = { if (it.messageId == 602L || it.messageId == 703L) WatchPoint(600_000, 1_600_000) else null },
        finished = { it.messageId == 601L || it.messageId == 701L || it.messageId == 702L },
    )

    @Test
    fun everyLongOrRightToLeftLanguage() = runBlocking {
        val all = System.getenv("TMPLAYER_SHOTS")?.isNotBlank() == true
        val tags = if (all) {
            listOf(Languages.ENGLISH, Languages.PSEUDO, "de", "fr", "pl", "ru", "uk", "ml", "bn", "hi", "ar", "fa")
        } else {
            listOf(Languages.ENGLISH, "ar")
        }
        val online = MetaFixture.install("on")
        try {
            warm(online, bunny, episodes[4])
            settings.saveResumePosition(2, 500, 726_000, 1_700_000)
            setWatched(watched, settings, episodes[0], "Weekend series", true)
            setWatchLater(watched, clips[0], "Weekend Clips", true)
            settings.saveResumePosition(3, 801, 300_000, 900_000)
            settings.rememberCachedVideo(episodes[5], "Weekend series")
            for (tag in tags) {
                Translator.use(tag)
                try {
                    shots(tag, online)
                } finally {
                    Translator.use(Languages.ENGLISH)
                }
            }
        } finally {
            OnlineMetadata.current = null
        }
    }

    private suspend fun shots(tag: String, online: OnlineMetadata) {
        fun shot(name: String, width: Int = 1280, height: Int = 800, sidebar: Boolean = true, page: @Composable () -> Unit) =
            save("refresh-$tag-$name.png", render(width, height, sidebar, page))

        // The narrowest window the desktop allows, and tall enough for every group.
        shot("settings", width = 960, height = 7600) { SettingsPage(shell, "2.0.0") }
        shot("pinpad", width = 960, height = 600) {
            PinPad(settings, PinPurpose.Choose, LocalStrings.current.lockNewPin, onDone = {}, onCancel = {})
        }
        shot("pinpad-phone", width = 412, height = 860, sidebar = false) {
            PinPad(settings, PinPurpose.Check, LocalStrings.current.lockEnterCurrent, onDone = {}, onCancel = {})
        }
        shot("pinpad-phone-wide", width = 860, height = 412, sidebar = false) {
            PinPad(settings, PinPurpose.Choose, LocalStrings.current.lockNewPin, onDone = {}, onCancel = {})
        }
        val show = (SeriesShelf.arrange(episodes).first() as ShelfEntry.Show).series
        shot("grid") {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) { MetaNoticeCard(shell) }
                VideoGrid(
                    shell, listOf(bunny, show.cover) + clips, "Weekend Clips",
                    series = mapOf(show.cover.id to show), posters = setOf(bunny.id, show.cover.id),
                )
            }
        }
        shot("course", width = 960, height = 300) {
            val progress = CourseProgress.of(lessons, onList = { watch.finished(it) }, point = { watch.point(it) })!!
            Box(Modifier.padding(24.dp)) { CourseHeader(progress, "CS Lectures", onPlay = {}) }
        }
        shell.openDetail(DetailRequest(bunny, "Film Club", selectThis = {}))
        try {
            shot("detail-side") {
                Box(Modifier.fillMaxSize()) {
                    VideoGrid(shell, clips, "Film Club")
                    DetailPaneHost(shell)
                }
            }
        } finally {
            shell.closeDetail()
        }
        shot("detail-phone", width = 412, height = 1100, sidebar = false) {
            val s = LocalStrings.current
            val point = WatchPoint(726_000, 1_700_000)
            val rows = listOf(DetailAction.Resume, DetailAction.StartOver, DetailAction.MarkWatched, DetailAction.AddWatchLater, DetailAction.Download)
                .map { detailRow(it, s, point, "Film Club", onSelect = {}, free = "120 GB free") }
            MediaDetailScreen(bunny, "Film Club", point, false, rows, onClose = {}, bookmarks = listOf(Bookmark(2, 500, 1, 420_000, "The bunny wakes")))
        }
        for ((name, size) in listOf("series-phone" to (412 to 1300), "series-wide" to (960 to 540), "series-desktop" to (1280 to 900))) {
            shot(name, width = size.first, height = size.second, sidebar = name == "series-desktop") {
                SeriesPanel(
                    show, watch, onPlay = {}, onDownload = {}, chatTitle = "Weekend series",
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        shot("history") { HistoryPage(shell) }
        shot("downloads") { DownloadsPage(shell, dir.resolve("Downloads").apply { mkdirs() }) }
        for (page in listOf(MenuPage.Main, MenuPage.Bookmarks, MenuPage.Options)) {
            shot("playermenu-${page.name.lowercase()}", sidebar = false) {
                PlayerTheme {
                    Box(Modifier.fillMaxSize().background(Color(0xFF203040))) {
                        Box(Modifier.align(Alignment.TopEnd).padding(top = 40.dp, end = 300.dp)) {
                            PlayerMenu(
                                menu = MenuAt(page, MenuAt.Anchor.Overflow),
                                status = PlaybackStatus(opened = true, durationMs = 2_634_000, positionMs = 1_260_000, playing = true),
                                tracks = emptyList(),
                                fullscreen = false,
                                ignoreClicks = false,
                                miniPlayerAvailable = true,
                                alwaysOnTopAvailable = true,
                                fromTelegram = true,
                                savable = true,
                                watchedLabel = WatchedWords.markLabel(false),
                                sleepTimer = null,
                                bookmarks = if (page == MenuPage.Bookmarks) emptyList() else listOf(Bookmark(1, 1, 1, 420_000, "")),
                                onOpenMenu = {},
                                onClose = {},
                                onAction = {},
                            )
                        }
                    }
                }
            }
        }
        // The invite asks only while posters are off from an older version and no setup is pending.
        MetaFixture.install("off")
        try {
            shot("invite") { Box(Modifier.fillMaxSize()) { VideoGrid(shell, clips, "Weekend Clips"); MetaInviteDialog(shell) } }
        } finally {
            OnlineMetadata.current = online
        }
    }

    private suspend fun warm(online: OnlineMetadata, vararg items: MediaItem) {
        for (it in items) {
            val q = MetaQuery.of(it.fileName)!!
            online.lookup(q)
            val info = (online.lookup(q.showOnly()) as? MetaResult.Found)?.info
            if (info != null) {
                online.extras(info)
                for (url in listOfNotNull(info.posterUrl, info.backdropUrl, info.wideUrl)) {
                    for (width in listOf(240, 342, 480, 1280)) com.tmplayer.data.Thumbnails.online(url, width) { online.image(url) }
                }
            }
        }
    }

    private fun render(width: Int, height: Int, sidebar: Boolean, page: @Composable () -> Unit): ByteArray =
        ImageComposeScene(width, height, Density(1f)) {
            TmMaterialTheme(dark = true) {
                ProvideStrings {
                    CompositionLocalProvider(LocalSeriesWatch provides watch) {
                        Surface(Modifier.fillMaxSize(), color = Tone.background) {
                            if (sidebar) {
                                NavFrame(shell, null, null) { page() }
                            } else {
                                page()
                            }
                        }
                    }
                }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(400)
            scene.render(500_000_000)
            Thread.sleep(200)
            scene.render(1_000_000_000)
            scene.render(2_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve(name).writeBytes(png)
    }

    /** A tiny JPEG in [argb], standing in for Telegram's minithumbnail. */
    private fun jpeg(argb: Int): ByteArray {
        val surface = SkSurface.makeRasterN32Premul(40, 24)
        surface.canvas.drawRect(Rect.makeWH(40f, 24f), Paint().apply { color = argb or 0xFF000000.toInt() })
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.JPEG, 80)!!.bytes
    }
}
