package com.tmplayer.online

import com.tmplayer.json.JSONObject
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The scores a detail page lists: OMDb's answer read into IMDb, Rotten Tomatoes and Metacritic,
 * Wikidata's Rotten Tomatoes and Metacritic where OMDb is not there or lacks them, each provider's
 * own score, the merge in [ScoreSource] order, OMDb asked once per title (again after 30 days)
 * and left alone for the day once its key is refused or spent, the viewer's own key before the
 * build's, all over a fake network.
 */
class MetaScoresTest {

    private class FakeHttp : HttpTransport {
        val requests = mutableListOf<HttpRequest>()
        val rules = mutableListOf<Triple<String, Int, String>>()

        fun on(part: String, code: Int = 200, body: () -> String) {
            rules.removeAll { it.first == part }
            rules += Triple(part, code, body())
        }

        override suspend fun send(request: HttpRequest): HttpResponse {
            requests += request
            val rule = rules.firstOrNull { (part, _, _) -> part in request.url || request.body?.contains(part) == true }
                ?: return HttpResponse(404, body = "{}".toByteArray())
            return HttpResponse(rule.second, body = rule.third.toByteArray())
        }
    }

    private var clock = 1_800_000_000_000L
    private val http = FakeHttp()
    private val dir: File = Files.createTempDirectory("tm-scores").toFile()

    private fun metadata(omdbKey: String = "omdb-key", ownOmdbKey: String = "") = OnlineMetadata(
        buildKey = "build-key",
        store = MetadataStore(File(dir, "metadata.properties")).also { s -> s.update { it.copy(enabled = true, omdbKey = ownOmdbKey) } },
        cache = MetadataCache(File(dir, "cache"), now = { clock }),
        appVersion = "test",
        http = http,
        now = { clock },
        sleep = { clock += it },
        language = { "en" },
        region = { "US" },
        omdbKey = omdbKey,
    )

    private val film = MetaInfo(MetaProvider.Tmdb, "603", MetaKind.Film, "The Matrix", 1999)
    private val filmPage = """{"id":603,"imdb_id":"tt0133093","genres":[],"runtime":136,"vote_average":8.2,"vote_count":26000}"""
    private val omdbPage = """{"Title":"The Matrix","imdbRating":"8.7","imdbVotes":"2,123,456","Metascore":"73",
        "Ratings":[{"Source":"Internet Movie Database","Value":"8.7/10"},{"Source":"Rotten Tomatoes","Value":"83%"},
        {"Source":"Metacritic","Value":"73/100"}],"Response":"True"}"""

    private fun omdbRequests() = http.requests.count { "omdbapi.com" in it.url }
    private fun wikidataRequests() = http.requests.count { "query.wikidata.org" in it.url }

    /** One review score as the Query Service binds it. */
    private fun binding(site: String, score: String, at: String? = null, method: String? = null, rank: String = "NormalRank"): String {
        val parts = mutableListOf(
            """"src":{"type":"uri","value":"http://www.wikidata.org/entity/$site"}""",
            """"score":{"type":"literal","value":"$score"}""",
            """"rank":{"type":"uri","value":"http://wikiba.se/ontology#$rank"}""",
        )
        at?.let { parts += """"when":{"datatype":"http://www.w3.org/2001/XMLSchema#dateTime","type":"literal","value":"$it"}""" }
        method?.let { parts += """"method":{"type":"uri","value":"http://www.wikidata.org/entity/$it"}""" }
        return parts.joinToString(",", "{", "}")
    }

    private fun wikidataPage(vararg bindings: String) =
        """{"head":{"vars":["src","score","when","method","rank"]},"results":{"bindings":[${bindings.joinToString(",")}]}}"""

    private val RT = WikidataApi.ROTTEN_TOMATOES
    private val MC = WikidataApi.METACRITIC

