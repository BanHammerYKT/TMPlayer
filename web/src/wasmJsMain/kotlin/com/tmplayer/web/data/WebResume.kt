package com.tmplayer.web.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Where the viewer stopped in a video, and when. */
data class ResumePoint(val positionSec: Double, val durationSec: Double, val updatedAt: Long) {
    val fraction: Float get() = if (durationSec > 0) (positionSec / durationSec).toFloat().coerceIn(0f, 1f) else 0f
}

/**
 * Resume points and the Watched marks in this browser, by video key (`chatId:messageId`), with
 * the apps' rules: a point is kept from 30 seconds in, and a video is watched once less than about
 * 5 % (at most 3 minutes) is left. Moves onto :core's ResumeRecord and WatchedStore, which also
 * sync across devices through Saved Messages, once those are in the pure half.
 */
class WebResume(private val prefs: WebPrefs) {
    private val points = HashMap<String, ResumePoint>()
    private val watched = HashSet<String>()
    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    init {
        runCatching {
            val o = Json.parseToJsonElement(prefs.string("resume") ?: "{}").jsonObject
            for ((k, v) in o) {
                val p = v as? JsonObject ?: continue
                points[k] = ResumePoint(
                    (p["p"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                    (p["d"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
                    (p["t"] as? JsonPrimitive)?.longOrNull ?: 0L,
                )
            }
        }
        prefs.string("watched")?.split(',')?.filter { it.isNotBlank() }?.let { watched += it }
    }

    fun point(key: String): ResumePoint? = points[key]

    fun fraction(key: String): Float = points[key]?.fraction ?: if (key in watched) 1f else 0f

    fun isWatched(key: String): Boolean = key in watched

    /** Records [positionSec] of [durationSec]; past the end it becomes Watched instead. */
    fun record(key: String, positionSec: Double, durationSec: Double, now: Long) {
        if (durationSec > 0 && durationSec - positionSec < minOf(180.0, durationSec * 0.05)) {
            points.remove(key)
            watched += key
        } else if (positionSec >= 30) {
            points[key] = ResumePoint(positionSec, durationSec, now)
        } else {
            return
        }
        while (points.size > 500) points.remove(points.minBy { it.value.updatedAt }.key)
        save()
    }

    fun setWatched(key: String, on: Boolean) {
        if (on) {
            watched += key
            points.remove(key)
        } else {
            watched -= key
        }
        save()
    }

    /** Most recent first, for Continue watching. */
    fun recent(): List<Pair<String, ResumePoint>> = points.entries.sortedByDescending { it.value.updatedAt }.map { it.key to it.value }

    private fun save() {
        prefs.setString(
            "resume",
            buildJsonObject {
                for ((k, p) in points) putJsonObject(k) {
                    put("p", p.positionSec)
                    put("d", p.durationSec)
                    put("t", p.updatedAt)
                }
            }.toString(),
        )
        prefs.setString("watched", watched.joinToString(","))
        _version.value++
    }
}
