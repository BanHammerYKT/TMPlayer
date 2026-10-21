package com.tmplayer.player

import com.tmplayer.i18n.Strings
import kotlin.math.exp

/**
 * How far a video's opening has come, as the one line the pre-roll loader draws, on the phone,
 * the television, the desktop and the browser alike.
 *
 * The wait is cut into stages, each owning a slice of the line and filled from what the player
 * can actually measure:
 *
 * 1. [Stage.Connecting], 0 to 10 per cent: until the first bytes of the file reach the demuxer,
 *    or at once for a file already on the disk.
 * 2. [Stage.Reading], 10 to 40: the head and the index, as bytes handed to the demuxer against
 *    [READ_BYTES], until the engine says it has read the container.
 * 3. [Stage.Finding], 40 to 60, only for a start away from 0: from the seek to the demuxer having
 *    found the place, as bytes read since against [FIND_BYTES]. Without a seek, Reading runs on to
 *    60 instead, so no slice is skipped in one jump.
 * 4. [Stage.Buffering], 60 to 100: time buffered ahead against what the engine wants before it
 *    plays, or bytes against that time at the video's bitrate where the engine cannot say.
 *    It reaches 100 only through [finish], when the first frame shows.
 *
 * Two rules hold the whole way: the line never moves backwards (a stage is never left for an
 * earlier one, and a smaller signal changes nothing), and it never sits still for long (between
 * signals it eases slowly toward, and never to, the end of the stage it is in).
 *
 * The platforms only gather [Signals]; everything here is pure, for the tests.
 */
class OpeningProgress(seeks: Boolean = false) {
    /**
     * True for a resume, or any start away from 0: the Finding stage gets its slice. Settable,
     * since a saved place can be read after the opening began; the line still never goes back.
     */
    var seeks: Boolean = seeks

    enum class Stage { Connecting, Reading, Finding, Buffering }

    /**
     * What the platform knows at one moment. Every field has a value meaning "cannot say", so an
     * engine with fewer signals still gets a moving line from the others and the easing.
     */
    data class Signals(
        /** Bytes the demuxer has been handed since the open began. */
        val readBytes: Long = 0,
        /** The whole file is on the disk: nothing to connect to. */
        val onDisk: Boolean = false,
        /** The engine has read the container: tracks and duration known. */
        val opened: Boolean = false,
        /** The start point has been found; null where the engine cannot say. */
        val seekDone: Boolean? = null,
        /** Time buffered ahead of the start point, in ms; negative where the engine cannot say. */
        val bufferedMs: Long = -1,
        /** What the engine wants buffered before it plays, in ms. */
        val bufferTargetMs: Long = DEFAULT_BUFFER_TARGET_MS,
        /** The video's average rate (size over duration), for a buffer counted in bytes; 0 for unknown. */
        val bytesPerSec: Long = 0,
    )

    /** The stage the line is in. */
    var stage: Stage = Stage.Connecting
        private set

    /** The line, 0 to 1. */
    var value: Float = 0f
        private set

    private var lastAt = -1L
    private var stageBytes = 0L

    /** Takes the signals of [nowMs] and returns the line, never less than before. */
    fun update(signals: Signals, nowMs: Long): Float {
        if (value >= 1f) return value
        val next = stageFor(signals)
        if (next.ordinal > stage.ordinal) {
            stage = next
            stageBytes = signals.readBytes
        }
        val (start, end) = range(stage)
        val span = end - start
        val real = fraction(signals)?.let { start + span * it.coerceIn(0f, 1f) * REAL_SHARE }
        var shown = maxOf(value, start, real ?: 0f)
        val dt = if (lastAt < 0) 0L else (nowMs - lastAt).coerceAtLeast(0)
        lastAt = nowMs
        val easeTo = start + span * EASE_SHARE
        if (dt > 0 && shown < easeTo) {
            shown += (easeTo - shown) * (1f - exp(-dt.toFloat() / EASE_TAU_MS))
        }
        value = shown.coerceAtMost(start + span * REAL_SHARE).coerceAtLeast(value)
        return value
    }

