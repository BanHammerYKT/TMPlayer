package com.tmplayer.data

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.tmplayer.online.MetadataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Black as a switch under the theme (2026-10-19), and the old fourth theme read as one. */
class PureBlackTest {

    private val dir = Files.createTempDirectory("tm-pure-black").toFile()
    private val data = SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME))
    private val settings = SettingsStore(data)

    private suspend fun storeRaw(theme: String?, pureBlack: Boolean? = null) = data.edit {
        if (theme != null) it[stringPreferencesKey("theme_choice")] = theme
        if (pureBlack != null) it[booleanPreferencesKey("pure_black")] = pureBlack
    }

    private suspend fun rawTheme(): String? = data.data.first()[stringPreferencesKey("theme_choice")]

    @Test
    fun `the theme offers System, Light and Dark, and the switch is off by default`() = runBlocking {
        assertEquals(listOf("System", "Light", "Dark"), ThemeChoice.entries.map { it.name })
        assertEquals(ThemeChoice.System, settings.themeChoice.first())
        assertFalse(settings.pureBlack.first())
    }

    @Test
    fun `a stored Black reads as Dark with the switch on`() = runBlocking {
        storeRaw("Black", pureBlack = false)
        assertEquals(ThemeChoice.Dark, ThemeChoice.from("Black"))
        assertEquals(ThemeChoice.Dark, settings.themeChoice.first())
        assertTrue(settings.pureBlack.first())
    }

    @Test
    fun `choosing another theme over a stored Black keeps the switch on`() = runBlocking {
        storeRaw("Black")
        settings.setThemeChoice(ThemeChoice.Light)
        assertEquals("Light", rawTheme())
        assertEquals(ThemeChoice.Light, settings.themeChoice.first())
        assertTrue(settings.pureBlack.first())
    }

    @Test
    fun `turning the switch off over a stored Black leaves Dark`() = runBlocking {
        storeRaw("Black")
        settings.setPureBlack(false)
        assertEquals("Dark", rawTheme())
        assertEquals(ThemeChoice.Dark, settings.themeChoice.first())
        assertFalse(settings.pureBlack.first())
    }

    @Test
    fun `an old backup with Black restores as Dark with the switch on`() = runBlocking {
        settings.setPureBlack(false)
        val service = BackupService(
            settings,
            WatchedStore(WatchedStore.openDataStore(dir.resolve(WatchedStore.FILE_NAME))),
            MetadataStore(dir.resolve("metadata.properties")),
            "1.23.0",
            now = { 0L },
            bookmarks = BookmarkStore(BookmarkStore.openDataStore(dir.resolve(BookmarkStore.FILE_NAME))),
        )
        val old = Backup(
            createdAt = 0L, appVersion = "1.20.0", settings = mapOf("theme_choice" to "Black"), metadata = emptyMap(),
            favourites = emptySet(), recentSearches = emptyList(), memory = emptyMap(), resume = emptyList(), watched = emptyList(),
        )
        service.restore(old)
        assertEquals(ThemeChoice.Dark, settings.themeChoice.first())
        assertTrue(settings.pureBlack.first())
    }

    @Test
    fun `pure black applies only while the app is dark`() {
        assertTrue(pureBlackGround(ThemeChoice.Dark, pureBlack = true, systemDark = false))
        assertTrue(pureBlackGround(ThemeChoice.System, pureBlack = true, systemDark = true))
        assertFalse(pureBlackGround(ThemeChoice.System, pureBlack = true, systemDark = false))
        assertFalse(pureBlackGround(ThemeChoice.Light, pureBlack = true, systemDark = true))
        assertFalse(pureBlackGround(ThemeChoice.Dark, pureBlack = false, systemDark = true))
    }

    @Test
    fun `the switch is offered under System and Dark, not Light`() {
        assertEquals(listOf(ThemeChoice.System, ThemeChoice.Dark), ThemeChoice.entries.filter { it.offersPureBlack })
    }

    @Test
    fun `the accent is Telegram blue until one is picked, and outlives a sign out`() = runBlocking {
        assertEquals(Accent.Telegram, settings.accent.first())
        assertEquals(Accent.Telegram, Accent.from("Nonsense"))
        assertEquals(Accent.Custom, Accent.entries.last())
        assertTrue(Accent.Monochrome in Accent.entries)
        settings.setAccent(Accent.Red)
        settings.clearEverything()
        assertEquals(Accent.Red, settings.accent.first())
    }
}
