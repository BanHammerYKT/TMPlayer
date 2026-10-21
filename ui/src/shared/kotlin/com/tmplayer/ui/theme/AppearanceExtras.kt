package com.tmplayer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.Typography
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import com.tmplayer.data.Accent
import com.tmplayer.data.AppTypeface

/**
 * The viewer's own accent colour, made into the roles a scheme needs (REELBOX-PARITY: a custom
 * accent made readable on light and dark). The hue and the strength are the viewer's; the
 * lightness is found for each mode so the primary stands out from the ground and the raised
 * surfaces by 3:1, the words written on it by 4.5:1, and the containers carry their text by 4.5:1,
 * which is the bar every preset accent clears ([TmAccents]).
 */
object TmCustomAccent {

    /** The roles for [seed] in this mode, against the mode's own ground. */
    fun roles(seed: Color, dark: Boolean): AccentRoles {
        val (h, s, _) = hsl(seed)
        val base = if (dark) TmSchemes.Dark else TmSchemes.Light
        val grounds = listOf(base.background, base.surfaceContainerHigh, TmSchemes.Black.background.takeIf { dark } ?: base.background)
        fun tone(l: Float, sat: Float = s, hue: Float = h) = Color.hsl(((hue % 360f) + 360f) % 360f, sat.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
        fun onFor(c: Color): Color = if (contrastRatio(c, Color.White) >= contrastRatio(c, Color.Black)) Color.White else Color.Black
        fun stands(c: Color) = grounds.all { contrastRatio(c, it) >= 3f } && contrastRatio(c, onFor(c)) >= 4.5f
        // In the dark the primary is light: from the seed's own lightness up. In daylight, down.
        val steps = (0..40).map { it / 40f }
        val primary = if (dark) {
            steps.map { 0.55f + it * 0.4f }.map { tone(it) }.firstOrNull(::stands) ?: tone(0.85f)
        } else {
            steps.map { 0.5f - it * 0.35f }.map { tone(it) }.firstOrNull(::stands) ?: tone(0.3f)
        }
        val secondarySat = s * 0.35f
        val tertiaryHue = h + 60f
        return if (dark) {
            AccentRoles(
                primary = primary, onPrimary = onFor(primary),
                primaryContainer = tone(0.24f), onPrimaryContainer = tone(0.9f),
                inversePrimary = tone(0.38f),
                secondary = tone(0.78f, secondarySat), onSecondary = tone(0.15f, secondarySat),
                secondaryContainer = tone(0.26f, secondarySat), onSecondaryContainer = tone(0.9f, secondarySat),
                tertiary = tone(0.78f, s * 0.6f, tertiaryHue), onTertiary = tone(0.15f, s * 0.6f, tertiaryHue),
                tertiaryContainer = tone(0.26f, s * 0.6f, tertiaryHue), onTertiaryContainer = tone(0.9f, s * 0.6f, tertiaryHue),
            )
        } else {
            AccentRoles(
                primary = primary, onPrimary = onFor(primary),
                primaryContainer = tone(0.9f), onPrimaryContainer = tone(0.14f),
                inversePrimary = tone(0.78f),
                secondary = tone(0.38f, secondarySat), onSecondary = Color.White,
                secondaryContainer = tone(0.9f, secondarySat), onSecondaryContainer = tone(0.14f, secondarySat),
                tertiary = tone(0.36f, s * 0.6f, tertiaryHue), onTertiary = Color.White,
                tertiaryContainer = tone(0.9f, s * 0.6f, tertiaryHue), onTertiaryContainer = tone(0.14f, s * 0.6f, tertiaryHue),
            )
        }
    }

    /** Whether [seed] had to be made lighter or darker to be readable in either mode, which the picker says. */
    fun adjusted(seed: Color): Boolean {
        val (_, _, l) = hsl(seed)
        val dark = hsl(roles(seed, dark = true).primary).third
        val light = hsl(roles(seed, dark = false).primary).third
        return kotlin.math.abs(dark - l) > 0.08f || kotlin.math.abs(light - l) > 0.08f
    }

    /** A colour as hue (0 to 360), saturation and lightness (0 to 1). */
    fun hsl(c: Color): Triple<Float, Float, Float> {
        val r = c.red
        val g = c.green
        val b = c.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return Triple(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + (if (g < b) 6f else 0f))
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        return Triple(h, s, l)
    }
}

/** This scheme with the viewer's own accent ([TmCustomAccent]) when [accent] is [Accent.Custom] and one was picked. */
fun ColorScheme.withCustomAccent(accent: Accent, custom: Int?, dark: Boolean): ColorScheme {
    if (accent != Accent.Custom || custom == null) return this
    val r = TmCustomAccent.roles(Color(custom), dark)
    return copy(
        primary = r.primary, onPrimary = r.onPrimary,
        primaryContainer = r.primaryContainer, onPrimaryContainer = r.onPrimaryContainer,
        inversePrimary = r.inversePrimary,
        secondary = r.secondary, onSecondary = r.onSecondary,
        secondaryContainer = r.secondaryContainer, onSecondaryContainer = r.onSecondaryContainer,
        tertiary = r.tertiary, onTertiary = r.onTertiary,
        tertiaryContainer = r.tertiaryContainer, onTertiaryContainer = r.onTertiaryContainer,
    )
}

/**
 * High contrast (REELBOX-PARITY): second lines, hints and edges brought most of the way to the
 * text's own colour, so nothing reads faint in a bright room or to tired eyes. The ground, the
 * text and the accent stay as they are.
 */
fun ColorScheme.highContrast(on: Boolean): ColorScheme = if (!on) this else copy(
    onSurfaceVariant = lerp(onSurfaceVariant, onSurface, 0.6f),
    outline = lerp(outline, onSurface, 0.5f),
    outlineVariant = lerp(outlineVariant, onSurface, 0.35f),
)

/** The font family of an [AppTypeface]; null for the system's own, which is what the styles carry already. */
fun AppTypeface.family(): FontFamily? = when (this) {
    AppTypeface.System -> null
    AppTypeface.Serif -> FontFamily.Serif
    AppTypeface.Typewriter -> FontFamily.Monospace
}

/** Every style of this scale set in [family]; the scale itself when null. */
fun Typography.withFamily(family: FontFamily?): Typography {
    if (family == null) return this
    fun TextStyle.f() = copy(fontFamily = family)
    return copy(
        displayLarge = displayLarge.f(), displayMedium = displayMedium.f(), displaySmall = displaySmall.f(),
        headlineLarge = headlineLarge.f(), headlineMedium = headlineMedium.f(), headlineSmall = headlineSmall.f(),
        titleLarge = titleLarge.f(), titleMedium = titleMedium.f(), titleSmall = titleSmall.f(),
        bodyLarge = bodyLarge.f(), bodyMedium = bodyMedium.f(), bodySmall = bodySmall.f(),
        labelLarge = labelLarge.f(), labelMedium = labelMedium.f(), labelSmall = labelSmall.f(),
    )
}