    /** The Matrix as Wikidata had it on 2026-10-22: an older Tomatometer added for the test, and the average of rated reviews. */
    private val wikidataMatrix = wikidataPage(
        binding(RT, "7.8/10", "2021-09-11T00:00:00Z", "Q108403540"),
        binding(MC, "73/100", "2026-01-16T00:00:00Z", WikidataApi.METASCORE),
        binding(RT, "88%", "2019-01-01T00:00:00Z", WikidataApi.TOMATOMETER),
        binding(RT, "83%", "2026-01-16T00:00:00Z", WikidataApi.TOMATOMETER),
    )

    // ---- OMDb's answer ------------------------------------------------------------------------

    @Test
    fun omdbGivesImdbRottenTomatoesAndMetacritic() {
        val reply = OmdbApi.read(JSONObject(omdbPage)) as MetaReply.Ok
        assertEquals(
            listOf(MetaScore(ScoreSource.Imdb, 8.7), MetaScore(ScoreSource.RottenTomatoes, 83.0), MetaScore(ScoreSource.Metacritic, 73.0)),
            reply.value,
        )
    }

    @Test
    fun notAvailableFieldsAreLeftOut() {
        val json = JSONObject("""{"imdbRating":"N/A","imdbVotes":"N/A","Metascore":"N/A","Ratings":[],"Response":"True"}""")
        assertEquals(emptyList<MetaScore>(), (OmdbApi.read(json) as MetaReply.Ok).value)
    }

    @Test
    fun metascoreStandsInWhenRatingsHasNoMetacritic() {
        val json = JSONObject("""{"imdbRating":"7.1","imdbVotes":"900","Metascore":"64","Ratings":[],"Response":"True"}""")
        assertEquals(
            listOf(MetaScore(ScoreSource.Imdb, 7.1), MetaScore(ScoreSource.Metacritic, 64.0)),
            (OmdbApi.read(json) as MetaReply.Ok).value,
        )
    }

    @Test
    fun anImdbRatingFromFewVotesIsLeftOut() {
        val json = JSONObject("""{"imdbRating":"9.9","imdbVotes":"12","Ratings":[{"Source":"Rotten Tomatoes","Value":"100%"}],"Response":"True"}""")
        assertEquals(listOf(MetaScore(ScoreSource.RottenTomatoes, 100.0)), (OmdbApi.read(json) as MetaReply.Ok).value)
    }

    @Test
    fun aSpentDayOrABadKeyIsARefusalAndAnUnknownTitleIsEmpty() {
        assertEquals(MetaReply.Throttled(OmdbApi.SPENT), OmdbApi.read(JSONObject("""{"Response":"False","Error":"Request limit reached!"}""")))
        assertEquals(MetaReply.Refused, OmdbApi.read(JSONObject("""{"Response":"False","Error":"Invalid API key!"}""")))
        assertEquals(MetaReply.Ok(emptyList<MetaScore>()), OmdbApi.read(JSONObject("""{"Response":"False","Error":"Incorrect IMDb ID."}""")))
    }

    // ---- each provider's own ------------------------------------------------------------------

    @Test
    fun tvmazeAverageIsItsScoreButNotTheRating() = runBlocking {
        val show = MetaInfo(MetaProvider.TvMaze, "169", MetaKind.Show, "Breaking Bad", 2008)
        http.on("shows/169") { """{"id":169,"genres":["Drama"],"rating":{"average":9.24},"externals":{}}""" }
        val extras = metadata().extras(show) ?: error("no extras")
        assertEquals("no vote count, so no rating", null, extras.rating)
        assertEquals(listOf(MetaScore(ScoreSource.TvMaze, 9.2)), extras.scores)
    }

    @Test
    fun tvmazeWithoutAnAverageHasNoScore() = runBlocking {
        val show = MetaInfo(MetaProvider.TvMaze, "170", MetaKind.Show, "New", 2026)
        http.on("shows/170") { """{"id":170,"rating":{"average":null}}""" }
        val extras = metadata().extras(show) ?: error("no extras")
        assertEquals(null, extras.rating)
        assertTrue(extras.scores.isEmpty())
    }

