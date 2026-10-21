package com.tmplayer.ui.theme

import androidx.compose.runtime.Composable

/**
 * In the browser the page itself loads Noto Sans, the emoji and the CJK, Indic and Arabic subsets
 * into Skia's font collection as fallbacks (web/.../WebFonts.kt), the UI language's first, so the
 * screens need nothing more here than the desktop's own resolver would give them.
 */
@Composable
internal fun ProvideFallbackFonts(content: @Composable () -> Unit) = content()
