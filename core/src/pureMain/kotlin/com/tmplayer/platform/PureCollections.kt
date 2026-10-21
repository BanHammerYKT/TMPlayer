package com.tmplayer.platform

/**
 * The JDK collection helpers pure code used, written for every platform: `toSortedMap`,
 * `toSortedSet`, `putIfAbsent` and `merge` are JVM only in Kotlin's standard library.
 */

/** This map's entries in key order, as `toSortedMap()` iterates them. */
internal fun <K : Comparable<K>, V> Map<K, V>.sortedByKey(): Map<K, V> =
    entries.sortedBy { it.key }.associateTo(LinkedHashMap()) { it.key to it.value }

/** This collection's distinct elements in natural order, as `toSortedSet()` holds them. */
internal fun <T : Comparable<T>> Iterable<T>.sortedDistinct(): Set<T> = toSortedList().toCollection(LinkedHashSet())

private fun <T : Comparable<T>> Iterable<T>.toSortedList(): List<T> = toSet().sorted()

/** Puts [value] under [key] when nothing is there yet; returns what was there, as `putIfAbsent`. */
internal fun <K, V> MutableMap<K, V>.putIfMissing(key: K, value: V): V? {
    val existing = get(key)
    if (existing == null) put(key, value)
    return existing
}

/** Combines [value] with what is under [key] by [combine], or puts it, as `merge`. */
internal fun <K, V : Any> MutableMap<K, V>.mergeWith(key: K, value: V, combine: (V, V) -> V) {
    val existing = get(key)
    put(key, if (existing == null) value else combine(existing, value))
}

/** The code point at [index], joining a surrogate pair, as `String.codePointAt`. */
internal fun String.codePointAtIndex(index: Int): Int {
    val high = this[index]
    if (high.isHighSurrogate() && index + 1 < length) {
        val low = this[index + 1]
        if (low.isLowSurrogate()) return ((high.code - 0xD800) shl 10) + (low.code - 0xDC00) + 0x10000
    }
    return high.code
}

/** How many chars [codePoint] takes, as `Character.charCount`. */
internal fun charCountOf(codePoint: Int): Int = if (codePoint >= 0x10000) 2 else 1

/** Appends [codePoint], as `StringBuilder.appendCodePoint`. */
internal fun StringBuilder.appendCodePointValue(codePoint: Int): StringBuilder {
    if (codePoint < 0x10000) return append(codePoint.toChar())
    val v = codePoint - 0x10000
    append((0xD800 + (v shr 10)).toChar())
    append((0xDC00 + (v and 0x3FF)).toChar())
    return this
}

/** [bytes] from [offset] as UTF-16, big or little endian, as `String(bytes, Charsets.UTF_16BE)`. */
internal fun utf16(bytes: ByteArray, offset: Int, length: Int, bigEndian: Boolean): String {
    val out = StringBuilder(length / 2)
    var i = offset
    val end = offset + length - 1
    while (i < end) {
        val a = bytes[i].toInt() and 0xFF
        val b = bytes[i + 1].toInt() and 0xFF
        out.append((if (bigEndian) (a shl 8) or b else (b shl 8) or a).toChar())
        i += 2
    }
    return out.toString()
}

/** Compares two runs of decimal digits by their value, however long, as BigInteger would. */
internal fun compareDigitRuns(a: String, b: String): Int {
    val x = a.trimStart('0')
    val y = b.trimStart('0')
    if (x.length != y.length) return x.length.compareTo(y.length)
    return x.compareTo(y)
}
