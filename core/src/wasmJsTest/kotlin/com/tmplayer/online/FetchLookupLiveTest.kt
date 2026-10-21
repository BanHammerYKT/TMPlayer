package com.tmplayer.online

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private fun env(name: String): String = js("(typeof process !== 'undefined' && process.env[name]) || ''")

/** TMDB_API_KEY from the environment, else from local.properties in a folder above the test's. */
private fun tmdbKey(): String = js("""(() => {
  if (typeof process === 'undefined') return '';
  if (process.env.TMDB_API_KEY) return process.env.TMDB_API_KEY;
  const fs = process.getBuiltinModule && process.getBuiltinModule('fs');
  const path = process.getBuiltinModule && process.getBuiltinModule('path');
  if (!fs || !path) return '';
  let dir = process.cwd();
  for (let i = 0; i < 8; i++) {
    const f = path.join(dir, 'local.properties');
    if (fs.existsSync(f)) {
      const m = /^TMDB_API_KEY=(.*)$/m.exec(fs.readFileSync(f, 'utf8'));
      return m ? m[1].trim() : '';
    }
    dir = path.dirname(dir);
  }
  return '';
})()""")

/**
 * The web build's lookup over [FetchTransport] against the real TMDB, as OnlineMetadataLiveTest
 * does on the JVM: the same matching, through fetch. Skipped unless TMPLAYER_LIVE=1 and a
 * TMDB key (TMDB_API_KEY, or local.properties) is there; the key is never printed.
 */
class FetchLookupLiveTest {

    private class Settings : MetaSettingsStore {
        private val state = MutableStateFlow(MetaSettings(enabled = true))
        override val settings: StateFlow<MetaSettings> = state
        override val now: MetaSettings get() = state.value
        override fun update(change: (MetaSettings) -> MetaSettings) {
            state.value = change(state.value)
        }
    }

    private class Memory : MetaCacheStore {
        val answers = HashMap<String, MetaResult>()
        val images = HashMap<String, java.io.File>()
        override fun skipTimes(key: String): List<SkipSegment>? = null
        override fun putSkipTimes(key: String, segments: List<SkipSegment>) {}
        override fun answer(key: String): MetaResult? = answers[key]
        override fun putAnswer(key: String, result: MetaResult) {
            answers[key] = result
        }
        override fun extras(key: String): MetaExtras? = null
        override fun putExtras(key: String, value: MetaExtras) {}
        override fun logo(key: String): LogoAnswer? = null
        override fun putLogo(key: String, answer: LogoAnswer) {}
        override fun season(key: String): List<MetaEpisode>? = null
        override fun putSeason(key: String, episodes: List<MetaEpisode>) {}
        override fun image(url: String): CachedImage? = images[url]
        override fun putImage(url: String, bytes: ByteArray): CachedImage? =
            java.io.File("/live/" + images.size).also { it.writeBytes(bytes); images[url] = it }
        override fun prune() {}
        override fun bytes(): Long = 0
        override fun imageBytes(): Long = 0
        override fun purgeImages() {}
        override fun purge() {}
    }

    @Test
    fun tmdbFindsAWellKnownFilmOverFetch() = runTest {
        val key = tmdbKey()
        if (env("TMPLAYER_LIVE") != "1" || key.isBlank()) return@runTest
        val cache = Memory()
        val online = OnlineMetadata(key, Settings(), cache, "live-test", language = { "en" }, region = { "US" })
        val info = (online.lookup(MetaQuery.of("Big.Buck.Bunny.2008.1080p.BluRay.x264.mkv")!!) as MetaResult.Found).info
        assertEquals(MetaProvider.Tmdb, info.provider)
        assertEquals("10378", info.id)
        assertTrue(info.overview.isNotBlank(), "an overview")
        val poster = online.image(info.posterUrl!!)
        assertTrue((poster?.length() ?: 0) > 5_000, "the poster downloads")
        val logo = online.logo(info)
        println("LIVE fetch tmdb: ${info.title} (${info.year}) id ${info.id}, poster ${info.posterUrl} ${poster?.length()} bytes, backdrop ${info.backdropUrl}, logo $logo")
    }

    @Test
    fun tvmazeFindsAShowAndItsEpisodeOverFetch() = runTest {
        if (env("TMPLAYER_LIVE") != "1") return@runTest
        val online = OnlineMetadata("", Settings(), Memory(), "live-test", language = { "en" }, region = { "US" })
        val info = (online.lookup(MetaQuery.of("Breaking.Bad.S01E02.720p.mkv")!!) as MetaResult.Found).info
        assertEquals(MetaProvider.TvMaze, info.provider)
        assertTrue(info.episode?.name.orEmpty().isNotBlank(), "the episode's name")
        val poster = online.image(info.posterUrl!!)
        assertTrue((poster?.length() ?: 0) > 5_000, "the poster downloads")
        println("LIVE fetch tvmaze: ${info.title} id ${info.id}, S01E02 \"${info.episode?.name}\", poster ${poster?.length()} bytes")
    }
}
