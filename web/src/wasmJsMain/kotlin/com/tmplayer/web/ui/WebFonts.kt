package com.tmplayer.web.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import com.tmplayer.web.js.TmHost
import com.tmplayer.web.js.text
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Fonts for a canvas that has none of the system's: Skia in the browser draws only what it is
 * given. Noto Sans (Latin, Cyrillic, Greek, Vietnamese) comes first and is waited for; the emoji
 * and the CJK, Indic and Arabic subsets the desktop ships come after the first paint, as
 * fallbacks, so the first screen is not held up by megabytes most viewers never need.
 */
object WebFonts {
    private val WEIGHTS = listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold)

    @OptIn(ExperimentalEncodingApi::class)
    suspend fun bytes(path: String): ByteArray? {
        val b64 = TmHost.fetchBase64(path).text()
        if (b64.isEmpty()) return null
        return runCatching { Base64.decode(b64) }.getOrNull()
    }

    /** One variable font file at the weights the app uses. */
    fun family(name: String, data: ByteArray): FontFamily = FontFamily(
        WEIGHTS.map { w ->
            Font(
                identity = "$name-${w.weight}",
                data = data,
                weight = w,
                variationSettings = FontVariation.Settings(FontVariation.weight(w.weight)),
            )
        },
    )

    /** The text face; null only when the file could not be fetched, and Skia's default is used. */
    suspend fun main(): FontFamily? = bytes("fonts/NotoSans.ttf")?.let { family("NotoSans", it) }

    /** The fallbacks, in the order Skia should try them: emoji, then the script subsets. */
    val FALLBACKS = listOf(
        "NotoColorEmoji",
        "NotoSansArabicUI-Subset", "NotoSansDevanagari-Subset", "NotoSansBengali-Subset",
        "NotoSansMalayalam-Subset", "NotoSansJP-Subset", "NotoSansSC-Subset", "NotoSansTC-Subset",
        "NotoSansKR-Subset",
    )

    /** Which subsets a language needs at once rather than in the background. */
    fun firstFor(language: String): List<String> = when (language.substringBefore('-')) {
        "ar", "fa" -> listOf("NotoSansArabicUI-Subset")
        "hi" -> listOf("NotoSansDevanagari-Subset")
        "bn" -> listOf("NotoSansBengali-Subset")
        "ml" -> listOf("NotoSansMalayalam-Subset")
        "ja" -> listOf("NotoSansJP-Subset")
        "ko" -> listOf("NotoSansKR-Subset")
        "zh" -> if (language.contains("TW", ignoreCase = true) || language.contains("Hant")) listOf("NotoSansTC-Subset") else listOf("NotoSansSC-Subset")
        else -> emptyList()
    }
}
