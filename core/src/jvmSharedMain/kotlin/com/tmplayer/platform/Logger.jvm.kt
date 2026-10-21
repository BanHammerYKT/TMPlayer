package com.tmplayer.platform

internal actual fun printError(line: String, error: Throwable?) {
    System.err.println(line)
    error?.printStackTrace()
}
