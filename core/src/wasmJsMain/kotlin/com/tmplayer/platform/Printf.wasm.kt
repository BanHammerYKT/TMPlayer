package com.tmplayer.platform

/**
 * `String.format` for the browser build, as far as the shared code uses it: `%d` and `%02d`,
 * `%.1f` and `%.0f`, `%s`, `%x`, and `%%`. Numbers in the C locale, as the code asks with Locale.ROOT.
 */
fun printf(format: String, args: Array<out Any?>): String {
    val out = StringBuilder()
    var i = 0
    var next = 0
    while (i < format.length) {
        val c = format[i]
        if (c != '%') {
            out.append(c)
            i++
            continue
        }
        val m = SPEC.find(format, i)
        if (m == null || m.range.first != i) {
            out.append(c)
            i++
            continue
        }
        val flags = m.groupValues[1]
        val width = m.groupValues[2].toIntOrNull() ?: 0
        val precision = m.groupValues[3].removePrefix(".").toIntOrNull()
        val conv = m.groupValues[4]
        val text = when (conv) {
            "%" -> "%"
            "d" -> (args.getOrNull(next++) as? Number)?.toLong()?.toString() ?: "null"
            "x" -> (args.getOrNull(next++) as? Number)?.toLong()?.toString(16) ?: "null"
            "X" -> (args.getOrNull(next++) as? Number)?.toLong()?.toString(16)?.uppercase() ?: "null"
            "f" -> fixed((args.getOrNull(next++) as? Number)?.toDouble() ?: 0.0, precision ?: 6)
            "g" -> (args.getOrNull(next++) as? Number)?.toDouble()?.let { fixed(it, precision ?: 3).trimEnd('0').trimEnd('.') } ?: "null"
            else -> args.getOrNull(next++).toString()
        }
        val padded = when {
            text.length >= width -> text
            '-' in flags -> text.padEnd(width)
            '0' in flags && conv != "s" -> if (text.startsWith('-')) "-" + text.drop(1).padStart(width - 1, '0') else text.padStart(width, '0')
            else -> text.padStart(width)
        }
        out.append(padded)
        i = m.range.last + 1
    }
    return out.toString()
}

private fun fixed(value: Double, digits: Int): String {
    if (value.isNaN() || value.isInfinite()) return value.toString()
    var scale = 1.0
    repeat(digits) { scale *= 10 }
    val rounded = kotlin.math.round(kotlin.math.abs(value) * scale)
    val whole = (rounded / scale).toLong()
    val frac = (rounded - whole * scale).toLong()
    val sign = if (value < 0 && rounded != 0.0) "-" else ""
    return if (digits == 0) "$sign$whole" else "$sign$whole." + frac.toString().padStart(digits, '0')
}

private val SPEC = Regex("""%([-0+ ]*)(\d+)?(\.\d+)?([dfsxXg%])""")
