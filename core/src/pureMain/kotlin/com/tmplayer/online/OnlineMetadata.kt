package com.tmplayer.online

import com.tmplayer.data.MediaItem
import com.tmplayer.i18n.Translator
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.tmplayer.platform.IOException
import com.tmplayer.platform.LruMap
import com.tmplayer.platform.PlainLock
import com.tmplayer.platform.nowMillis
import com.tmplayer.platform.sha256
import kotlin.concurrent.Volatile
import kotlinx.coroutines.sync.withPermit

/**
 * Posters and overviews for videos, looked up by name: the one place phone, TV and desktop all
 * go through, so the rules below hold the same everywhere.
 *
 * - **Off until asked.** Nothing is looked up until the viewer turns it on in Settings (or from
 *   the detail panel): a lookup sends a video's cleaned name to a provider.
 * - **Providers.** TMDB for films and shows, with the build's key (`TMDB_API_KEY`) or the
 *   viewer's own; TVmaze for shows and their episodes, with no key; AniList for anime, with no
 *   key. A show from TMDB takes its episode from TMDB too, in the UI language; one from TVmaze
 *   from TVmaze. Anime asks AniList first.
 * - **Safe matching.** Only a title with the same words, or most of them and the same year, is
 *   taken ([MetaMatch]); otherwise the file keeps its own name. `{tmdb-123}` in a name forces it.
 * - **Never in the way.** A refused TMDB key turns TMDB off with a sentence saying so (shows still
 *   come from TVmaze and AniList); a 429 pauses that provider for as long as it asks; offline,
 *   whatever is cached keeps showing. No feature of the app waits on any of this.
 * - **Polite.** One request at a time per provider, spaced to its published limit; one lookup per
 *   title at a time; everything kept in [cache] for up to six months, with a purge in Settings.
 * - **Language.** Overviews in the UI language where the provider has one, English otherwise.
 */
