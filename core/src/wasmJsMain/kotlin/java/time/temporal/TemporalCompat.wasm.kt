// The JDK as far as the browser build of :core's JVM half needs it (core/web-sources.txt):
// small stand ins for the JDK types those files name, on the browser's own facilities.
package java.time.temporal

import java.time.LocalDate

enum class ChronoUnit {
    DAYS;

    fun between(a: LocalDate, b: LocalDate): Long = b.epochDay - a.epochDay
}
