package com.tmplayer.data

import com.tmplayer.platform.TextCollator
import com.tmplayer.platform.toTmZone
import java.text.Collator
import java.time.ZoneId

/*
 * The JDK forms of pure functions, so callers that hold a JDK collator or zone (the JVM tests)
 * call them as before.
 */

internal fun VideoSorting.compareNames(a: String, b: String, collator: Collator): Int =
    compareNames(a, b, TextCollator { x, y -> collator.compare(x, y) })

fun WatchedWhen.label(watchedAt: Long, now: Long, zone: ZoneId): String = label(watchedAt, now, zone.toTmZone())

fun WatchedWhen.phrase(watchedAt: Long, now: Long, zone: ZoneId): String = phrase(watchedAt, now, zone.toTmZone())
