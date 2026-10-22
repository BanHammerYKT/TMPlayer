package com.tmplayer.online

import com.tmplayer.json.JSONArray
import com.tmplayer.json.JSONObject

/** Where a score comes from, in the order a page lists them. */
enum class ScoreSource { Imdb, RottenTomatoes, Metacritic, Tmdb, AniList, TvMaze }

/**
 * One site's score for a title, as that site gives it: out of ten for IMDb, TMDB and TVmaze, a
 * percentage for Rotten Tomatoes' critics and AniList, out of a hundred for Metacritic.
 */
data class MetaScore(val source: ScoreSource, val value: Double) {
    /** Out of ten, or out of a hundred. */
    val outOfHundred: Boolean
        get() = source == ScoreSource.RottenTomatoes || source == ScoreSource.Metacritic || source == ScoreSource.AniList

    /** Rotten Tomatoes' own line: fresh at 60 per cent and over, rotten under it. */
    val fresh: Boolean get() = value >= FRESH

    companion object {
        const val FRESH = 60.0

        /** [first] and [then] as one list, one score per source ([first]'s where both have one), in [ScoreSource] order. */
        fun merge(first: List<MetaScore>, then: List<MetaScore>): List<MetaScore> =
            (first + then).distinctBy { it.source }.sortedBy { it.source.ordinal }

        fun listToJson(scores: List<MetaScore>): JSONArray =
            JSONArray(scores.map { JSONObject().put("source", it.source.name).put("value", it.value) })

        fun listFromJson(array: JSONArray?): List<MetaScore> {
            array ?: return emptyList()
            return (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val source = ScoreSource.entries.firstOrNull { it.name == o.optString("source") } ?: return@mapNotNull null
                val value = o.optDouble("value", 0.0).takeIf { it > 0.0 && !it.isNaN() } ?: return@mapNotNull null
                MetaScore(source, value)
            }.sortedBy { it.source.ordinal }
        }
    }
}
