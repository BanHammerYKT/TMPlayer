package com.tmplayer.web.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.tmplayer.data.AuthState
import com.tmplayer.web.WebAppState
import com.tmplayer.web.js.TmHost

@Composable
fun WebApp(app: WebAppState, family: FontFamily?, firstFallbacks: List<FontFamily>) {
    val theme by app.prefs.themeState.collectAsState()
    val black by app.prefs.blackState.collectAsState()
    val dark = when (theme) {
        "dark" -> true
        "light" -> false
        else -> TmHost.prefersDark()
    }
    WebTheme(family, dark, black) {
        val resolver = LocalFontFamilyResolver.current
        LaunchedEffect(Unit) {
            // The script the UI language needs first, then the emoji and every other script, as
            // fallbacks Skia reaches for a character Noto Sans does not have.
            for (f in firstFallbacks) runCatching { resolver.preload(f) }
            for (name in WebFonts.FALLBACKS) {
                val bytes = WebFonts.bytes("fonts/$name.ttf") ?: continue
                runCatching { resolver.preload(WebFonts.family(name, bytes)) }
            }
        }
        val auth by app.auth.state.collectAsState()
        val elsewhere by app.auth.elsewhere.collectAsState()
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            when {
                elsewhere -> OtherTab(onUseHere = app.auth::useHere)
                auth == AuthState.Ready -> Shell(app)
                else -> SignInScreen(app.auth)
            }
        }
    }
}

@Composable
private fun OtherTab(onUseHere: () -> Unit) {
    val s = LocalStrings.current
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 420.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(s.webOtherTabTitle, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text(s.webOtherTabBody, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            Button(onClick = onUseHere, modifier = Modifier.height(48.dp)) { Text(s.webUseHere) }
        }
    }
}
