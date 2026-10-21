package com.tmplayer.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf

private fun reducedQuery(): Boolean = js("(() => { try { return window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) { return false; } })()")

private fun onReducedChange(handler: (Boolean) -> Unit): Unit =
    js("(() => { try { window.matchMedia('(prefers-reduced-motion: reduce)').addEventListener('change', (e) => handler(e.matches)); } catch (e) {} })()")

private object BrowserMotion {
    val reduced = mutableStateOf(reducedQuery())

    init {
        onReducedChange { reduced.value = it }
    }
}

/** True when the device asks for less motion (prefers-reduced-motion), followed live. */
@Composable
fun rememberReducedMotion(): Boolean = BrowserMotion.reduced.value
