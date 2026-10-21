package com.tmplayer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LearntLanguagesTest {

    private val dir = Files.createTempDirectory("tm-learnt").toFile()
    private val settings = SettingsStore(SettingsStore.openDataStore(dir.resolve(SettingsStore.FILE_NAME)))

    @Test
    fun `a series is listed by the name it was watched under, with what was learnt`() = runBlocking {
        settings.setTrackChoice("Kerala Crime Files", TrackChoice(audioLanguage = "mal", textLanguage = "en", subtitlesOn = true))
        settings.setTrackChoice("Aranyak", TrackChoice(audioLanguage = "hin"))
        val learnt = settings.learntLanguages.first()
        assertEquals(listOf("Aranyak", "Kerala Crime Files"), learnt.map { it.name })
        assertEquals("keralacrimefiles", learnt[1].key)
        assertEquals(TrackChoice("mal", "en", true), learnt[1].choice)
        assertEquals("Sound: Malayalam, Subtitles: English", learnt[1].line())
        assertEquals("Sound: Hindi, Subtitles: Off", learnt[0].line())
    }

    @Test
    fun `a memory that says nothing is not listed`() = runBlocking {
        settings.setTrackChoice("A film watched once", TrackChoice())
        assertTrue(settings.learntLanguages.first().isEmpty())
    }

    @Test
    fun `subtitles on with no language, and no sound learnt, read as such`() {
        val line = LearntSeries("x", "x", TrackChoice(subtitlesOn = true)).line()
        assertEquals("Sound: As the video sets it, Subtitles: On", line)
    }

    @Test
    fun `a memory from before names were kept is listed by its key`() {
        val learnt = LearntLanguages.from(mapOf("audio_oldshow" to "eng", "subs_oldshow" to false, "speed_oldshow" to 1.5f))
        assertEquals(listOf("oldshow"), learnt.map { it.name })
    }

    @Test
    fun `forget clears one series and forget all clears every one, nothing else`() = runBlocking {
        settings.setTrackChoice("Kerala Crime Files", TrackChoice(audioLanguage = "mal", subtitlesOn = true))
        settings.setTrackChoice("Aranyak", TrackChoice(audioLanguage = "hin"))
        settings.setSubtitleStyle(com.tmplayer.player.SubtitleStyle(bold = true))

        settings.forgetLearntLanguages("keralacrimefiles")
        assertEquals(listOf("Aranyak"), settings.learntLanguages.first().map { it.name })
        assertEquals(TrackChoice(), settings.trackChoice("Kerala Crime Files"))

        settings.forgetAllLearntLanguages()
        assertTrue(settings.learntLanguages.first().isEmpty())
        assertEquals(TrackChoice(), settings.trackChoice("Aranyak"))
        // The look and every other setting stay.
        assertTrue(settings.subtitleStyleNow().bold)
    }
}
