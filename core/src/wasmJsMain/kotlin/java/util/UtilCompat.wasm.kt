// The JDK as far as the browser build of :core's JVM half needs it (core/web-sources.txt):
// small stand ins for the JDK types those files name, on the browser's own facilities.
package java.util

import kotlin.io.encoding.ExperimentalEncodingApi

/** java.util.Locale is the core's own TmLocale in the browser, as TmLocale is Locale on the JVM. */
typealias Locale = com.tmplayer.i18n.TmLocale

/** java.util.Date: a moment, in milliseconds. */
class Date(val time: Long = com.tmplayer.platform.nowMillis())

@OptIn(ExperimentalEncodingApi::class)
object Base64 {
    class Encoder internal constructor(private val url: Boolean) {
        fun encodeToString(bytes: ByteArray): String =
            if (url) kotlin.io.encoding.Base64.UrlSafe.encode(bytes) else kotlin.io.encoding.Base64.Default.encode(bytes)
        fun encode(bytes: ByteArray): ByteArray = encodeToString(bytes).encodeToByteArray()
    }

    class Decoder internal constructor(private val url: Boolean) {
        fun decode(text: String): ByteArray =
            if (url) kotlin.io.encoding.Base64.UrlSafe.decode(text) else kotlin.io.encoding.Base64.Default.decode(text)
    }

    fun getEncoder(): Encoder = Encoder(false)
    fun getDecoder(): Decoder = Decoder(false)
    fun getUrlEncoder(): Encoder = Encoder(true)
    fun getUrlDecoder(): Decoder = Decoder(true)
}

object Collections {
    fun <T> synchronizedSet(set: MutableSet<T>): MutableSet<T> = set
    fun <K, V> synchronizedMap(map: MutableMap<K, V>): MutableMap<K, V> = map
    fun <T> synchronizedList(list: MutableList<T>): MutableList<T> = list
}

/** java.util.Properties: key=value lines, as the desktop's own settings file keeps them. */
class Properties {
    private val map = LinkedHashMap<String, String>()

    fun getProperty(key: String): String? = map[key]
    fun setProperty(key: String, value: String): Any? = map.put(key, value)
    fun containsKey(key: Any?): Boolean = key in map

    fun load(input: java.io.InputStream) {
        input.readBytes().decodeToString().lines().forEach { line ->
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#") || t.startsWith("!")) return@forEach
            val at = t.indexOfFirst { it == '=' || it == ':' }
            if (at > 0) map[t.substring(0, at).trim()] = t.substring(at + 1).trim()
        }
    }

    fun store(output: java.io.OutputStream, comment: String?) {
        val text = buildString {
            if (comment != null) append("#").append(comment).append('\n')
            map.forEach { (k, v) -> append(k).append('=').append(v).append('\n') }
        }
        output.write(text.encodeToByteArray())
        output.flush()
    }
}
