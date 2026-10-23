package com.tmplayer.online

import com.tmplayer.platform.IOException

/**
 * The web player's rules look nothing up online (section 7 of the web player plan), so nothing
 * is kept and nothing is fetched: a lookup fails as a network error would.
 */
actual open class CachedImage

actual val defaultTransport: HttpTransport get() = NoTransport

private object NoTransport : HttpTransport {
    override suspend fun send(request: HttpRequest): HttpResponse = throw IOException("no online lookups in the web player")
}
