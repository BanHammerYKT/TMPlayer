package com.tmplayer.ui.auth

/**
 * The browser's region, for [CountryGuess] when Telegram will not say: the first language the
 * visitor asked for that names one ("en-IN" gives IN). The app's own language says nothing about
 * where somebody is, and the browser's location would need a permission prompt, so neither is used.
 */
fun platformRegion(): String = CountryGuess.regionOf(browserLanguages().split(','))

private fun browserLanguages(): String =
    js("(navigator.languages && navigator.languages.length ? Array.from(navigator.languages) : [navigator.language || '']).join(',')")