    @Test
    fun anilistAverageScoreIsAPercentage() = runBlocking {
        val anime = MetaInfo(MetaProvider.AniList, "21", MetaKind.Show, "One Piece", 1999)
        http.on("graphql.anilist.co") { """{"data":{"Media":{"averageScore":88,"genres":[]}}}""" }
        val extras = metadata().extras(anime) ?: error("no extras")
        assertEquals(listOf(MetaScore(ScoreSource.AniList, 88.0)), extras.scores)
    }

    // ---- OMDb asked from the detail page ------------------------------------------------------

    @Test
    fun omdbScoresGoInFrontOfTheProvidersAndAreAskedOnce() = runBlocking {
        http.on("movie/603") { filmPage }
        http.on("omdbapi.com") { omdbPage }
        val online = metadata()
        val extras = online.extras(film) ?: error("no extras")
        assertEquals(
            listOf(ScoreSource.Imdb, ScoreSource.RottenTomatoes, ScoreSource.Metacritic, ScoreSource.Tmdb),
            extras.scores.map { it.source },
        )
        assertTrue(extras.scoresAsked)
        assertTrue(http.requests.first { "omdbapi.com" in it.url }.url.contains("i=tt0133093"))
        online.extras(film)
        // A fresh instance reads the cached answer, scores and all, and asks nobody.
        val again = metadata().extras(film) ?: error("no extras")
        assertEquals(extras.scores, again.scores)
        assertEquals(1, omdbRequests())
        assertEquals("OMDb had both, so Wikidata is not asked", 0, wikidataRequests())
    }

    @Test
    fun aTitleOmdbDoesNotKnowIsMarkedAskedWithTheProvidersScore() = runBlocking {
        http.on("movie/603") { filmPage }
        http.on("omdbapi.com") { """{"Response":"False","Error":"Incorrect IMDb ID."}""" }
        val extras = metadata().extras(film) ?: error("no extras")
        assertEquals(listOf(MetaScore(ScoreSource.Tmdb, 8.2)), extras.scores)
        assertTrue(extras.scoresAsked)
    }

    @Test
    fun aSpentDayLeavesTheTitleUnaskedAndOmdbAloneUntilTomorrow() = runBlocking {
        http.on("movie/603") { filmPage }
        http.on("omdbapi.com", 401) { """{"Response":"False","Error":"Request limit reached!"}""" }
        val online = metadata()
        val extras = online.extras(film) ?: error("no extras")
        assertFalse(extras.scoresAsked)
        assertEquals(listOf(MetaScore(ScoreSource.Tmdb, 8.2)), extras.scores)
        online.extras(film)
        assertEquals(1, omdbRequests())
        // The next UTC day it is asked again, and the kept answer gains its scores.
        clock += 24L * 60 * 60 * 1000
        http.on("omdbapi.com") { omdbPage }
        val later = online.extras(film) ?: error("no extras")
        assertTrue(later.scoresAsked)
        assertEquals(4, later.scores.size)
        assertEquals(2, omdbRequests())
    }

    @Test
    fun noKeyAsksOmdbNothing() = runBlocking {
        http.on("movie/603") { filmPage }
        val extras = metadata(omdbKey = "").extras(film) ?: error("no extras")
        assertFalse(extras.scoresAsked)
        assertEquals(0, omdbRequests())
    }

    @Test
    fun anAnswerKeptBeforeScoresGainsTheProvidersAndOmdbsOnce() = runBlocking {
        // As an older build kept it: the rating alone, no scores, never asked.
        val old = MetaExtras(rating = 8.2, imdbId = "tt0133093")
        val cache = MetadataCache(File(dir, "cache"), now = { clock })
        cache.putExtras(listOf("extras2", "Tmdb", "Film", "603", "en-US", "US").joinToString("|"), old)
        http.on("omdbapi.com") { omdbPage }
        val extras = metadata().extras(film) ?: error("no extras")
        assertEquals(
            listOf(ScoreSource.Imdb, ScoreSource.RottenTomatoes, ScoreSource.Metacritic, ScoreSource.Tmdb),
            extras.scores.map { it.source },
        )
        assertEquals(0, http.requests.count { "themoviedb" in it.url })
        assertEquals(1, omdbRequests())
    }


