package com.tmplayer.player

import com.tmplayer.platform.nowMillis
import kotlin.concurrent.Volatile

/**
 * The speed Telegram last sent a video at on this run, for choosing a lighter copy of the next
 * one before it opens ([com.tmplayer.data.TelegramQualities.choose]). Each player feeds it the
 * smoothed figure its [SpeedMeter] reads while bytes are arriving; a reading older than
 * [FRESH_MS] says nothing about the line now, and neither does one below
 * [StreamStats.MIN_MEANINGFUL_SPEED].
 */
object LineSpeed {

    const val FRESH_MS = 10 * 60 * 1000L

    @Volatile
    private var last: Pair<Long, Long>? = null

    fun note(bytesPerSec: Long, nowMs: Long = nowMillis()) {
        if (bytesPerSec < StreamStats.MIN_MEANINGFUL_SPEED) return
        last = bytesPerSec to nowMs
    }

    fun recent(nowMs: Long = nowMillis()): Long? {
        val (speed, at) = last ?: return null
        return speed.takeIf { nowMs - at in 0..FRESH_MS }
    }

    /** For a test, and a sign out. */
    fun forget() {
        last = null
    }
}
