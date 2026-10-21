// The JDK as far as the browser build of :core's JVM half needs it (core/web-sources.txt):
// small stand ins for the JDK types those files name, on the browser's own facilities.
package java.text

import java.util.Date
import java.util.Locale

/** The patterns the shared code formats dates with ("yyyy-MM-dd", "HH:mm"), in UTC. */
class SimpleDateFormat(private val pattern: String, @Suppress("UNUSED_PARAMETER") locale: Locale = Locale.ROOT) {
    fun format(date: Date): String {
        val ms = date.time
        val days = Math.floorDiv(ms, 86_400_000L)
        val secs = Math.floorMod(ms, 86_400_000L) / 1000
        val (y, m, d) = civil(days)
        fun two(n: Long) = n.toString().padStart(2, '0')
        return pattern
            .replace("yyyy", y.toString())
            .replace("MM", two(m))
            .replace("dd", two(d))
            .replace("HH", two(secs / 3600))
            .replace("mm", two(secs % 3600 / 60))
            .replace("ss", two(secs % 60))
    }

    private fun civil(z0: Long): Triple<Long, Long, Long> {
        val z = z0 + 719468
        val era = Math.floorDiv(z, 146097L)
        val doe = z - era * 146097
        val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        return Triple(yoe + era * 400 + if (m <= 2) 1 else 0, m, d)
    }
}

private object Math {
    fun floorDiv(a: Long, b: Long): Long = kotlin.math.floor(a.toDouble() / b).toLong()
    fun floorMod(a: Long, b: Long): Long = a - floorDiv(a, b) * b
}
