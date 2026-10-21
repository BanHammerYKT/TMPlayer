package com.tmplayer.online

import com.tmplayer.platform.IOException

/** One request to an online provider. [body] is sent as JSON when present. */
data class HttpRequest(
    val method: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val body: String? = null,
)

/** The status, the headers (names in lower case) and the body, read whole. */
class HttpResponse(
    val code: Int,
    headers: Map<String, String> = emptyMap(),
    val body: ByteArray = ByteArray(0),
) {
    val headers: Map<String, String> = headers.mapKeys { it.key.lowercase() }

    val text: String get() = body.decodeToString()

    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * The one way the online providers reach the network, so the tests can stand a fake in its place.
 * A failure to connect at all is an [IOException]; any answer, an error status included, is a
 * [HttpResponse].
 */
fun interface HttpTransport {
    suspend fun send(request: HttpRequest): HttpResponse
}
