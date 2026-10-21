package com.tmplayer.online

actual typealias CachedImage = java.io.File

actual val defaultTransport: HttpTransport get() = UrlConnectionTransport