    // ---- Wikidata ------------------------------------------------------------------------------

    @Test
    fun wikidataGivesTheNewestTomatometerAndMetascore() {
        assertEquals(
            listOf(MetaScore(ScoreSource.RottenTomatoes, 83.0), MetaScore(ScoreSource.Metacritic, 73.0)),
            WikidataApi.read(JSONObject(wikidataMatrix)),
        )
    }

    @Test
    fun wikidataSkipsAveragesUserScoresAndDeprecatedStatements() {
        val page = wikidataPage(
            binding(RT, "8.1/10", "2026-01-01T00:00:00Z", "Q108403540"),
            binding(MC, "8.9/10", "2026-01-01T00:00:00Z", "Q109000000"),
            binding(RT, "99%", "2026-02-01T00:00:00Z", WikidataApi.TOMATOMETER, rank = "DeprecatedRank"),
            binding(RT, "87%", "2022-11-29T00:00:00Z"),
            binding(MC, "74/100"),
        )
        assertEquals(
            listOf(MetaScore(ScoreSource.RottenTomatoes, 87.0), MetaScore(ScoreSource.Metacritic, 74.0)),
            WikidataApi.read(JSONObject(page)),
        )
    }

    @Test
    fun wikidataWithoutTheTitleOrItsScoresIsEmpty() {
        assertEquals(emptyList<MetaScore>(), WikidataApi.read(JSONObject(wikidataPage())))
        assertEquals(emptyList<MetaScore>(), WikidataApi.read(JSONObject("{}")))
    }

    @Test
    fun wikidataValuesAreReadOnTheirScale() {
        assertEquals(92.0 to 100, WikidataApi.value("92%"))
        assertEquals(78.0 to 100, WikidataApi.value("78/100"))
        assertEquals(7.8 to 10, WikidataApi.value("7.8/10"))
        assertEquals(null, WikidataApi.value("11/10"))
        assertEquals(null, WikidataApi.value("4 stars"))
        assertEquals(null, WikidataApi.value(""))
    }

    @Test
    fun wikidataIsAskedByImdbIdWithTheAppsName() = runBlocking {
        http.on("query.wikidata.org") { wikidataMatrix }
        assertEquals(
            MetaReply.Ok(listOf(MetaScore(ScoreSource.RottenTomatoes, 83.0), MetaScore(ScoreSource.Metacritic, 73.0))),
            WikidataApi(http, RateLimiter(0, { clock }, { clock += it }), "TMPlayer/test (+https://tmplayer.org)").scores("tt0133093"),
        )
        val request = http.requests.single()
        assertTrue(java.net.URLDecoder.decode(request.url, "UTF-8").contains("wdt:P345 \"tt0133093\""))
        assertTrue(request.headers.getValue("Api-User-Agent").startsWith("TMPlayer/"))
        // Anything that is not an IMDb id is never put in a query.
        assertEquals(MetaReply.Ok(emptyList<MetaScore>()), WikidataApi(http, RateLimiter(0, { clock }, { clock += it }), "t").scores("tt1\" }"))
        assertEquals(1, http.requests.size)
    }

    // ---- which site answers ----------------------------------------------------------------------

    @Test
    fun withoutAnOmdbKeyWikidataAnswersOnceAndThePageStopsAsking() = runBlocking {
        http.on("movie/603") { filmPage }
        http.on("query.wikidata.org") { wikidataMatrix }
        val online = metadata(omdbKey = "")
        assertEquals(OmdbState.NoKey, online.omdbState())
        val extras = online.extras(film) ?: error("no extras")
        assertEquals(
            listOf(MetaScore(ScoreSource.RottenTomatoes, 83.0), MetaScore(ScoreSource.Metacritic, 73.0), MetaScore(ScoreSource.Tmdb, 8.2)),
            extras.scores,
        )
        assertTrue(extras.scoresAsked)
        assertFalse(extras.scoresFromOmdb)
        assertTrue("no IMDb score is made up", extras.scores.none { it.source == ScoreSource.Imdb })
        online.extras(film)
        metadata(omdbKey = "").extras(film)
        assertEquals(1, wikidataRequests())
        assertEquals(0, omdbRequests())
    }

