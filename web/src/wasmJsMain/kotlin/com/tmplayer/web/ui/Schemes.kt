package com.tmplayer.web.ui

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * The palette of the apps (ui/src/shared/.../theme/Palette.kt, TmSchemes), until the web build
 * compiles that file itself: the shared UI needs parts of :core that are not yet in its pure half.
 */
internal object WebSchemes {
    val Dark: ColorScheme = darkColorScheme(
        primary = Color(0xFF79D0F5),
        onPrimary = Color(0xFF003546),
        primaryContainer = Color(0xFF004D64),
        onPrimaryContainer = Color(0xFFBEE9FF),
        inversePrimary = Color(0xFF00677F),
        secondary = Color(0xFFB4CAD6),
        onSecondary = Color(0xFF1F333C),
        secondaryContainer = Color(0xFF354A53),
        onSecondaryContainer = Color(0xFFD0E6F2),
        tertiary = Color(0xFFC6C2EA),
        onTertiary = Color(0xFF2F2D4D),
        tertiaryContainer = Color(0xFF464364),
        onTertiaryContainer = Color(0xFFE3DFFF),
        error = Color(0xFFFFB4AB),
        onError = Color(0xFF690005),
        errorContainer = Color(0xFF93000A),
        onErrorContainer = Color(0xFFFFDAD6),
        background = Color(0xFF0C0E10),
        onBackground = Color(0xFFF2F4F5),
        surface = Color(0xFF0C0E10),
        onSurface = Color(0xFFF2F4F5),
        surfaceVariant = Color(0xFF40484C),
        onSurfaceVariant = Color(0xFFAEB5BB),
        surfaceContainerLowest = Color(0xFF08090A),
        surfaceContainerLow = Color(0xFF15181B),
        surfaceContainer = Color(0xFF1A1D20),
        surfaceContainerHigh = Color(0xFF23272A),
        surfaceContainerHighest = Color(0xFF2D3134),
        surfaceBright = Color(0xFF33373A),
        surfaceDim = Color(0xFF0C0E10),
        outline = Color(0xFF8A9297),
        outlineVariant = Color(0xFF2E3337),
        inverseSurface = Color(0xFFDEE3E7),
        inverseOnSurface = Color(0xFF2B3134),
        scrim = Color(0xFF000000),
    )

    /** The same scheme in daylight. */
    val Light: ColorScheme = lightColorScheme(
        primary = Color(0xFF00677F),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFB6EAFF),
        onPrimaryContainer = Color(0xFF001F28),
        inversePrimary = Color(0xFF79D0F5),
        secondary = Color(0xFF4C616B),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFCFE6F1),
        onSecondaryContainer = Color(0xFF071E26),
        tertiary = Color(0xFF5D5B7D),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFE3DFFF),
        onTertiaryContainer = Color(0xFF1A1836),
        error = Color(0xFFBA1A1A),
        onError = Color(0xFFFFFFFF),
        errorContainer = Color(0xFFFFDAD6),
        onErrorContainer = Color(0xFF410002),
        background = Color(0xFFF6FAFD),
        onBackground = Color(0xFF171C1F),
        surface = Color(0xFFF6FAFD),
        onSurface = Color(0xFF171C1F),
        surfaceVariant = Color(0xFFDCE4E9),
        onSurfaceVariant = Color(0xFF40484C),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF0F4F8),
        surfaceContainer = Color(0xFFEAEEF2),
        surfaceContainerHigh = Color(0xFFE4E9EC),
        surfaceContainerHighest = Color(0xFFDEE3E7),
        surfaceBright = Color(0xFFF6FAFD),
        surfaceDim = Color(0xFFD6DBDF),
        outline = Color(0xFF70787D),
        outlineVariant = Color(0xFFC0C8CD),
        inverseSurface = Color(0xFF2B3134),
        inverseOnSurface = Color(0xFFECF1F5),
        scrim = Color(0xFF000000),
    )


    val Black: ColorScheme = Dark.copy(
        background = Color(0xFF000000),
        surface = Color(0xFF000000),
        surfaceDim = Color(0xFF000000),
        surfaceContainerLowest = Color(0xFF000000),
        surfaceContainerLow = Color(0xFF0A0B0C),
        surfaceContainer = Color(0xFF111315),
    )
}
