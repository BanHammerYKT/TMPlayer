package com.tmplayer.platform

/** A failure to reach the network or read a file: `java.io.IOException` itself on the JVM. */
expect open class IOException(message: String?) : Exception

/** [text] as `application/x-www-form-urlencoded`, as `URLEncoder.encode(text, "UTF-8")` writes it. */
fun formEncode(text: String): String {
    val out = StringBuilder()
    for (b in text.encodeToByteArray()) {
        val c = b.toInt() and 0xFF
        when {
            c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code ||
                c == '.'.code || c == '-'.code || c == '*'.code || c == '_'.code -> out.append(c.toChar())
            c == ' '.code -> out.append('+')
            else -> out.append('%').append(HEX[c shr 4]).append(HEX[c and 15])
        }
    }
    return out.toString()
}

private const val HEX = "0123456789ABCDEF"
