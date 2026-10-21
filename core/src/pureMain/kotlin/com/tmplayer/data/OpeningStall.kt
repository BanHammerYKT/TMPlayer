package com.tmplayer.data

/**
 * The player's opening wait that has gone on with nothing arriving.
 *
 * Telegram that is out of reach, or slow to start sending a file, leaves the opening loader on
 * "Starting" with no error at all: TDLib does not fail a download it cannot begin, it waits. After
 * [STALL_MS] with no new bytes the player says so and offers Try again, Download first and Back.
 * Kept here, free of the player, so the phone, the television and the desktop decide it alike and
 * a test can check it.
 */
object OpeningStall {

    /** How long the opening may go with no new bytes before the player says so. */
    const val STALL_MS = 10_000L

    /** Why nothing is arriving, which decides the words on the sheet. */
    enum class Reason {
        /** No line to Telegram: offline, blocked, or a proxy that is not answering. */
        NoLine,

        /** Connected, and Telegram has not started sending this video. */
        Slow,
    }

    /**
     * Whether the opening has stalled: still opening, the file not on this device in full, and the
     * last new byte (or the start of the wait, when none has come) at least [STALL_MS] ago.
     */
    fun stalled(opening: Boolean, local: Boolean, lastProgressAtMs: Long, nowMs: Long, stallMs: Long = STALL_MS): Boolean =
        opening && !local && nowMs - lastProgressAtMs >= stallMs

    fun reason(link: TelegramLink): Reason = if (link.isUp) Reason.Slow else Reason.NoLine
}
