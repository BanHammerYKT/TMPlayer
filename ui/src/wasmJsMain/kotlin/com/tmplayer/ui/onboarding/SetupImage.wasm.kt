package com.tmplayer.ui.onboarding

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.tmplayer.data.decodeImage
import com.tmplayer.ui.theme.LocalDarkTheme
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.js.Promise
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.await
import kotlinx.coroutines.launch

// The setup's and the tour's pictures in the browser: the desktop's own files
// (ui/src/jvmMain/resources/onboarding), which the web build serves under onboarding/
// (web/build.gradle.kts, copyOnboarding). A page has no classpath to read them from at once, so
// all of them are fetched the first time the setup or the tour asks for one, and each is drawn
// as soon as it has arrived; the tour comes after the setup, so its pictures are there by then.

@JsFun("(url) => window.TmHost.fetchBase64(url)")
private external fun fetchBase64(url: String): Promise<JsString>

private object OnboardingImages {
    /** The decoded pictures by file name; a name fetched without success is not kept, and not retried. */
    val loaded = mutableStateMapOf<String, ImageBitmap>()
    private var started = false

    @OptIn(ExperimentalEncodingApi::class)
    fun start() {
        if (started) return
        started = true
        val names = buildList {
            for (dark in listOf(false, true)) {
                SetupChoice.entries.mapNotNullTo(this) { setupImageName(it, dark) }
                TourPage.entries.mapNotNullTo(this) { tourImageName(it, dark) }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        for (name in names) {
            scope.launch {
                val text = runCatching { fetchBase64("onboarding/$name").await<JsString>().toString() }.getOrDefault("")
                if (text.isEmpty()) return@launch
                runCatching { decodeImage(Base64.Default.decode(text)) }.getOrNull()?.let { loaded[name] = it }
            }
        }
    }

    @Composable
    fun image(name: String?): ImageBitmap? {
        LaunchedEffect(Unit) { start() }
        return name?.let { loaded[it] }
    }
}

/** The picture of an answer, as the desktop's: the marked shot, light or dark to match. */
@Composable
internal fun SetupShot(choice: SetupChoice, modifier: Modifier = Modifier) {
    val image = OnboardingImages.image(setupImageName(choice, LocalDarkTheme.current)) ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
    )
}

/** The file name of an answer's picture: the desktop's, since the browser draws the desktop's screens. */
fun setupImageName(choice: SetupChoice, dark: Boolean): String? {
    val shot = when (choice) {
        SetupChoice.PostersOn -> "posters-on"
        SetupChoice.PostersOff -> "posters-off"
        SetupChoice.FullQuality -> "performance-full"
        SetupChoice.Lighter -> "performance-lighter"
        SetupChoice.KeepWhilePlaying, SetupChoice.LowStorage, SetupChoice.SaveData, SetupChoice.NoLimits,
        SetupChoice.ApplyRecommended, SetupChoice.NotNow, SetupChoice.CrashReportsOff, SetupChoice.CrashReportsOn -> return null
    }
    return "$shot-${if (dark) "dark" else "light"}.webp"
}

/** A tour page's picture, shown whole, since the badges over it are placed by fractions of it. */
@Composable
internal fun TourShot(page: TourPage, modifier: Modifier = Modifier) {
    val image = OnboardingImages.image(tourImageName(page, LocalDarkTheme.current)) ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
}

/** The size of a tour page's picture, for laying out the badges over it; null until it has arrived. */
@Composable
internal fun tourShotSize(page: TourPage): androidx.compose.ui.geometry.Size? =
    OnboardingImages.image(tourImageName(page, LocalDarkTheme.current))
        ?.let { androidx.compose.ui.geometry.Size(it.width.toFloat(), it.height.toFloat()) }

/** The file name of a tour page's picture, as on the desktop; null for the television's own page. */
fun tourImageName(page: TourPage, dark: Boolean): String? =
    if (page == TourPage.TvHome) null else "tour-${page.imageName}-${if (dark) "dark" else "light"}.webp"
