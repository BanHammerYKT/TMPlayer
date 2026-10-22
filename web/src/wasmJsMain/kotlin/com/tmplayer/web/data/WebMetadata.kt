package com.tmplayer.web.data

import com.tmplayer.json.JSONObject
import com.tmplayer.online.CachedImage
import com.tmplayer.online.LogoAnswer
import com.tmplayer.online.MetaCacheStore
import com.tmplayer.online.MetaEpisode
import com.tmplayer.online.MetaExtras
import com.tmplayer.online.MetaInfo
import com.tmplayer.online.MetaKeep
import com.tmplayer.online.MetaResult
import com.tmplayer.online.MetaSeasonJson
import com.tmplayer.online.MetaSettings
import com.tmplayer.online.MetaSettingsStore
import com.tmplayer.online.OnlineMetadata
import com.tmplayer.online.SkipSegment
import com.tmplayer.platform.Background
import com.tmplayer.platform.nowMillis
import com.tmplayer.platform.sha256
import com.tmplayer.web.BuildInfo
import com.tmplayer.web.js.TmHost
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Posters and overviews in the browser, as the desktop sets them up (desktopOnlineMetadata in
 * desktop/.../Main.kt): the build's TMDB key, the same switches with the same defaults (on, as on
 * the apps, until the viewer turns it off), the answers kept in this browser's local storage and
 * the pictures fetched over fetch, through the browser's HTTP cache. The matching, the providers
 * and their limits are :core's [OnlineMetadata], the same code the apps run.
 */
internal fun webOnlineMetadata(): OnlineMetadata = OnlineMetadata(
    buildKey = BuildInfo.TMDB_API_KEY,
    store = WebMetaSettings(),
    cache = WebMetaCache(),
    appVersion = BuildInfo.VERSION,
    omdbKey = BuildInfo.OMDB_API_KEY,
).also { metadata ->
    // No warmUp(): its bare requests answer 401 and 404 and show as errors in the console. The
    // page opens the same two connections with preconnect hints instead (index.html).
    Background.scope.launch { runCatching { metadata.cache.prune() } }
}

/** [MetaSettings] under one local storage key, the fields named as MetadataStore names them. */
private class WebMetaSettings : MetaSettingsStore {
    private val state = MutableStateFlow(load())
    override val settings: StateFlow<MetaSettings> = state.asStateFlow()
    override val now: MetaSettings get() = state.value

    override fun update(change: (MetaSettings) -> MetaSettings) {
        val next = change(state.value)
        if (next == state.value) return
        save(next)
        state.value = next
    }

    private fun load(): MetaSettings {
        val p = TmHost.getItem(KEY)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        // Posters turned back on once for everybody (user, 2026-10-22), as MetadataStore does.
        val onAgain = p.optString(ON_AGAIN) == "true"
        return MetaSettings(
            enabled = !onAgain || p.optString("enabled") != "false",
            ownKey = p.optString("own_key"),
            refusedKey = p.optString("refused_key"),
            refusedAt = p.optString("refused_at").toLongOrNull() ?: 0L,
            omdbKey = p.optString("omdb_key"),
            omdbRefusedKey = p.optString("omdb_refused_key"),
            skipTimes = p.optString("skip_times") != "false",
            offByChoice = onAgain && p.optString("off_by_choice") == "true",
        )
    }

    private fun save(s: MetaSettings) {
        val p = JSONObject()
            .put("enabled", s.enabled.toString())
            .put("own_key", s.ownKey)
            .put("refused_key", s.refusedKey)
            .put("refused_at", s.refusedAt.toString())
            .put("omdb_key", s.omdbKey)
            .put("omdb_refused_key", s.omdbRefusedKey)
            .put("skip_times", s.skipTimes.toString())
            .put("off_by_choice", s.offByChoice.toString())
            .put(ON_AGAIN, "true")
        TmHost.setItem(KEY, p.toString())
    }

    private companion object {
        const val KEY = "tm.meta.settings"
        const val ON_AGAIN = "posters_on_again"
    }
}

