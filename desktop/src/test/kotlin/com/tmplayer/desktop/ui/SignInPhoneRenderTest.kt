package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.runComposeUiTest
import com.tmplayer.data.AuthState
import com.tmplayer.ui.auth.CountryGuess
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The number step while the country is being guessed: the code and the number are closed and a
 * shimmer stands where the country and its code go, with no words on it; once the guess is in, or
 * the time is up (here TDLib never answers), both open.
 */
@OptIn(ExperimentalTestApi::class)
class SignInPhoneRenderTest {

    @Test
    fun `the number waits for the country, then opens`() = runComposeUiTest {
        // The shimmer never stops, so the clock is driven by hand rather than waited idle.
        mainClock.autoAdvance = false
        setContent {
            TmMaterialTheme(dark = true) {
                Surface(Modifier.fillMaxSize(), color = Tone.background) { SignInScreen(AuthState.Phone()) }
            }
        }
        mainClock.advanceTimeBy(200)
        // A closed field has no set text action, so the fields are found by their text instead.
        val fields = onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.EditableText))
        assertEquals(2, fields.fetchSemanticsNodes().size)
        fields[0].assertIsNotEnabled()
        fields[1].assertIsNotEnabled()
        assertTrue(onAllNodesWithContentDescription("Finding", substring = true).fetchSemanticsNodes().isNotEmpty())
        save("signin-phone-detecting.png")

        // The guess's time limit runs on the effect's own clock, so frames are stepped until it
        // has passed (TDLib is not running here, so nothing ever answers) and the fade is done.
        waitUntil(timeoutMillis = CountryGuess.LIMIT_MS + 4_000) {
            mainClock.advanceTimeByFrame()
            fields.fetchSemanticsNodes().none { SemanticsProperties.Disabled in it.config }
        }
        mainClock.advanceTimeBy(500)
        fields[0].assertIsEnabled()
        fields[1].assertIsEnabled()
        assertTrue(onAllNodesWithContentDescription("Finding", substring = true).fetchSemanticsNodes().isEmpty())
        save("signin-phone-ready.png")
    }

    private fun androidx.compose.ui.test.ComposeUiTest.save(name: String) {
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        val image = onNode(isRoot()).captureToImage().toAwtImage()
        javax.imageio.ImageIO.write(image, "png", File(out).apply { mkdirs() }.resolve(name))
    }
}
