package com.tmplayer.online

/**
 * A kept picture is a [java.io.File] in the browser too (the shared screens decode it by its
 * path), one of the browser build's in-memory files (core/src/wasmJsMain/.../java/io).
 */
actual typealias CachedImage = java.io.File

actual val defaultTransport: HttpTransport get() = FetchTransport
