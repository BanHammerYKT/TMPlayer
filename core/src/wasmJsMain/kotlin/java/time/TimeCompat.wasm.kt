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
    fun toLocalDate(): LocalDate {
        val local = epochMilli - offsetMinutes(epochMilli.toDouble()) * 60_000L
        return LocalDate(kotlin.math.floor(local / 86_400_000.0).toLong())
    }
}

data class LocalDate(val epochDay: Long) : Comparable<LocalDate> {
    override fun compareTo(other: LocalDate): Int = epochDay.compareTo(other.epochDay)
}
