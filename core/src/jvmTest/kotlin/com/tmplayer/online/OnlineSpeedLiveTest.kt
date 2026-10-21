package com.tmplayer.online

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong

/**
 * How long a screenful of posters takes from a cold cache, against the real TMDB: a dozen films
 * looked up together, as a Home row's tiles ask, each poster fetched as soon as its answer is in.
 * Prints the time to the first poster on disk and to the last. Skipped unless TMPLAYER_LIVE=1;
 * the key is read as [OnlineMetadataLiveTest] reads it and never printed.
 */
class OnlineSpeedLiveTest {

    private val live = System.getenv("TMPLAYER_LIVE") == "1"

    private fun tmdbKey(): String = System.getenv("TMDB_API_KEY")?.takeIf { it.isNotBlank() }
        ?: listOf(File("local.properties"), File("../local.properties")).firstOrNull { it.isFile }?.let { f ->
            Properties().apply { f.inputStream().use { load(it) } }.getProperty("TMDB_API_KEY")
        }.orEmpty()

    private val names = listOf(
        "Big.Buck.Bunny.2008.1080p.mkv", "Sintel.2010.720p.mkv", "Tears.of.Steel.2012.mkv",
        "Inception.2010.1080p.mkv", "Interstellar.2014.1080p.mkv", "Arrival.2016.720p.mkv",
        "Dune.2021.1080p.mkv", "Parasite.2019.1080p.mkv", "Coco.2017.720p.mkv",
        "Up.2009.1080p.mkv", "Heat.1995.1080p.mkv", "Alien.1979.1080p.mkv",
    )

    @Test
    fun aScreenfulOfPostersFromCold(): Unit = runBlocking {
        assumeTrue(live)
        val key = tmdbKey()
        assumeTrue("no TMDB key", key.isNotBlank())
        val dir = Files.createTempDirectory("tm-meta-speed").toFile()
        val store = MetadataStore(File(dir, "m.properties")).apply { update { it.copy(enabled = true) } }
        val online = OnlineMetadata(key, store, MetadataCache(File(dir, "cache")), "speed-test", language = { "en" })
        val start = System.nanoTime()
        val firstPoster = AtomicLong(0)
        val firstTiny = AtomicLong(0)
        coroutineScope {
            names.map { name ->
                async {
                    val info = (online.lookup(MetaQuery.of(name)!!) as? MetaResult.Found)?.info ?: return@async
                    val url = info.posterUrl ?: return@async
                    val tiny = MetaImageSize.tiny(url)
                    if (tiny != null && online.image(tiny) != null) firstTiny.compareAndSet(0, System.nanoTime() - start)
                    if (online.image(url) != null) firstPoster.compareAndSet(0, System.nanoTime() - start)
                }
            }.awaitAll()
        }
        val all = System.nanoTime() - start
        println(
            "LIVE speed: first tiny ${firstTiny.get() / 1_000_000} ms, first poster ${firstPoster.get() / 1_000_000} ms, " +
                "all ${names.size} in ${all / 1_000_000} ms",
        )
        dir.deleteRecursively()
    }
}
