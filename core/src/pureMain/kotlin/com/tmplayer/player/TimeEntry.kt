package com.tmplayer.player

/**
 * A typed time, for the desktop player's "Go to a time": where in the video the text points.
 *
 * What it reads, with or without spaces:
 * - a clock: `1:23:45`, `83:10`, `0:45` (minutes may run past 59 when there is no hour part);
 * - units: `1h20m`, `1h 20m 5s`, `90s`, `45m`, `2h` (`min` and `sec` too);
 * - a bare number, as minutes: `45` is 45:00, since nobody types seconds to travel;
 * - a percentage of the length: `50%`, `12.5%`;
 * - any of those after `+` or `-`, as a step from where the video is.
 *
 * Digits in any script count (Arabic, Persian, Devanagari, full width), and so do the full width
 * colon and the Arabic percent sign, so a viewer typing in their own keyboard's digits is not
 * refused. Null for text that is none of these, or for a place past the end of a video whose length
 * is known; a step is kept inside the video instead, as a key's step is.
 */
object TimeEntry {

    fun parse(text: String, durationMs: Long, positionMs: Long = 0): Long? {
        var t = normalise(text)
        if (t.isEmpty()) return null
        val sign = when (t[0]) {
            '+' -> 1
            '-' -> -1
            else -> 0
        }
        if (sign != 0) t = t.substring(1).trim()
        if (t.isEmpty()) return null
        val amount = amount(t, durationMs) ?: return null
        if (sign != 0) {
            val target = positionMs + sign * amount
            val upper = if (durationMs > 0) durationMs else Long.MAX_VALUE
            return target.coerceIn(0, upper)
        }
        if (durationMs > 0 && amount > durationMs) return null
        return amount
    }

    private fun amount(t: String, durationMs: Long): Long? {
        if (t.endsWith('%')) {
            if (durationMs <= 0) return null
            val number = t.dropLast(1).trim().replace(',', '.').toDoubleOrNull() ?: return null
            if (number < 0 || number > 100) return null
            return (durationMs * number / 100).toLong()
        }
        if (':' in t) {
            val parts = t.split(':').map { it.trim() }
            if (parts.size !in 2..3 || parts.any { it.isEmpty() || !it.all(Char::isDigit) }) return null
            val numbers = parts.map { it.toLongOrNull() ?: return null }
            val (h, m, s) = if (numbers.size == 3) numbers else listOf(0L) + numbers
            if (s >= 60) return null
            if (numbers.size == 3 && m >= 60) return null
            return ((h * 60 + m) * 60 + s) * 1000
        }
        if (t.all(Char::isDigit)) return t.toLongOrNull()?.let { it * 60_000 }
        val units = UNITS.findAll(t).toList()
        if (units.isEmpty() || units.joinToString("") { it.value } != t) return null
        var total = 0L
        val seen = mutableSetOf<Char>()
        for (u in units) {
            val number = u.groupValues[1].toLongOrNull() ?: return null
            val unit = u.groupValues[2][0]
            if (!seen.add(unit)) return null
            total += number * when (unit) {
                'h' -> 3_600_000L
                'm' -> 60_000L
                else -> 1_000L
            }
        }
        return total
    }

    /** Lower case, ASCII digits, the plain colon and percent sign, inner spaces gone. */
    private fun normalise(text: String): String = buildString {
        for (c in text.trim().lowercase()) {
            when {
                c.isDigit() -> append(c.digitToInt())
                c == '：' -> append(':')
                c == '٪' || c == '％' -> append('%')
                c == '＋' -> append('+')
                c == '−' || c == '－' -> append('-')
                c == '٫' -> append('.')
                c.isWhitespace() -> Unit
                else -> append(c)
            }
        }
    }

    private val UNITS = Regex("(\\d+)(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)")
}