    @Test
    fun omdbsScoresWinAndWikidataFillsWhatOmdbLacks() = runBlocking {
        http.on("movie/603") { filmPage }
        http.on("omdbapi.com") { """{"imdbRating":"8.7","imdbVotes":"2,000,000","Ratings":[{"Source":"Rotten Tomatoes","Value":"84%"}],"Response":"True"}""" }
        http.on("query.wikidata.org") { wikidataMatrix }
        val extras = metadata().extras(film) ?: error("no extras")
        assertEquals(
            listOf(
                MetaScore(ScoreSource.Imdb, 8.7),
                MetaScore(ScoreSource.RottenTomatoes, 84.0),
                MetaScore(ScoreSource.Metacritic, 73.0),
                MetaScore(ScoreSource.Tmdb, 8.2),
            ),
            extras.scores,
        )
        assertTrue(extras.scoresFromOmdb)
        assertEquals(1, wikidataRequests())
    }

    @Test
    fun aSpentOmdbDayFallsBackToWikidataAndAsksOmdbTheNextDay() = runBlocking {
        http.on("movie/603") { filmPage }
        http.on("omdbapi.com", 401) { """{"Response":"False","Error":"Request limit reached!"}""" }
        http.on("query.wikidata.org") { wikidataMatrix }
        val online = metadata()
        val extras = online.extras(film) ?: error("no extras")
        assertTrue(extras.scoresAsked)
        assertEquals(listOf(ScoreSource.RottenTomatoes, ScoreSource.Metacritic, ScoreSource.Tmdb), extras.scores.map { it.source })
        online.extras(film)
        assertEquals(1, omdbRequests())
        assertEquals(1, wikidataRequests())
        clock += 24L * 60 * 60 * 1000
        http.on("omdbapi.com") { omdbPage }
        val later = online.extras(film) ?: error("no extras")
        assertEquals(MetaScore(ScoreSource.Imdb, 8.7), later.scores.first())
        assertTrue(later.scoresFromOmdb)
        assertEquals(2, omdbRequests())
        assertEquals(1, wikidataRequests())
    }

    @Test
    fun scoresAreKeptThirtyDaysThenAskedAgain() = runBlocking {
        http.on("movie/603") { filmPage }
        http.on("omdbapi.com") { omdbPage }
        val online = metadata()
        online.extras(film)
        clock += 29L * 24 * 60 * 60 * 1000
        online.extras(film)
        assertEquals(1, omdbRequests())
        clock += 2L * 24 * 60 * 60 * 1000
        http.on("omdbapi.com") { omdbPage.replace("8.7", "8.8") }
        val later = metadata().extras(film) ?: error("no extras")
        assertEquals(2, omdbRequests())
        assertEquals(MetaScore(ScoreSource.Imdb, 8.8), later.scores.first())
        assertEquals(clock / 1000, later.scoresAt)
    }

    // ---- the viewer's own key ------------------------------------------------------------------

    @Test
    fun theViewersOwnKeyIsUsedBeforeTheBuilds() = runBlocking {
        http.on("movie/603") { filmPage }
        http.on("omdbapi.com") { omdbPage }
        val online = metadata(ownOmdbKey = "mine")
        assertEquals(OmdbState.OwnKey, online.omdbState())
        online.extras(film)
        val url = http.requests.single { "omdbapi.com" in it.url }.url
        assertTrue(url.contains("apikey=mine"))
        assertFalse(url.contains("omdb-key"))
    }

