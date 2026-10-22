package com.tmplayer.ui.auth

import java.util.Locale

/**
 * This computer's region, for [CountryGuess] when Telegram will not say: the locale's country,
 * blank when the locale names none. The browser has its own (PlatformRegion.wasm.kt).
 */
fun platformRegion(): String = Locale.getDefault().country.uppercase(Locale.ROOT)
