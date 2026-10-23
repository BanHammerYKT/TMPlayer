package com.tmplayer.platform

/** The web player's rules log to the worker's console; the page keeps no log file. */
internal actual fun printError(line: String, error: Throwable?) {
    console.warn(if (error == null) line else "$line: ${error.message}")
}
