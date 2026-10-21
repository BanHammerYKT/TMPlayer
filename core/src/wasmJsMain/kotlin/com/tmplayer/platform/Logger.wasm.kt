package com.tmplayer.platform

private fun consoleWarn(line: String): Unit = js("console.warn(line)")

internal actual fun printError(line: String, error: Throwable?) {
    val text = if (error == null) line else "$line: ${error.message}"
    consoleWarn(text)
    RecentLog.add(text)
}

/**
 * The page's last log lines, kept in memory for Settings' Save logs (LogExport scrubs them before
 * they are written): a browser has no log file, and the console cannot be read back.
 */
object RecentLog {
    private const val KEPT = 2_000
    private val lines = ArrayDeque<String>()

    fun add(line: String) {
        lines.addLast("${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date(nowMillis()))} $line")
        while (lines.size > KEPT) lines.removeFirst()
    }

    fun recent(max: Int): List<String> = lines.toList().takeLast(max)
}
