package com.tmplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.tmplayer.data.Accent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every accent (2026-10-19) in light, dark and pure black: words on the accent pass WCAG AA, and
 * the accent itself (a focus ring in daylight, a switch, a progress bar, the selected tab, the
 * Play pill) stands out from the ground and the raised surfaces by 3:1, the figure for a control.
 */
class AccentContrastTest {

    private data class Mode(val name: String, val dark: Boolean, val black: Boolean)

    private val modes = listOf(Mode("light", false, false), Mode("dark", true, false), Mode("black", true, true))

    private fun each(check: (String, ColorScheme, Boolean) -> Unit) {
        for (accent in Accent.entries) {
            for (mode in modes) check("${accent.name} ${mode.name}", TmSchemes.of(mode.dark, mode.black, accent), mode.dark)
        }
    }

    private fun atLeast(what: String, ratio: Float, a: Color, b: Color) {
        val got = contrastRatio(a, b)
        assertTrue("$what: $got, wanted $ratio", got >= ratio)
    }

    @Test
    fun `the contrast ratio is WCAG's`() {
        assertEquals(21f, contrastRatio(Color.Black, Color.White), 0.01f)
        assertEquals(1f, contrastRatio(Color.Red, Color.Red), 0.001f)
        assertEquals(4.79f, contrastRatio(Color(0xFFE50914), Color.White), 0.02f)
    }

    @Test
    fun `text on the accent and its containers passes AA`() = each { name, s, dark ->
        atLeast("$name onPrimary", 4.5f, s.onPrimary, s.primary)
        atLeast("$name onPrimaryContainer", 4.5f, s.onPrimaryContainer, s.primaryContainer)
        atLeast("$name onSecondaryContainer", 4.5f, s.onSecondaryContainer, s.secondaryContainer)
        atLeast("$name onTertiaryContainer", 4.5f, s.onTertiaryContainer, s.tertiaryContainer)
        // Tone.focusFill and Tone.onFocusFill: the primary in the dark, its container in daylight.
        if (dark) atLeast("$name focus", 4.5f, s.onPrimary, s.primary) else atLeast("$name focus", 4.5f, s.onPrimaryContainer, s.primaryContainer)
    }

    @Test
    fun `the accent stands out from the ground and the surfaces`() = each { name, s, _ ->
        for ((ground, colour) in listOf(
            "background" to s.background,
            "surfaceContainerLow" to s.surfaceContainerLow,
            "surfaceContainerHigh" to s.surfaceContainerHigh,
        )) {
            atLeast("$name primary on $ground", 3f, s.primary, colour)
        }
    }

    @Test
    fun `Telegram blue is the palette the app always had`() {
        assertEquals(TmSchemes.Dark, TmSchemes.of(dark = true, accent = Accent.Telegram))
        assertEquals(TmSchemes.Light, TmSchemes.of(dark = false, accent = Accent.Telegram))
        assertEquals(Color(0xFF79D0F5), TmSchemes.of(dark = true).primary)
        assertEquals(Color(0xFF00677F), TmSchemes.of(dark = false).primary)
    }

    @Test
    fun `monochrome has no hue`() {
        for (dark in listOf(true, false)) {
            val s = TmSchemes.of(dark, accent = Accent.Monochrome)
            for (c in listOf(s.primary, s.onPrimary, s.primaryContainer, s.secondary, s.secondaryContainer, s.tertiary, s.tertiaryContainer)) {
                assertTrue("$c is grey", c.red == c.green && c.green == c.blue)
            }
        }
    }

    /** Seeds a viewer might pick: a pale yellow, a deep navy, a near grey, a neon green, a hot pink. */
    private val seeds = listOf(0xFFFFF59D, 0xFF0D1B4C, 0xFF8A8F94, 0xFF39FF14, 0xFFFF1493, 0xFF7C5CFF).map { it.toInt() }

    @Test
    fun `the viewer's own colour is made readable in every mode`() {
        for (seed in seeds) {
            for (mode in modes) {
                val s = TmSchemes.of(mode.dark, mode.black, Accent.Custom).withCustomAccent(Accent.Custom, seed, mode.dark)
                val name = "custom ${Integer.toHexString(seed)} ${mode.name}"
                atLeast("$name onPrimary", 4.5f, s.onPrimary, s.primary)
                atLeast("$name onPrimaryContainer", 4.5f, s.onPrimaryContainer, s.primaryContainer)
                atLeast("$name onSecondaryContainer", 4.5f, s.onSecondaryContainer, s.secondaryContainer)
                atLeast("$name onTertiaryContainer", 4.5f, s.onTertiaryContainer, s.tertiaryContainer)
                for (ground in listOf(s.background, s.surfaceContainerLow, s.surfaceContainerHigh)) {
                    atLeast("$name primary on the ground", 3f, s.primary, ground)
                }
            }
        }
    }

    @Test
    fun `high contrast brings second lines closer to the text`() {
        for (mode in modes) {
            val base = TmSchemes.of(mode.dark, mode.black)
            val high = base.highContrast(true)
            assertTrue(contrastRatio(high.onSurfaceVariant, high.background) > contrastRatio(base.onSurfaceVariant, base.background))
            assertEquals(base.onSurface, high.onSurface)
        }
    }
}
