package com.tmplayer.web.data

import com.tmplayer.web.js.TmHost
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The viewer's choices in this browser, kept in its local storage under `tm.` keys. Small, and
 * only for this browser: nothing here reaches a server. Moves onto :core's shared settings store
 * (the same keys the apps back up) once that is in the pure half.
 */
class WebPrefs {
    private fun read(key: String): String? = TmHost.getItem("tm.$key")
    private fun write(key: String, value: String?) {
        if (value == null) TmHost.removeItem("tm.$key") else TmHost.setItem("tm.$key", value)
    }

    private inner class Pref<T>(private val key: String, default: T, private val decode: (String) -> T?, private val encode: (T) -> String) {
        private val flow = MutableStateFlow(read(key)?.let(decode) ?: default)
        val state: StateFlow<T> = flow.asStateFlow()
        var value: T
            get() = flow.value
            set(v) {
                flow.value = v
                write(key, encode(v))
            }
    }

    private val languagePref = Pref("language", "", { it }, { it })
    private val themePref = Pref("theme", "system", { it }, { it })
    private val blackPref = Pref("black", false, { it == "1" }, { if (it) "1" else "0" })

    /** The UI language tag, or "" to follow the browser. */
    var language: String
        get() = languagePref.value
        set(v) {
            languagePref.value = v
        }
    val languageState: StateFlow<String> get() = languagePref.state

    /** "system", "dark" or "light". */
    var theme: String
        get() = themePref.value
        set(v) {
            themePref.value = v
        }
    val themeState: StateFlow<String> get() = themePref.state

    /** Pure black ground in the dark theme. */
    var black: Boolean
        get() = blackPref.value
        set(v) {
            blackPref.value = v
        }
    val blackState: StateFlow<Boolean> get() = blackPref.state

    fun string(key: String): String? = read(key)
    fun setString(key: String, value: String?) = write(key, value)
}
