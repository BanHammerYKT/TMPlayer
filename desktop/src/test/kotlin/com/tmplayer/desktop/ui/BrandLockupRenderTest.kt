package com.tmplayer.desktop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.use
import com.tmplayer.data.SettingsStore
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.ui.components.BrandLayout
import com.tmplayer.ui.components.BrandLockup
import com.tmplayer.ui.components.BrandSize
import com.tmplayer.ui.components.LocalBrandBadge
import com.tmplayer.ui.lock.PinPad
import com.tmplayer.ui.lock.PinPurpose
import com.tmplayer.ui.theme.TmMaterialTheme
import com.tmplayer.ui.theme.Tone
import org.jetbrains.skia.EncodedImageFormat
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The app's mark with its name ([BrandLockup]): side by side where the row fits, the name under
 * the mark where it does not, in both themes, in Arabic (right to left) and with the browser's
 * Beta word; and the PIN pad that opens the app, which carries it. With TMPLAYER_SHOTS=<dir> the
 * frames are written there as PNGs (brand-*.png).
 */
class BrandLockupRenderTest {

    @After
    fun english() {
        Translator.use(Languages.ENGLISH)
    }

    @Test
    fun `auto sets a row where it fits and stacks where it does not`() {
        val wide = measure(400)
        val narrow = measure(80)
        assertTrue("row is wider than tall: $wide", wide.width > wide.height * 2)
        assertTrue("stacked is taller than the row: $narrow vs $wide", narrow.height > wide.height * 1.5f)
        assertTrue("stacked is narrower than the row: $narrow vs $wide", narrow.width < wide.width)
    }

    @Test
    fun shots() {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            for (rtl in listOf(false, true)) {
                if (rtl) Translator.use("ar") else Translator.use(Languages.ENGLISH)
                val dir = if (rtl) "-ar" else ""
                save("brand-forms-$theme$dir.png", render(720, 640, dark, rtl) { Forms() })
            }
            Translator.use(Languages.ENGLISH)
            save("brand-beta-$theme.png", render(720, 360, dark, badge = "Beta") { Forms(sizes = false) })
            save("brand-pinpad-$theme.png", render(1280, 800, dark) { Pad() })
            save("brand-pinpad-phone-$theme.png", render(360, 740, dark) { Pad() })
        }
    }

    @Composable
    private fun Forms(sizes: Boolean = true) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            BrandLockup(layout = BrandLayout.Row)
            if (sizes) {
                BrandLockup(size = BrandSize.Medium)
                BrandLockup(size = BrandSize.Large)
            }
            // A box narrower than the row: Auto puts the name under the mark.
            Box(Modifier.width(140.dp)) { BrandLockup(size = BrandSize.Medium) }
            BrandLockup(size = BrandSize.Medium, layout = BrandLayout.Stacked)
        }
    }

    @Composable
    private fun Pad() {
        PinPad(settings = settings, purpose = PinPurpose.Check, title = com.tmplayer.i18n.L.lockEnterForApp, onDone = {}, onCancel = null, brand = true)
    }

    private val settings = SettingsStore(
        SettingsStore.openDataStore(Files.createTempDirectory("tm-brand-render").resolve(SettingsStore.FILE_NAME).toFile()),
    )

    private fun measure(width: Int): IntSize {
        var size = IntSize.Zero
        ImageComposeScene(width, 400, Density(1f)) {
            TmMaterialTheme(dark = true) {
                Box(Modifier.width(width.dp)) {
                    BrandLockup(size = BrandSize.Medium, modifier = Modifier.onSizeChanged { size = it })
                }
            }
        }.use { it.render(0) }
        return size
    }

    private fun render(
        w: Int,
        h: Int,
        dark: Boolean,
        rtl: Boolean = false,
        badge: String? = null,
        page: @Composable () -> Unit,
    ): ByteArray =
        ImageComposeScene(w, h, Density(1f)) {
            TmMaterialTheme(dark = dark) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalBrandBadge provides badge,
                ) {
                    Surface(Modifier.fillMaxSize(), color = Tone.background) { page() }
                }
            }
        }.use { scene ->
            scene.render(0)
            Thread.sleep(300)
            scene.render(500_000_000)
            scene.render(1_000_000_000).encodeToData(EncodedImageFormat.PNG)!!.bytes
        }

    private fun save(name: String, png: ByteArray) {
        assertTrue(png.size > 1000)
        val out = System.getenv("TMPLAYER_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        File(out).apply { mkdirs() }.resolve(name).writeBytes(png)
    }
}
