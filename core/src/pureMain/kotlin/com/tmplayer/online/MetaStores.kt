package com.tmplayer.online

import kotlinx.coroutines.flow.StateFlow

/** Where the online metadata settings are kept: MetadataStore's small file on the JVM. */
interface MetaSettingsStore {
    val settings: StateFlow<MetaSettings>
    val now: MetaSettings

    /** Applies [change] and keeps the result, when it differs. */
    fun update(change: (MetaSettings) -> MetaSettings)
}

/**
 * Where the answers and the pictures are kept between launches: MetadataCache's directory on the
 * JVM, the browser's storage on the web. Every read misses quietly; nothing here throws.
 */
interface MetaCacheStore {
    fun skipTimes(key: String): List<SkipSegment>?
    fun putSkipTimes(key: String, segments: List<SkipSegment>)
    fun answer(key: String): MetaResult?
    fun putAnswer(key: String, result: MetaResult)
    fun extras(key: String): MetaExtras?
    fun putExtras(key: String, value: MetaExtras)
    fun season(key: String): List<MetaEpisode>?
    fun putSeason(key: String, episodes: List<MetaEpisode>)
    fun image(url: String): CachedImage?
    fun putImage(url: String, bytes: ByteArray): CachedImage?

    /** Drops what has outlived its keep. */
    fun prune()
    fun bytes(): Long
    fun imageBytes(): Long
    fun purgeImages()
    fun purge()
}

/** A picture kept by [MetaCacheStore]: a file on the JVM, its bytes in the browser. */
expect open class CachedImage

/** The transport OnlineMetadata uses unless told otherwise: the JDK's connection, or fetch. */
expect val defaultTransport: HttpTransport