/**
 * The answers kept between visits, as MetadataCache keeps them on the apps (the same keys, the same
 * entries, the same ages from [MetaKeep]), in local storage: one key per kind of answer, written a
 * moment after the last change and when the page is hidden, each held under a ceiling with the
 * entries written longest ago dropped first, so the page's storage stays small.
 *
 * The pictures are not stored here. Their bytes are held in memory for the decoder, up to
 * [IMAGE_CAP_BYTES]; a reload fetches them again through the browser's HTTP cache, which keeps
 * TMDB's for a year and answers without the network.
 */
private class WebMetaCache(private val now: () -> Long = { nowMillis() }) : MetaCacheStore {

    /** One kind of answer: entries by key, oldest written first, each with the time it was saved. */
    private inner class Shelf(name: String, private val capChars: Int, private val ttl: (JSONObject) -> Long) {
        private val storageKey = "tm.meta.$name"
        private var loaded: LinkedHashMap<String, JSONObject>? = null
        var dirty = false

        val entries: LinkedHashMap<String, JSONObject>
            get() = loaded ?: read().also { loaded = it }

        private fun read(): LinkedHashMap<String, JSONObject> {
            val map = LinkedHashMap<String, JSONObject>()
            val json = TmHost.getItem(storageKey)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return map
            val list = json.optJSONArray("entries") ?: return map
            for (i in 0 until list.length()) {
                val e = list.optJSONObject(i) ?: continue
                val key = e.optString("key").takeIf { it.isNotEmpty() } ?: continue
                map[key] = e
            }
            return map
        }

        /** The entry for [key] while it is fresh; a stale one is dropped. */
        fun get(key: String): JSONObject? {
            val e = entries[key] ?: return null
            if (now() - e.optLong("saved") > ttl(e)) {
                entries.remove(key)
                changed()
                return null
            }
            return e
        }

        fun put(key: String, entry: JSONObject) {
            entries.remove(key)
            entries[key] = entry.put("key", key).put("saved", now())
            changed()
        }

        fun prune() {
            val t = now()
            if (entries.entries.removeAll { (_, e) -> t - e.optLong("saved") > ttl(e) }) changed()
        }

        fun clear() {
            loaded = LinkedHashMap()
            dirty = false
            TmHost.removeItem(storageKey)
        }

        /** Writes the shelf out, the oldest entries left behind past [capChars]. */
        fun flush() {
            if (!dirty) return
            dirty = false
            val texts = entries.values.map { it.toString() }
            var total = texts.sumOf { it.length }
            var drop = 0
            while (total > capChars && drop < texts.size) total -= texts[drop++].length
            if (drop > 0) entries.keys.take(drop).forEach { entries.remove(it) }
            TmHost.setItem(storageKey, "{\"entries\":[" + texts.drop(drop).joinToString(",") + "]}")
            chars = total
        }

        /** What the shelf takes in storage, in characters, as last written. */
        var chars = -1
            get() {
                if (field < 0) field = TmHost.getItem(storageKey)?.length ?: 0
                return field
            }
    }

    private val answers = Shelf("answers", 1_200_000) { if (it.has("info")) MetaKeep.FOUND_TTL_MS else MetaKeep.MISS_TTL_MS }
    private val extrasShelf = Shelf("extras", 400_000) { MetaKeep.FOUND_TTL_MS }
    private val seasons = Shelf("seasons", 300_000) { MetaKeep.SEASON_TTL_MS }
    private val skips = Shelf("skips", 100_000) {
        if ((it.optJSONArray("segments")?.length() ?: 0) == 0) MetaKeep.SKIP_MISS_TTL_MS else MetaKeep.SKIP_FOUND_TTL_MS
    }
    private val logos = Shelf("logos", 150_000) { if (it.optString("url").isNotBlank()) MetaKeep.FOUND_TTL_MS else MetaKeep.MISS_TTL_MS }
    private val shelves = listOf(answers, extrasShelf, seasons, skips, logos)

