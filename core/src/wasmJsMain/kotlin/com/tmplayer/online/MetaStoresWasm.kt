package com.tmplayer.online

/** A picture's bytes, as the browser keeps it. */
actual open class CachedImage(val bytes: ByteArray)

actual val defaultTransport: HttpTransport get() = FetchTransport
