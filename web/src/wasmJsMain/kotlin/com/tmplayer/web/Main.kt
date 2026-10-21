package com.tmplayer.web

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import com.tmplayer.i18n.Languages
import com.tmplayer.i18n.Translator
import com.tmplayer.web.js.TmHost
import com.tmplayer.web.js.text
import com.tmplayer.web.ui.WebApp
import com.tmplayer.web.ui.WebFonts
import kotlinx.browser.document
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

/**
 * The page's entry point: the UI catalogs and the text font first (nothing can be drawn without
 * them), then Compose takes the page over, TDLib starts, and the fallback fonts load behind it.
 */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    val scope = MainScope()
    scope.launch {
        val app = WebAppState(scope)
        val system = listOf(TmHost.language())
        val tag = Languages.resolve(app.prefs.language, system)
        Translator.provide(Languages.ENGLISH, TmHost.fetchText("i18n/en.json").text())
        if (tag != Languages.ENGLISH) {
            TmHost.fetchText("i18n/$tag.json").text().takeIf { it.isNotBlank() }?.let { Translator.provide(tag, it) }
        }
        Translator.select(app.prefs.language, system)
        val family = WebFonts.main()
        val firstFallbacks = WebFonts.firstFor(tag).mapNotNull { name ->
            WebFonts.bytes("fonts/$name.ttf")?.let { WebFonts.family(name, it) }
        }
        com.tmplayer.web.player.WebPlayer.installStrings()
        app.start()
        ComposeViewport(document.getElementById("app")!!) {
            WebApp(app, family, firstFallbacks)
        }
        TmHost.ready()
        TmHost.registerWorker()
    }
}
