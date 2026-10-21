package com.tmplayer.platform

private fun consoleWarn(line: String): Unit = js("console.warn(line)")

internal actual fun printError(line: String, error: Throwable?) {
    consoleWarn(if (error == null) line else "$line: ${error.message}")
}
