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
    /** A title's logo answer, the title having none included, or null to ask. */
    fun logo(key: String): LogoAnswer?
    fun putLogo(key: String, answer: LogoAnswer)
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

/**
 * How long each kind of answer is kept, the same in every [MetaCacheStore]: the JVM's directory
 * and the browser's storage forget on one schedule. Nothing past six months, TMDB's limit.
 */
object MetaKeep {
    const val DAY_MS = 24L * 60 * 60 * 1000
    const val MAX_TTL_MS = 180 * DAY_MS
    const val FOUND_TTL_MS = 60 * DAY_MS
    const val MISS_TTL_MS = 7 * DAY_MS
    const val SKIP_FOUND_TTL_MS = 30 * DAY_MS
    const val SKIP_MISS_TTL_MS = 3 * DAY_MS

    /** A season's episode list: a day, since a running show gains an episode a week. */
    const val SEASON_TTL_MS = 24L * 60 * 60 * 1000
}

/** A season's episode list as a [MetaCacheStore] keeps it, one shape on every platform. */
object MetaSeasonJson {
    fun toJson(episodes: List<MetaEpisode>): com.tmplayer.json.JSONArray = com.tmplayer.json.JSONArray(
        episodes.map {
            com.tmplayer.json.JSONObject().put("season", it.season).put("number", it.number).put("name", it.name).put("overview", it.overview)
                .put("still", it.stillUrl.orEmpty()).put("airDate", it.airDate.orEmpty()).put("runtime", it.runtimeMin ?: 0)
        },
    )

    fun fromJson(list: com.tmplayer.json.JSONArray): List<MetaEpisode> =
        (0 until list.length()).mapNotNull { list.optJSONObject(it) }.map {
            MetaEpisode(
                season = it.optInt("season"),
                number = it.optInt("number"),
                name = it.optString("name"),
                overview = it.optString("overview"),
                stillUrl = it.optString("still").ifBlank { null },
                airDate = it.optString("airDate").ifBlank { null },
                runtimeMin = it.optInt("runtime").takeIf { r -> r > 0 },
            )
        }
}
