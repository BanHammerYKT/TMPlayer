package com.tmplayer.online

/**
 * The address of a larger copy of a provider's picture, for a screen that draws it larger than
 * the copy the metadata names.
 *
 * The answers name one size each (TMDB's `w342` poster and `w780` backdrop, TVmaze's `medium`
 * pictures), picked for a phone. A desktop window draws a poster card at 200 to 400 pixels across
 * and the Home carousel at the window's whole width, two or three times that on a high density
 * screen, so the named copy is stretched there and looks soft. [atLeast] gives the smallest size
 * the provider offers that is at least [atLeast]'s `pixels` wide, never smaller than the copy
 * named, or the address unchanged when there is nothing larger or the address is not one it
 * knows. Nothing is re-encoded: each size is a file the provider serves as it is.
 */
object MetaImageSize {

    /** [url] at the smallest size its provider offers that is [pixels] wide or more. */
    fun atLeast(url: String, pixels: Int): String {
        if (pixels <= 0) return url
        tmdb(url, pixels)?.let { return it }
        tvmaze(url, pixels)?.let { return it }
        anilist(url, pixels)?.let { return it }
        return url
    }

    private fun tmdb(url: String, pixels: Int): String? {
        val match = TMDB.matchEntire(url) ?: return null
        val (head, size, tail) = match.destructured
        val width = size.removePrefix("w").toIntOrNull() ?: return null
        if (pixels <= width) return url
        val ladder = TMDB_LADDERS[size] ?: return url
        val next = ladder.firstOrNull { (TMDB_WIDTHS[it] ?: Int.MAX_VALUE) >= pixels } ?: ladder.last()
        return "$head$next$tail"
    }

    private fun tvmaze(url: String, pixels: Int): String? {
        val match = TVMAZE.matchEntire(url) ?: return null
        val (head, size, tail) = match.destructured
        val width = if (size == "medium_portrait") TVMAZE_PORTRAIT else TVMAZE_LANDSCAPE
        return if (pixels <= width) url else "${head}original_untouched$tail"
    }

    private fun anilist(url: String, pixels: Int): String? {
        val match = ANILIST.matchEntire(url) ?: return null
        val (head, size, tail) = match.destructured
        val width = if (size == "medium") ANILIST_MEDIUM else ANILIST_LARGE
        if (pixels <= width) return url
        val next = if (size == "medium" && pixels <= ANILIST_LARGE) "large" else "extraLarge"
        return "$head$next$tail"
    }

    private val TMDB = Regex("""(https://image\.tmdb\.org/t/p/)(w\d+)(/.+)""")
    private val TVMAZE = Regex("""(https://static\.tvmaze\.com/uploads/images/)(medium_portrait|medium_landscape)(/.+)""")
    private val ANILIST = Regex("""(https://s4\.anilist\.co/file/anilistcdn/media/[a-z]+/cover/)(medium|large)(/.+)""")

    /**
     * What each named TMDB size steps up to. The sizes differ by kind of picture (posters, backdrops,
     * stills, faces), and the size named tells the kind: `w342` and `w500` are posters, `w780` and
     * `w1280` backdrops, `w300` a still and `w185` a face, which go straight to the original.
     */
    private val TMDB_LADDERS = mapOf(
        "w92" to listOf("w185", "w342", "w500", "w780", "original"),
        "w154" to listOf("w185", "w342", "w500", "w780", "original"),
        "w185" to listOf("original"),
        "w300" to listOf("original"),
        "w342" to listOf("w500", "w780", "original"),
        "w500" to listOf("w780", "original"),
        "w780" to listOf("w1280", "original"),
        "w1280" to listOf("original"),
    )
    private val TMDB_WIDTHS = mapOf("w185" to 185, "w342" to 342, "w500" to 500, "w780" to 780, "w1280" to 1280)

    private const val TVMAZE_PORTRAIT = 210
    private const val TVMAZE_LANDSCAPE = 250
    private const val ANILIST_MEDIUM = 100
    private const val ANILIST_LARGE = 230
}
