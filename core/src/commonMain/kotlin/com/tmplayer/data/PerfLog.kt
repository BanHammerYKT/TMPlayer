package com.tmplayer.data

/**
 * Timings for the moments that make the app feel fast or slow: Home's first rows after a cold
 * start, a poster's small copy and its sharp one. One line each, tagged `tm-perf`, through
 * [com.tmplayer.platform.Logger] (logcat on Android, standard error on the desktop), so a device
 * pass can read them with `adb logcat -s tm-perf`. Nothing is sent anywhere.
 */
object PerfLog {

    /** When the process started, as near as this object can tell: the first touch of it. */
    val processStart: Long = System.nanoTime()

    /** A few lines an app start; on by default, as the app's other log lines are. */
    @Volatile
    var enabled: Boolean = true

    /** "[what] in N ms" since [since], a [System.nanoTime] reading. */
    fun mark(what: String, since: Long) {
        if (!enabled) return
        com.tmplayer.platform.Logger.i("tm-perf", "$what in ${(System.nanoTime() - since) / 1_000_000} ms")
    }

    /** "[what] at N ms" after the process started. */
    fun sinceStart(what: String) = mark("$what (since start)", processStart)
}
