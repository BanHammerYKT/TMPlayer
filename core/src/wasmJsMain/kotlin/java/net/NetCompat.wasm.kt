// The JDK as far as the browser build of :core's JVM half needs it (core/web-sources.txt):
// small stand ins for the JDK types those files name, on the browser's own facilities.
package java.net

private fun jsDecode(s: String): String = js("decodeURIComponent(s)")
private fun jsEncode(s: String): String = js("encodeURIComponent(s)")

object URLDecoder {
    fun decode(text: String, @Suppress("UNUSED_PARAMETER") charset: String): String = jsDecode(text.replace("+", " "))
}

object URLEncoder {
    fun encode(text: String, @Suppress("UNUSED_PARAMETER") charset: String): String = jsEncode(text).replace("%20", "+")
}

/** The JDK's HTTP client, as named by code the browser build never runs (the updater). */
class URI(private val text: String) {
    fun toURL(): URL = URL(text)
    override fun toString(): String = text
}

class URL(private val text: String) {
    fun openConnection(): URLConnection = HttpURLConnection(text)
}

open class URLConnection

class HttpURLConnection(@Suppress("unused") private val url: String) : URLConnection() {
    var connectTimeout: Int = 0
    var readTimeout: Int = 0
    var instanceFollowRedirects: Boolean = true
    val responseCode: Int get() = throw java.io.IOException("No HTTP from here in the browser")
    val errorStream: java.io.InputStream? get() = null
    val inputStream: java.io.InputStream get() = throw java.io.IOException("No HTTP from here in the browser")
    fun setRequestProperty(key: String, value: String) {}
    fun setRequestProperty(entry: Map.Entry<String, String>) {}
    fun disconnect() {}
}