    private var flushing = false

    init {
        // A reload or a closed tab right after a lookup still keeps it.
        TmHost.onVisible { visible -> if (!visible) flushAll() }
    }

    private fun Shelf.changed() {
        dirty = true
        if (flushing) return
        flushing = true
        Background.scope.launch {
            delay(FLUSH_DELAY_MS)
            flushAll()
        }
    }

    private fun flushAll() {
        flushing = false
        shelves.forEach { it.flush() }
    }

    override fun logo(key: String): LogoAnswer? = logos.get(key)?.let { LogoAnswer(it.optString("url").ifBlank { null }) }

    override fun putLogo(key: String, answer: LogoAnswer) = logos.put(key, JSONObject().put("url", answer.url.orEmpty()))

    override fun skipTimes(key: String): List<SkipSegment>? = skips.get(key)?.let { SkipSegment.listFromJson(it.optJSONArray("segments")) }

    override fun putSkipTimes(key: String, segments: List<SkipSegment>) =
        skips.put(key, JSONObject().put("segments", SkipSegment.listToJson(segments)))

    override fun answer(key: String): MetaResult? {
        val e = answers.get(key) ?: return null
        val info = e.optJSONObject("info")?.let { MetaInfo.fromJson(it) }
        return if (info != null) MetaResult.Found(info) else MetaResult.NoMatch
    }

    override fun putAnswer(key: String, result: MetaResult) {
        val info = when (result) {
            is MetaResult.Found -> result.info
            MetaResult.NoMatch -> null
            else -> return
        }
        val json = JSONObject()
        if (info != null) json.put("info", info.toJson())
        answers.put(key, json)
    }

    override fun extras(key: String): MetaExtras? = extrasShelf.get(key)?.optJSONObject("extras")?.let { MetaExtras.fromJson(it) }

    override fun putExtras(key: String, value: MetaExtras) = extrasShelf.put(key, JSONObject().put("extras", value.toJson()))

    override fun season(key: String): List<MetaEpisode>? = seasons.get(key)?.optJSONArray("episodes")?.let { MetaSeasonJson.fromJson(it) }

    override fun putSeason(key: String, episodes: List<MetaEpisode>) = seasons.put(key, JSONObject().put("episodes", MetaSeasonJson.toJson(episodes)))

    // ---- pictures, in memory --------------------------------------------------------------------

    private val images = LinkedHashMap<String, java.io.File>()
    private var imageTotal = 0L

    override fun image(url: String): CachedImage? {
        val file = images.remove(url) ?: return null
        images[url] = file
        return file
    }

    override fun putImage(url: String, bytes: ByteArray): CachedImage? {
        images.remove(url)?.let { imageTotal -= it.length(); it.delete() }
        val file = java.io.File(IMAGE_DIR, name(url))
        file.writeBytes(bytes)
        images[url] = file
        imageTotal += bytes.size
        while (imageTotal > IMAGE_CAP_BYTES && images.size > 1) {
            val oldest = images.keys.first()
            images.remove(oldest)?.let { imageTotal -= it.length(); it.delete() }
        }
        return file
    }

    override fun prune() {
        shelves.forEach { it.prune() }
    }

    override fun bytes(): Long = shelves.sumOf { it.chars.toLong() * 2 } + imageTotal

    override fun imageBytes(): Long = imageTotal

    override fun purgeImages() {
        images.values.forEach { it.delete() }
        images.clear()
        imageTotal = 0
    }

    override fun purge() {
        purgeImages()
        shelves.forEach { it.clear() }
    }

    private fun name(url: String): String =
        sha256(url.encodeToByteArray()).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private companion object {
        const val IMAGE_DIR = "/meta-images"

        /** The picture bytes held for the decoder: a few screens of posters and backdrops. */
        const val IMAGE_CAP_BYTES = 32L * 1024 * 1024

        const val FLUSH_DELAY_MS = 1_500L
    }
}