class OnlineMetadata(
    private val buildKey: String,
    val store: MetaSettingsStore,
    val cache: MetaCacheStore,
    appVersion: String,
    private val http: HttpTransport = defaultTransport,
    private val now: () -> Long = { nowMillis() },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val language: () -> String = { Translator.messages.tag },
    /** The viewer's region, for the age rating: the device's country, else the US. */
    private val region: () -> String = { MetaRules.region() },
) {
    private val userAgent = "TMPlayer/$appVersion (+https://tmplayer.org)"
    private val tmdb = TmdbApi(http, RateLimiter(TmdbApi.GAP_MS, now, sleep), userAgent)
    private val tvmaze = TvMazeApi(http, RateLimiter(TvMazeApi.GAP_MS, now, sleep), userAgent)
    private val anilist = AniListApi(http, RateLimiter(AniListApi.GAP_MS, now, sleep), userAgent)
    /**
     * Pictures fetched side by side. They come from the providers' image CDNs, which carry no
     * request limit of their own, so they are not queued behind one another as the API calls are:
     * a row of ten posters spaced 50 ms apart, each over a fresh connection, was most of the wait
     * for Home's posters (2026-10-21).
     */
    private val imageGate = kotlinx.coroutines.sync.Semaphore(IMAGE_PARALLEL)
    private val skipApi = SkipTimesApi(
        http,
        RateLimiter(SkipTimesApi.INTRO_GAP_MS, now, sleep),
        RateLimiter(SkipTimesApi.ANISKIP_GAP_MS, now, sleep),
        userAgent,
    )

    private val locks = HashMap<String, Mutex>()
    private val locksLock = PlainLock()
    private val memory = LruMap<String, MetaResult>(MEMORY_ENTRIES)
    private val pausedUntil = HashMap<MetaProvider, Long>()
    private val pausedLock = PlainLock()

    val settings: StateFlow<MetaSettings> get() = store.settings

    /** Whether this build carries a TMDB key of its own. */
    val buildHasKey: Boolean get() = buildKey.isNotBlank()

    fun setEnabled(value: Boolean) = store.update { it.copy(enabled = value, offByChoice = !value) }

    fun setSkipTimes(value: Boolean) = store.update { it.copy(skipTimes = value) }

    /** The viewer's own TMDB key; blank goes back to the build's. A new key gets a fresh chance. */
    fun setOwnKey(key: String) = store.update { it.copy(ownKey = key.trim(), refusedKey = "", refusedAt = 0) }

    /** What Settings says about TMDB, whether or not lookups are on. */
    fun tmdbState(s: MetaSettings = store.now): TmdbState {
        val own = s.ownKey.isNotBlank()
        val key = activeKey(s)
        return when {
            key.isBlank() -> TmdbState.NoKey
            refused(s, key, own) -> if (own) TmdbState.OwnKeyRefused else TmdbState.AppKeyRefused
            own -> TmdbState.OwnKey
            else -> TmdbState.AppKey
        }
    }

    /** True when TMDB can be asked right now. */
    private fun tmdbUsable(s: MetaSettings): Boolean =
        tmdbState(s).let { it == TmdbState.AppKey || it == TmdbState.OwnKey }

    private fun activeKey(s: MetaSettings): String = s.ownKey.ifBlank { buildKey }

    /** The build's key is retried once a day after a refusal, in case it was a blip; the viewer's own until they change it. */
    private fun refused(s: MetaSettings, key: String, own: Boolean): Boolean =
        s.refusedKey.isNotEmpty() && s.refusedKey == print(key) && (own || now() - s.refusedAt < REFUSED_RETRY_MS)

    private fun print(key: String): String =
        sha256(key.encodeToByteArray()).take(6).joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    /** The language tag TMDB is asked in: the UI's, with the pseudo-locales and plain English as en-US. */
    fun tmdbLanguage(tag: String = language()): String = when {
        tag.isBlank() || tag.startsWith("en") -> ENGLISH
        else -> tag
    }

    // ---- lookups ------------------------------------------------------------------------------

    /** What is already known for [query] in memory, for the first frame. Never touches the disk or the network. */
    fun peek(query: MetaQuery): MetaResult? {
        if (!store.now.enabled) return MetaResult.Off
        return memory[query.cacheKey(tmdbLanguage())]
    }

    /**
     * The poster and overview for [query]: from memory, then the disk cache, then the providers.
     * Two callers asking for the same title wait for one lookup.
     */
    suspend fun lookup(query: MetaQuery): MetaResult {
        if (!store.now.enabled) return MetaResult.Off
        val lang = tmdbLanguage()
        val key = query.cacheKey(lang)
        remembered(key)?.let { return it }
        return lockFor(key).withLock {
            remembered(key)?.let { return@withLock it }
            val (result, keep) = resolve(query, lang)
            if (keep) {
                cache.putAnswer(key, result)
                memory[key] = result
            }
            result
        }
    }

    private fun remembered(key: String): MetaResult? {
        memory[key]?.let { return it }
        val stored = cache.answer(key) ?: return null
        memory[key] = stored
        return stored
    }

    private fun lockFor(key: String): Mutex = locksLock.locked {
        if (locks.size > MEMORY_ENTRIES) locks.entries.removeAll { !it.value.isLocked }
        locks.getOrPut(key) { Mutex() }
    }

    /** The answer, and whether it may be kept: only what every provider could be asked about is. */
    private suspend fun resolve(query: MetaQuery, lang: String): Pair<MetaResult, Boolean> {
        if (query.wantsEpisode) {
            val show = lookup(query.showOnly())
            if (show !is MetaResult.Found) return show to (show is MetaResult.NoMatch)
            val (episode, complete) = episodeFor(show.info, query, lang)
            return MetaResult.Found(show.info.copy(episode = episode)) to complete
        }
        var blocked = false
        val order = when {
            query.kind == MetaKind.Film && query.anime -> listOf(MetaProvider.Tmdb, MetaProvider.AniList)
            query.kind == MetaKind.Film -> listOf(MetaProvider.Tmdb)
            query.anime -> listOf(MetaProvider.AniList, MetaProvider.Tmdb, MetaProvider.TvMaze)
            else -> listOf(MetaProvider.Tmdb, MetaProvider.TvMaze)
        }
        for (provider in order) {
            when (val outcome = ask(provider, query, lang)) {
                is Outcome.Found -> return MetaResult.Found(outcome.info) to true
                Outcome.Nothing -> Unit
                Outcome.Blocked -> blocked = true
            }
        }
        return if (blocked) MetaResult.Unavailable to false else MetaResult.NoMatch to true
    }

    private sealed interface Outcome {
        data class Found(val info: MetaInfo) : Outcome
        data object Nothing : Outcome

        /** Refused, throttled or offline: no answer, so no "nothing matched" may be kept. */
        data object Blocked : Outcome
    }

    private suspend fun ask(provider: MetaProvider, query: MetaQuery, lang: String): Outcome {
        if (paused(provider)) return Outcome.Blocked
        val s = store.now
        return when (provider) {
            MetaProvider.Tmdb -> if (!tmdbUsable(s)) Outcome.Blocked else tmdbLookup(activeKey(s), query, lang)
            MetaProvider.TvMaze -> tvmazeLookup(query)
            MetaProvider.AniList -> anilistLookup(query)
        }
    }

    private fun paused(provider: MetaProvider): Boolean = pausedLock.locked {
        (pausedUntil[provider] ?: 0L) > now()
    }

    /** Sorts a failed reply: pauses on 429, turns TMDB off on a refused key. Null when [reply] is Ok. */
    private fun <T> trouble(provider: MetaProvider, reply: MetaReply<T>, key: String? = null): Outcome? = when (reply) {
        is MetaReply.Ok -> null
        is MetaReply.Throttled -> {
            pausedLock.locked { pausedUntil[provider] = now() + reply.retryAfterMs }
            Outcome.Blocked
        }
        MetaReply.Refused -> {
            if (key != null) store.update { it.copy(refusedKey = print(key), refusedAt = now()) }
            Outcome.Blocked
        }
        // A 404 is an answer: that id or episode does not exist.
        is MetaReply.Failed -> if (reply.code == 404) Outcome.Nothing else Outcome.Blocked
    }

    /** One more try after a second for a dropped connection or a server error, then give up. */
    private suspend fun <T> retrying(call: suspend () -> MetaReply<T>): MetaReply<T> {
        val first = call()
        if (first is MetaReply.Failed && (first.code == 0 || first.code >= 500)) {
            sleep(RETRY_MS)
            return call()
        }
        return first
    }

    private suspend fun tmdbLookup(key: String, query: MetaQuery, lang: String): Outcome {
        val film = query.kind == MetaKind.Film
        // The match as the English search gave it, when that is where it was found.
        var inEnglish: MetaCandidate? = null
        var candidate: MetaCandidate = if (query.tmdbId != null) {
            val reply = retrying { if (film) tmdb.movie(key, query.tmdbId.toString(), lang) else tmdb.tv(key, query.tmdbId.toString(), lang) }
            trouble(MetaProvider.Tmdb, reply, key)?.let { return it }
            (reply as MetaReply.Ok).value
        } else {
            // In another language the titles TMDB answers with are that language's, or the original
            // where nobody translated it ("Le Fabuleux Destin d'Amélie Poulain" in Arabic), and a
            // file named in English matches neither. So a search that finds nothing in the UI
            // language is asked once more in English.
            var found: MetaCandidate? = null
            for (searchLang in listOf(lang, ENGLISH).distinct()) {
                when (val outcome = tmdbSearch(key, query, searchLang)) {
                    is SearchOutcome.Found -> {
                        found = outcome.candidate
                        if (searchLang != lang) inEnglish = outcome.candidate
                        break
                    }
                    is SearchOutcome.Stopped -> return outcome.outcome
                    SearchOutcome.None -> Unit
                }
            }
            found ?: return Outcome.Nothing
        }
        var poster = candidate.posterUrl
        if (lang != ENGLISH) {
            // The title in the UI language, and its poster chosen by [MetaRules.poster] rather than
            // whichever one TMDB names for this language: one call, with the posters appended. A
            // failure here keeps what the search said.
            val ui = lang.substringBefore('-').lowercase()
            val imageLanguages = listOf(ui, "en", "null", candidate.originalLanguage.lowercase()).filter { it.isNotBlank() }.distinct()
            val localized = retrying { tmdb.localized(key, film, candidate.id, lang, imageLanguages) }
            if (localized is MetaReply.Ok) {
                val (local, posters) = localized.value
                val original = local.originalLanguage.ifBlank { candidate.originalLanguage }
                poster = MetaRules.poster(posters, lang, original)?.let { tmdb.posterUrl(it) } ?: local.posterUrl ?: poster
                candidate = local.copy(
                    names = (local.names + candidate.names).filter { it.isNotBlank() }.distinct(),
                    backdropUrl = local.backdropUrl ?: candidate.backdropUrl,
                    originalLanguage = original,
                )
            } else if (inEnglish != null) {
                // Not localized after all: what is shown came from the English search.
                return Outcome.Found(info(query, candidate, film, poster, candidate.overview, ENGLISH))
            }
        }
        var overview = candidate.overview
        var overviewLang = lang
        if (overview.isBlank() && lang != ENGLISH) {
            val english = inEnglish?.let { MetaReply.Ok(it) }
                ?: retrying { if (film) tmdb.movie(key, candidate.id, ENGLISH) else tmdb.tv(key, candidate.id, ENGLISH) }
            if (english is MetaReply.Ok) {
                overview = english.value.overview
                overviewLang = ENGLISH
            }
        }
        return Outcome.Found(info(query, candidate, film, poster, overview, overviewLang))
    }

    private fun info(query: MetaQuery, candidate: MetaCandidate, film: Boolean, poster: String?, overview: String, overviewLang: String) = MetaInfo(
        provider = MetaProvider.Tmdb,
        id = candidate.id,
        kind = if (film) MetaKind.Film else MetaKind.Show,
        title = candidate.names.firstOrNull { it.isNotBlank() } ?: query.title,
        year = candidate.year,
        overview = overview,
        posterUrl = poster,
        backdropUrl = candidate.backdropUrl,
        language = overviewLang,
    )

    private sealed interface SearchOutcome {
        data class Found(val candidate: MetaCandidate) : SearchOutcome
        data object None : SearchOutcome

        /** Refused, throttled or offline: the lookup ends with [outcome]. */
        data class Stopped(val outcome: Outcome) : SearchOutcome
    }

    /** TMDB's search in [searchLang] for the title that [MetaMatch] takes, with the year and then without it. */
    private suspend fun tmdbSearch(key: String, query: MetaQuery, searchLang: String): SearchOutcome {
        val film = query.kind == MetaKind.Film
        suspend fun search(year: Int?): MetaReply<List<MetaCandidate>> = retrying {
            if (film) tmdb.searchMovie(key, query.title, year, searchLang) else tmdb.searchTv(key, query.title, year, searchLang)
        }
        val first = search(query.year)
        trouble(MetaProvider.Tmdb, first, key)?.let { return SearchOutcome.Stopped(it) }
        var found = (first as MetaReply.Ok).value.firstOrNull { MetaMatch.accepts(query.title, query.year, it.names, it.year, show = !film) }
        // A year one off (a festival date, a late release) is filtered out by TMDB's own year,
        // so ask once more without it and let the match allow the difference. So is a show's
        // later season, whose file names carry the season's year rather than the premiere's.
        if (found == null && query.year != null) {
            val again = search(null)
            trouble(MetaProvider.Tmdb, again, key)?.let { return SearchOutcome.Stopped(it) }
            found = (again as MetaReply.Ok).value.firstOrNull { MetaMatch.accepts(query.title, query.year, it.names, it.year, show = !film) }
        }
        return found?.let { SearchOutcome.Found(it) } ?: SearchOutcome.None
    }

    private suspend fun tvmazeLookup(query: MetaQuery): Outcome {
        if (query.kind != MetaKind.Show) return Outcome.Nothing
        val reply = retrying { tvmaze.show(query.title) }
        trouble(MetaProvider.TvMaze, reply)?.let { return it }
        val show = (reply as MetaReply.Ok).value ?: return Outcome.Nothing
        if (!MetaMatch.accepts(query.title, query.year, show.names, show.year, show = true)) return Outcome.Nothing
        return Outcome.Found(
            MetaInfo(
                provider = MetaProvider.TvMaze,
                id = show.id,
                kind = MetaKind.Show,
                title = show.names.first(),
                year = show.year,
                overview = show.overview,
                posterUrl = show.posterUrl,
                language = ENGLISH,
            ),
        )
    }

    private suspend fun anilistLookup(query: MetaQuery): Outcome {
        val reply = retrying { anilist.search(query.title) }
        trouble(MetaProvider.AniList, reply)?.let { return it }
        val wantFilm = query.kind == MetaKind.Film
        val found = (reply as MetaReply.Ok).value
            .filter { (it.format == "MOVIE") == wantFilm }
            .firstOrNull { MetaMatch.accepts(query.title, query.year, it.names, it.year, show = !wantFilm) }
            ?: return Outcome.Nothing
        return Outcome.Found(
            MetaInfo(
                provider = MetaProvider.AniList,
                id = found.id,
                kind = query.kind,
                title = found.names.firstOrNull { it.isNotBlank() } ?: query.title,
                year = found.year,
                overview = found.overview,
                posterUrl = found.posterUrl,
                backdropUrl = found.backdropUrl,
                language = ENGLISH,
            ),
        )
    }

    /** The episode from the provider the show came from, and whether that answer is final. */
    private suspend fun episodeFor(show: MetaInfo, query: MetaQuery, lang: String): Pair<MetaEpisode?, Boolean> {
        val season = query.season ?: 1
        val number = query.episode ?: return null to true
        if (paused(show.provider)) return null to false
        return when (show.provider) {
            MetaProvider.Tmdb -> {
                val s = store.now
                if (!tmdbUsable(s)) return null to false
                val key = activeKey(s)
                val reply = retrying { tmdb.episode(key, show.id, season, number, lang) }
                if (reply is MetaReply.Failed && reply.code == 404) return null to true
                trouble(MetaProvider.Tmdb, reply, key)?.let { return null to false }
                var episode = (reply as MetaReply.Ok).value
                if (episode.overview.isBlank() && lang != ENGLISH) {
                    val english = retrying { tmdb.episode(key, show.id, season, number, ENGLISH) }
                    if (english is MetaReply.Ok) {
                        episode = episode.copy(
                            name = episode.name.ifBlank { english.value.name },
                            overview = english.value.overview,
                        )
                    }
                }
                episode to true
            }
            MetaProvider.TvMaze -> {
                val reply = retrying { tvmaze.episode(show.id, season, number) }
                trouble(MetaProvider.TvMaze, reply)?.let { return null to false }
                (reply as MetaReply.Ok).value to true
            }
            // AniList lists no episodes; the show's own words stand for each one.
            MetaProvider.AniList -> null to true
        }
    }

    // ---- the detail page's extras -------------------------------------------------------------

    private val extrasMemory = LruMap<String, MetaExtras>(EXTRAS_MEMORY_ENTRIES)

    private fun extrasKey(info: MetaInfo, lang: String, region: String): String =
        listOf("extras2", info.provider.name, info.kind.name, info.id, lang, region).joinToString("|")

    /** The extras for [info] already in memory, for the first frame. Never touches the disk or the network. */
    fun peekExtras(info: MetaInfo): MetaExtras? {
        if (!store.now.enabled) return null
        return extrasMemory[extrasKey(info, tmdbLanguage(), region())]
    }

    /**
     * The facts, cast, trailer and recommendations for a title already matched: one call to the
     * provider it came from (TMDB with everything appended, TVmaze with the cast embedded, one
     * AniList query), then kept as long as the match is. Null while off, or when the provider
     * could not be asked; an empty [MetaExtras] when it had nothing.
     */
    /**
     * Where [item]'s intro, recap and credits are, for the player's skip button: empty when
     * lookups or skip times are off, when the video matched no title, or when nobody has timed it.
     *
     * The title comes from the same lookup as its poster. A TMDB match asks TheIntroDB by TMDB id;
     * a TVmaze match asks it by the IMDb or TheTVDB id TVmaze lists; an AniList match asks AniSkip
     * by MyAnimeList id, with the episode's absolute number. Where TheIntroDB has nothing,
     * IntroDB.app is asked by IMDb id, then for a show AniSkip by the title's AniList match.
     * Answers, empty ones too, are kept.
     */
    suspend fun skipTimes(item: com.tmplayer.data.MediaItem): List<SkipSegment> {
        val settings = store.now
        if (!settings.enabled || !settings.skipTimes) return emptyList()
        val query = MetaQuery.of(item.nameForParsing, item.caption) ?: return emptyList()
        val info = (lookup(query) as? MetaResult.Found)?.info ?: return emptyList()
        val show = info.kind == MetaKind.Show
        if (show && query.episode == null) return emptyList()
        val season = if (show) query.season ?: 1 else null
        val episode = if (show) query.episode else null
        val key = listOf("skips", info.provider.name, info.kind.name, info.id, season ?: 0, episode ?: 0).joinToString("|")
        cache.skipTimes(key)?.let { return it }
        return lockFor(key).withLock {
            cache.skipTimes(key)?.let { return@withLock it }
            val reply = when (info.provider) {
                MetaProvider.Tmdb -> skipApi.theIntroDb(info.id, null, null, season, episode)
                MetaProvider.TvMaze -> {
                    val ids = extras(info) ?: return@withLock emptyList()
                    if (ids.imdbId == null && ids.tvdbId == null) return@withLock emptyList()
                    skipApi.theIntroDb(null, ids.imdbId, ids.tvdbId, season, episode)
                }
                MetaProvider.AniList -> {
                    val mal = extras(info)?.malId ?: return@withLock emptyList()
                    skipApi.aniSkip(mal, episode ?: 1, item.durationSec.coerceAtLeast(0))
                }
            }
            // Only a real answer is kept; a failure is asked about again next time.
            var segments = (reply as? MetaReply.Ok)?.value ?: return@withLock emptyList()
            // TheIntroDB had nothing: IntroDB.app, the other community database, by IMDb id.
            if (segments.isEmpty() && info.provider != MetaProvider.AniList) {
                val imdb = extras(info)?.imdbId
                if (imdb != null) {
                    val second = skipApi.introDbApp(imdb, season, episode)
                    segments = (second as? MetaReply.Ok)?.value ?: return@withLock emptyList()
                }
            }
            // An anime matched on TMDB or TVmaze: TheIntroDB has few of those, and neither gives a
            // MyAnimeList id. The same title asked of AniList first finds it if it is an anime,
            // and AniSkip is asked by that. A show AniList does not know comes back as before.
            if (segments.isEmpty() && show && info.provider != MetaProvider.AniList) {
                val anime = (lookup(query.showOnly().copy(anime = true)) as? MetaResult.Found)?.info
                    ?.takeIf { it.provider == MetaProvider.AniList }
                val mal = anime?.let { extras(it)?.malId }
                if (mal != null) {
                    val aniReply = skipApi.aniSkip(mal, episode ?: 1, item.durationSec.coerceAtLeast(0))
                    segments = (aniReply as? MetaReply.Ok)?.value ?: return@withLock emptyList()
                }
            }
            cache.putSkipTimes(key, segments)
            segments
        }
    }

    /**
     * [info] for [item], if anything backs it beyond the title ([MetaConfidence]): the year, the
     * episode code, or failing both the file's length against the runtime, which takes the
     * provider's extras (one call, kept like any other). Null leaves the video with its own name
     * and picture. [name] is how the file reads, the viewer's own title where they gave one.
     */
    suspend fun matched(item: MediaItem, name: com.tmplayer.data.ParsedName, info: MetaInfo): MatchedMeta? {
        MetaConfidence.basis(name, info)?.let { return MatchedMeta(info, it) }
        if (!MetaConfidence.needsRuntime(name, info, item.durationSec)) return null
        val runtime = extras(info)?.runtimeMin
        return MetaConfidence.basis(name, info, item.durationSec, runtime)?.let { MatchedMeta(info, it) }
    }

    suspend fun extras(info: MetaInfo): MetaExtras? {
        if (!store.now.enabled) return null
        val lang = tmdbLanguage()
        val key = extrasKey(info, lang, region())
        rememberedExtras(key)?.let { return it }
        return lockFor(key).withLock {
            rememberedExtras(key)?.let { return@withLock it }
            val found = fetchExtras(info, lang) ?: return@withLock null
            cache.putExtras(key, found)
            extrasMemory[key] = found
            found
        }
    }

    private fun rememberedExtras(key: String): MetaExtras? {
        extrasMemory[key]?.let { return it }
        val stored = cache.extras(key) ?: return null
        extrasMemory[key] = stored
        return stored
    }

    /** The provider's answer, an empty one for a 404, or null when it could not be asked. */
    private suspend fun fetchExtras(info: MetaInfo, lang: String): MetaExtras? {
        if (paused(info.provider)) return null
        val reply: MetaReply<MetaExtras>
        var key: String? = null
        when (info.provider) {
            MetaProvider.Tmdb -> {
                val s = store.now
                if (!tmdbUsable(s)) return null
                key = activeKey(s)
                val k = key
                reply = retrying { tmdb.extras(k, info.kind == MetaKind.Film, info.id, lang, region()) }
            }
            MetaProvider.TvMaze -> reply = retrying { tvmaze.extras(info.id) }
            MetaProvider.AniList -> reply = retrying { anilist.extras(info.id) }
        }
        return when (trouble(info.provider, reply, key)) {
            null -> (reply as MetaReply.Ok).value
            Outcome.Nothing -> MetaExtras()
            else -> null
        }
    }

    // ---- a show's episode lists ----------------------------------------------------------------

    private val seasonMemory = LruMap<String, List<MetaEpisode>>(EXTRAS_MEMORY_ENTRIES)

    private fun seasonKey(info: MetaInfo, season: Int, lang: String): String =
        listOf("season", info.provider.name, info.id, season, lang).joinToString("|")

    /** [season]'s list already in memory, for the first frame. */
    fun peekSeason(info: MetaInfo, season: Int): List<MetaEpisode>? {
        if (!store.now.enabled || info.kind != MetaKind.Show) return null
        return seasonMemory[seasonKey(info, season, tmdbLanguage())]
    }

    /**
     * Every episode of [season] of the show [info], with names, pictures and air dates, so the
     * detail page can list the ones the chat lacks and the ones still to air. TMDB asks for the
     * season; TVmaze answers every season at once, which is kept per season. AniList numbers an
     * anime's episodes without seasons and is not asked. Null while off or when nobody could be
     * asked; kept a day (see [MetadataCache.season]).
     */
    suspend fun season(info: MetaInfo, season: Int): List<MetaEpisode>? {
        if (!store.now.enabled || info.kind != MetaKind.Show || info.provider == MetaProvider.AniList) return null
        val lang = tmdbLanguage()
        val key = seasonKey(info, season, lang)
        seasonMemory[key]?.let { return it }
        return lockFor(key).withLock {
            seasonMemory[key]?.let { return@withLock it }
            cache.season(key)?.let { stored ->
                seasonMemory[key] = stored
                return@withLock stored
            }
            if (paused(info.provider)) return@withLock null
            when (info.provider) {
                MetaProvider.Tmdb -> {
                    val s = store.now
                    if (!tmdbUsable(s)) return@withLock null
                    val k = activeKey(s)
                    val reply = retrying { tmdb.season(k, info.id, season, lang) }
                    val found = when (trouble(info.provider, reply, k)) {
                        null -> (reply as MetaReply.Ok).value
                        Outcome.Nothing -> emptyList()
                        else -> return@withLock null
                    }
                    cache.putSeason(key, found)
                    seasonMemory[key] = found
                    found
                }
                MetaProvider.TvMaze -> {
                    val reply = retrying { tvmaze.episodes(info.id) }
                    val all = when (trouble(info.provider, reply)) {
                        null -> (reply as MetaReply.Ok).value
                        Outcome.Nothing -> emptyList()
                        else -> return@withLock null
                    }
                    // One answer covers every season: keep each, so the next season is no call.
                    all.groupBy { it.season }.forEach { (number, list) ->
                        val k = seasonKey(info, number, lang)
                        cache.putSeason(k, list)
                        seasonMemory[k] = list
                    }
                    val found = all.filter { it.season == season }
                    if (found.isEmpty()) {
                        cache.putSeason(key, found)
                        seasonMemory[key] = found
                    }
                    found
                }
                MetaProvider.AniList -> null
            }
        }
    }

    /**
     * What is already known for [query], from memory or the disk, with no lookup: how "More like
     * this" learns which title a video is without asking anyone about it.
     */
    fun cachedMatch(query: MetaQuery): MetaInfo? {
        if (!store.now.enabled) return null
        return (remembered(query.cacheKey(tmdbLanguage())) as? MetaResult.Found)?.info
    }

    /**
     * [extras]' recommendations narrowed to [items] the viewer has, by the matches already made
     * for them (no new lookups). A show is opened at its earliest episode. Run it off the main
     * thread: a title not in memory is read from the disk.
     */
    fun moreLikeThis(self: MetaInfo, extras: MetaExtras, items: Collection<MediaItem>): List<KnownTitle> {
        if (extras.similar.isEmpty() || items.isEmpty() || !store.now.enabled) return emptyList()
        val wanted = extras.similar.mapTo(HashSet()) { it.matchKey }
        val lang = tmdbLanguage()
        val asked = HashMap<String, MetaInfo?>()
        val best = LinkedHashMap<String, Pair<KnownTitle, Int>>()
        for (item in items) {
            val full = MetaQuery.of(item.nameForParsing, item.caption) ?: continue
            val show = full.showOnly()
            val cacheKey = show.cacheKey(lang)
            val info = if (cacheKey in asked) asked[cacheKey] else cachedMatch(show).also { asked[cacheKey] = it }
            info ?: continue
            val match = MetaRef.matchKey(info.provider, info.kind, info.id)
            if (match !in wanted) continue
            val order = (full.season ?: 0) * 10_000 + (full.episode ?: 0)
            val held = best[match]
            if (held == null || order < held.second) best[match] = KnownTitle(item, info) to order
        }
        return MetaRules.moreLikeThis(extras.similar, self, best.values.map { it.first })
    }

    // ---- pictures -----------------------------------------------------------------------------

    /**
     * The picture at [url] as a file on this device, fetched once and kept with the answers. Only
     * the three providers' own image hosts are fetched from.
     */
    suspend fun image(url: String): CachedImage? {
        cache.image(url)?.let { return it }
        if (!store.now.enabled) return null
        val host = hostOf(url) ?: return null
        if (!url.startsWith("https://") || host !in IMAGE_HOSTS) return null
        return lockFor("image|$url").withLock {
            cache.image(url)?.let { return@withLock it }
            val response = imageGate.withPermit {
                try {
                    http.send(HttpRequest("GET", url, mapOf("User-Agent" to userAgent)))
                } catch (e: IOException) {
                    null
                }
            } ?: return@withLock null
            if (response.code !in 200..299 || response.body.isEmpty()) return@withLock null
            cache.putImage(url, response.body)
        }
    }

    /**
     * Opens the connections to TMDB's API and picture hosts ahead of the first lookup, while the
     * app is still starting and Telegram connecting: the first secure connection of a process
     * (the TLS set up, the certificates read, the handshake) took over a second, and every poster
     * on Home waited behind it (2026-10-21). One small request to each host, whose answer does not
     * matter; the connection stays in the keep-alive pool for the lookups. Nothing while off.
     */
    suspend fun warmUp() {
        if (!store.now.enabled) return
        listOf("https://api.themoviedb.org/3/", "https://image.tmdb.org/t/p/w92/").forEach { url ->
            runCatching { http.send(HttpRequest("HEAD", url, mapOf("User-Agent" to userAgent))) }
        }
    }

    /** Whether the picture at [url] is on this device already: a screen then shows it sharp at once, with no small copy first. */
    fun hasImage(url: String): Boolean = cache.image(url) != null

    // ---- title logos --------------------------------------------------------------------------

    private val logoMemory = LruMap<String, LogoAnswer>(MEMORY_ENTRIES)

    private fun logoKey(info: MetaInfo, lang: String): String =
        listOf("logo", info.provider.name, info.kind.name, info.id, lang).joinToString("|")

    /**
     * What is known of [info]'s title logo without asking anyone: a [LogoAnswer] (its url may be
     * null, meaning the title has none), or null when it has not been looked up yet.
     */
    fun peekLogo(info: MetaInfo): LogoAnswer? {
        if (!store.now.enabled || info.provider != MetaProvider.Tmdb) return LogoAnswer(null)
        return logoMemory[logoKey(info, tmdbLanguage())]
    }

    /**
     * The address of [info]'s title logo, the film's or show's name drawn as a picture with a
     * clear ground (TMDB's `logos`), for the Home carousel to set in place of the name: in the UI
     * language, else English, else one with no words, PNG only, since an SVG would not decode.
     * One call per title, kept with the answers ([MetadataCache.logo]), a title with none
     * included. Null while off, for TVmaze and AniList (who have no logos), or when TMDB could
     * not be asked.
     */
    suspend fun logo(info: MetaInfo): String? {
        peekLogo(info)?.let { return it.url }
        val lang = tmdbLanguage()
        val key = logoKey(info, lang)
        cache.logo(key)?.let { stored ->
            logoMemory[key] = stored
            return stored.url
        }
        return lockFor(key).withLock {
            logoMemory[key]?.let { return@withLock it.url }
            if (paused(MetaProvider.Tmdb)) return@withLock null
            val s = store.now
            if (!tmdbUsable(s)) return@withLock null
            val apiKey = activeKey(s)
            val ui = lang.substringBefore('-').lowercase()
            val languages = listOf(ui, "en", "null").distinct()
            val reply = retrying { tmdb.logos(apiKey, info.kind == MetaKind.Film, info.id, languages) }
            val answer = when (trouble(MetaProvider.Tmdb, reply, apiKey)) {
                null -> LogoAnswer(MetaRules.logo((reply as MetaReply.Ok).value, ui)?.let { tmdb.logoUrl(it) })
                Outcome.Nothing -> LogoAnswer(null)
                else -> return@withLock null
            }
            cache.putLogo(key, answer)
            logoMemory[key] = answer
            answer.url
        }
    }

    // ---- the cache ----------------------------------------------------------------------------

    fun cacheBytes(): Long = cache.bytes()

    /** What the poster and backdrop pictures take, which Free up space counts with the thumbnails. */
    fun imageBytes(): Long = cache.imageBytes()

    /** The pictures, and nothing the titles were matched with. */
    fun purgeImages() {
        cache.purgeImages()
    }

    /** Every answer and picture, from the disk and from memory. */
    fun purge() {
        cache.purge()
        memory.clear()
        extrasMemory.clear()
        seasonMemory.clear()
        logoMemory.clear()
    }

    companion object {
        /** Set once at start by the app. Null in a context that never set it up, such as a test. */
        @Volatile
        var current: OnlineMetadata? = null

        const val ENGLISH = "en-US"
        const val REFUSED_RETRY_MS = 24L * 60 * 60 * 1000
        const val RETRY_MS = 1_000L
        /** Pictures fetched at once: a row's worth on screen, and few enough to leave the video its line. */
        const val IMAGE_PARALLEL = 6
        private const val MEMORY_ENTRIES = 600

        /** A few detail pages' worth: each holds a cast list and a recommendation list. */
        private const val EXTRAS_MEMORY_ENTRIES = 24

        /** Where posters may come from. Nothing else is fetched, whatever an answer says. */
        val IMAGE_HOSTS = setOf("image.tmdb.org", "static.tvmaze.com", "s4.anilist.co", "img.anili.st")

        /** Lookups are on, in a build that set the feature up. */
        val enabled: Boolean get() = current?.store?.now?.enabled == true
    }
}

/** The host of an `https://` URL, as `URI(url).host`, or null. */
internal fun hostOf(url: String): String? {
    val rest = url.substringAfter("://", "").takeIf { it.isNotEmpty() } ?: return null
    val authority = rest.substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
    return authority.substringBefore(':').lowercase().takeIf { it.isNotEmpty() }
}
