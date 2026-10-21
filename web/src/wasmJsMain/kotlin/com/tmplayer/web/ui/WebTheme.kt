package com.tmplayer.web.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.tmplayer.i18n.Messages
import com.tmplayer.i18n.Translator
import com.tmplayer.web.js.TmHost

/** The current language's strings, for Compose: recomposes when the language changes. */
val LocalStrings = staticCompositionLocalOf<Messages> { Translator.messages }

/** The look the viewer picked in Settings: follow the device, or always dark or light, and black. */
enum class ThemeMode { System, Dark, Light }

private fun TextStyle.with(family: FontFamily?): TextStyle = if (family == null) this else copy(fontFamily = family)

private fun typography(family: FontFamily?): Typography {
    val t = Typography()
    return Typography(
        displayLarge = t.displayLarge.with(family),
        displayMedium = t.displayMedium.with(family),
        displaySmall = t.displaySmall.with(family),
        headlineLarge = t.headlineLarge.with(family),
        headlineMedium = t.headlineMedium.with(family),
        headlineSmall = t.headlineSmall.with(family),
        titleLarge = t.titleLarge.with(family),
        titleMedium = t.titleMedium.with(family),
        titleSmall = t.titleSmall.with(family),
        bodyLarge = t.bodyLarge.with(family),
        bodyMedium = t.bodyMedium.with(family),
        bodySmall = t.bodySmall.with(family),
        labelLarge = t.labelLarge.with(family),
        labelMedium = t.labelMedium.with(family),
        labelSmall = t.labelSmall.with(family),
    )
}

/** Material's shape scale, which is the apps' Corner scale. */
private val TmShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun WebTheme(
    family: FontFamily?,
    dark: Boolean,
    black: Boolean,
    content: @Composable () -> Unit,
) {
    val messages by Translator.active.collectAsState()
    val scheme = when {
        dark && black -> WebSchemes.Black
        dark -> WebSchemes.Dark
        else -> WebSchemes.Light
    }
    LaunchedEffect(scheme, dark) {
        val argb = scheme.background.toArgb()
        val hex = "#" + (argb and 0xFFFFFF).toString(16).padStart(6, '0')
        TmHost.setThemeColor(hex, dark)
    }
    CompositionLocalProvider(
        LocalStrings provides messages,
        LocalLayoutDirection provides if (messages.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        MaterialTheme(colorScheme = scheme, typography = typography(family), shapes = TmShapes, content = content)
    }
}