    @Test
    fun aRefusedOwnKeyIsShownAndLeftAloneUntilItChanges() = runBlocking {
        http.on("movie/603") { filmPage }
        http.on("omdbapi.com", 401) { """{"Response":"False","Error":"Invalid API key!"}""" }
        http.on("query.wikidata.org") { wikidataMatrix }
        val online = metadata(ownOmdbKey = "wrong")
        val extras = online.extras(film) ?: error("no extras")
        assertEquals(OmdbState.OwnKeyRefused, online.omdbState())
        assertTrue("Wikidata still answered", extras.scoresAsked)
        clock += 2L * 24 * 60 * 60 * 1000
        online.extras(film)
        assertEquals(1, omdbRequests())
        // A new key: OMDb is asked on the next visit, and the page gains IMDb's score.
        http.on("omdbapi.com") { omdbPage }
        online.setOmdbKey("right")
        assertEquals(OmdbState.OwnKey, online.omdbState())
        val later = online.extras(film) ?: error("no extras")
        assertEquals(2, omdbRequests())
        assertEquals(MetaScore(ScoreSource.Imdb, 8.7), later.scores.first())
        assertTrue(http.requests.last { "omdbapi.com" in it.url }.url.contains("apikey=right"))
    }

    @Test
    fun aKeyAddedLaterAsksOmdbForPagesWikidataAnswered() = runBlocking {
        http.on("movie/603") { filmPage }
        http.on("query.wikidata.org") { wikidataMatrix }
        http.on("omdbapi.com") { omdbPage }
        val online = metadata(omdbKey = "")
        online.extras(film)
        assertEquals(0, omdbRequests())
        online.setOmdbKey("mine")
        val later = online.extras(film) ?: error("no extras")
        assertEquals(1, omdbRequests())
        assertEquals(4, later.scores.size)
        online.extras(film)
        assertEquals(1, omdbRequests())
        assertEquals(1, wikidataRequests())
    }

    @Test
    fun aKeptOwnKeyIsReadBack() {
        val store = MetadataStore(File(dir, "keys.properties"))
        store.update { it.copy(omdbKey = "mine", omdbRefusedKey = "abc") }
        val back = MetadataStore(File(dir, "keys.properties")).now
        assertEquals("mine", back.omdbKey)
        assertEquals("abc", back.omdbRefusedKey)
    }

    // ---- the list itself ----------------------------------------------------------------------

    @Test
    fun mergeKeepsOnePerSourceInOrder() {
        val merged = MetaScore.merge(
            listOf(MetaScore(ScoreSource.Metacritic, 70.0), MetaScore(ScoreSource.Imdb, 8.0)),
            listOf(MetaScore(ScoreSource.Tmdb, 7.5), MetaScore(ScoreSource.Imdb, 1.0)),
        )
        assertEquals(
            listOf(MetaScore(ScoreSource.Imdb, 8.0), MetaScore(ScoreSource.Metacritic, 70.0), MetaScore(ScoreSource.Tmdb, 7.5)),
            merged,
        )
    }

    @Test
    fun scoresSurviveTheJsonRoundTrip() {
        val extras = MetaExtras(
            rating = 8.2,
            scores = listOf(MetaScore(ScoreSource.Imdb, 8.7), MetaScore(ScoreSource.RottenTomatoes, 83.0), MetaScore(ScoreSource.Tmdb, 8.2)),
            scoresAsked = true,
        )
        val back = MetaExtras.fromJson(JSONObject(extras.toJson().toString())) ?: error("not read")
        assertEquals(extras.scores, back.scores)
        assertTrue(back.scoresAsked)
        val before = MetaExtras.fromJson(JSONObject("""{"rating":7.0}""")) ?: error("not read")
        assertTrue(before.scores.isEmpty())
        assertFalse(before.scoresAsked)
        val dated = MetaExtras(scoresAsked = true, scoresAt = 1_800_000_000L, scoresFromOmdb = true)
        val datedBack = MetaExtras.fromJson(JSONObject(dated.toJson().toString())) ?: error("not read")
        assertEquals(1_800_000_000L, datedBack.scoresAt)
        assertTrue(datedBack.scoresFromOmdb)
    }

    @Test
    fun scoresAloneAreNotEmpty() {
        assertTrue(MetaExtras().isEmpty)
        assertFalse(MetaExtras(scores = listOf(MetaScore(ScoreSource.RottenTomatoes, 83.0))).isEmpty)
    }
}
