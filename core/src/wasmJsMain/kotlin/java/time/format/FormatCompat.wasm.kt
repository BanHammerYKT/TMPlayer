// The JDK as far as the browser build of :core's JVM half needs it (core/web-sources.txt).
package java.time.format

/** A date pattern, applied by LocalDateTime.format with the fields SimpleDateFormat knows here. */
class DateTimeFormatter private constructor(val pattern: String) {
    companion object {
        fun ofPattern(pattern: String): DateTimeFormatter = DateTimeFormatter(pattern)
    }
}
