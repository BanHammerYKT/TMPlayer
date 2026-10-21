// The JDK as far as the browser build of :core's JVM half needs it (core/web-sources.txt):
// small stand ins for the JDK types those files name, on the browser's own facilities.
package java.time

private fun offsetMinutes(ms: Double): Int = js("new Date(ms).getTimezoneOffset()")

/** The device's time zone, which is all the shared code asks for. */
class ZoneId private constructor() {
    companion object {
        fun systemDefault(): ZoneId = ZoneId()
    }
}

class Instant private constructor(val epochMilli: Long) {
    fun atZone(@Suppress("UNUSED_PARAMETER") zone: ZoneId): ZonedDateTime = ZonedDateTime(epochMilli)
    fun toEpochMilli(): Long = epochMilli

    companion object {
        fun ofEpochMilli(ms: Long): Instant = Instant(ms)
    }
}

class ZonedDateTime internal constructor(private val epochMilli: Long) {
    fun toInstant(): Instant = Instant.ofEpochMilli(epochMilli)

    fun toLocalDate(): LocalDate {
        val local = epochMilli - offsetMinutes(epochMilli.toDouble()) * 60_000L
        return LocalDate(kotlin.math.floor(local / 86_400_000.0).toLong())
    }
}

data class LocalDate(val epochDay: Long) : Comparable<LocalDate> {
    override fun compareTo(other: LocalDate): Int = epochDay.compareTo(other.epochDay)

    /** ISO 8601, "2026-10-21", as the JDK writes it. */
    override fun toString(): String = java.text.SimpleDateFormat("yyyy-MM-dd").format(java.util.Date(epochDay * 86_400_000L))

    /** The start of this day in the device's time zone. */
    fun atStartOfDay(@Suppress("UNUSED_PARAMETER") zone: ZoneId): ZonedDateTime {
        val utc = epochDay * 86_400_000L
        return ZonedDateTime(utc + offsetMinutes(utc.toDouble()) * 60_000L)
    }

    companion object {
        /** "2026-10-14", as a provider writes a date. */
        fun parse(text: String): LocalDate {
            val (y, m, d) = text.trim().split('-').map { it.toLong() }
            val yy = if (m <= 2) y - 1 else y
            val era = (if (yy >= 0) yy else yy - 399) / 400
            val yoe = yy - era * 400
            val doy = (153 * (if (m > 2) m - 3 else m + 9) + 2) / 5 + d - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            return LocalDate(era * 146097 + doe - 719468)
        }

        fun now(): LocalDate = Instant.ofEpochMilli(com.tmplayer.platform.nowMillis()).atZone(ZoneId.systemDefault()).toLocalDate()
    }
}

/** A moment on the device's clock, for the log export's file name and header (LogExport). */
class LocalDateTime private constructor(private val localMilli: Long) {
    /** The pattern's yyyy, MM, dd, HH, mm and ss, which is all LogExport writes. */
    fun format(formatter: java.time.format.DateTimeFormatter): String =
        java.text.SimpleDateFormat(formatter.pattern).format(java.util.Date(localMilli))

    companion object {
        fun now(): LocalDateTime {
            val utc = com.tmplayer.platform.nowMillis()
            return LocalDateTime(utc - offsetMinutes(utc.toDouble()) * 60_000L)
        }
    }
}