    /** The first frame is showing: the line is full. */
    fun finish(): Float {
        stage = Stage.Buffering
        value = 1f
        return value
    }

    /** The stage the signals point at, which [update] only ever takes when it is a later one. */
    internal fun stageFor(s: Signals): Stage = when {
        !s.onDisk && !s.opened && s.readBytes <= 0 -> Stage.Connecting
        !s.opened && s.readBytes < READ_BYTES -> Stage.Reading
        seeks && !found(s) -> Stage.Finding
        else -> Stage.Buffering
    }

    /** The start point is found: the engine's word, else [FIND_BYTES] read since the seek began. */
    private fun found(s: Signals): Boolean =
        s.seekDone ?: (stage == Stage.Finding && s.readBytes - stageBytes >= FIND_BYTES)

    /** How far through its stage the signals say the line is, or null for no signal. */
    private fun fraction(s: Signals): Float? = when (stage) {
        Stage.Connecting -> null
        Stage.Reading -> s.readBytes.toFloat() / READ_BYTES
        Stage.Finding -> (s.readBytes - stageBytes).toFloat() / FIND_BYTES
        Stage.Buffering -> when {
            s.bufferedMs >= 0 && s.bufferTargetMs > 0 -> s.bufferedMs.toFloat() / s.bufferTargetMs
            s.bytesPerSec > 0 -> (s.readBytes - stageBytes).toFloat() /
                (s.bytesPerSec * maxOf(s.bufferTargetMs, 1L) / 1000f).coerceAtLeast(MIN_BUFFER_BYTES.toFloat())
            else -> null
        }
    }

    /** The slice of the line [stage] owns. */
    fun range(stage: Stage): Pair<Float, Float> = when (stage) {
        Stage.Connecting -> 0f to CONNECTING_END
        Stage.Reading -> CONNECTING_END to if (seeks) READING_END else FINDING_END
        Stage.Finding -> READING_END to FINDING_END
        Stage.Buffering -> FINDING_END to 1f
    }

    /**
     * The status line's words for the stage: "Starting" (or "Resuming from 13:54" for a resume
     * still connecting), "Reading the video", "Finding 13:54", "Buffering".
     */
    fun words(s: Strings, startClock: String?): String = wordsFor(stage, s, startClock)

    companion object {
        /** [words] for a given [stage], for a caller that keeps the stage as its own state. */
        fun wordsFor(stage: Stage, s: Strings, startClock: String?): String = when (stage) {
            Stage.Connecting -> startClock?.let { s.playerResumingFrom(it) } ?: s.playerLoaderStarting
            Stage.Reading -> s.playerLoaderReading
            Stage.Finding -> startClock?.let { s.playerLoaderFinding(it) } ?: s.playerLoaderReading
            Stage.Buffering -> s.playerBuffering
        }

        const val CONNECTING_END = 0.10f
        const val READING_END = 0.40f
        const val FINDING_END = 0.60f

        /**
         * What the demuxer reads before it knows the video: the warm-up's head
         * ([StreamWarmup]'s 1 MiB) and the index after it, rounded up to what an MP4's moov or a
         * Matroska file's Cues come to on a long film.
         */
        const val READ_BYTES = 4L * 1024 * 1024

        /** What a seek to the start point reads before the demuxer is there: a cluster or two. */
        const val FIND_BYTES = 2L * 1024 * 1024

        /** A floor for a buffer counted in bytes, so a tiny bitrate does not fill it at once. */
        const val MIN_BUFFER_BYTES = 256L * 1024

        /** Used where the engine says nothing of its own start threshold. */
        const val DEFAULT_BUFFER_TARGET_MS = 2_000L

        /** Real signals fill a stage to this share of it; only the next stage, or [finish], goes on. */
        const val REAL_SHARE = 0.98f

        /** Between signals the line eases toward this share of its stage, and never past it. */
        const val EASE_SHARE = 0.9f

        /** How slowly it eases: about two thirds of the way to [EASE_SHARE] in this long. */
        const val EASE_TAU_MS = 4_000f

        /** After this long a wait shows its measured speed beside the stage words. */
        const val SPEED_NOTE_AFTER_MS = 2_000L
    }
}
