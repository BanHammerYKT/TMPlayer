package com.tmplayer.data

/**
 * The storage chart's arithmetic, shared by the phone, the television and the desktop: what each
 * part of the device holds, as slices of one ring.
 *
 * Pure, so the slices can be tested without drawing them. Every part that holds a byte gets a
 * slice of at least [MIN_SWEEP_DEG], taken from the larger ones, because a part that is listed
 * beside the chart but has no visible slice in it reads as a mistake (user, 2026-10-18).
 */
object StorageChart {

    /** One kind of thing on the device, in the order the ring and its legend draw them. */
    enum class Part {
        /** Videos the viewer downloaded on purpose. */
        Downloads,

        /** Downloads that stopped or are still arriving: part of a file, on the device. */
        Unfinished,

        /** What playing left behind: the last video played, and videos stopped part way. */
        Recent,

        /** Video files on the device that no message or record names any more. */
        Unmatched,

        /** Thumbnails, previews and poster images. */
        Pictures,

        /** Everything on the device that is not TMPlayer's. */
        OtherApps,

        /** Room left. */
        Free;

        /** Whether the part is TMPlayer's own, which is what the centre of the ring adds up. */
        val mine: Boolean get() = this != OtherApps && this != Free
    }

    /** What the chart is drawn from. A [deviceTotal] of zero or less means the disk is not known. */
    data class Figures(
        val downloads: Long,
        val unfinished: Long,
        val recent: Long,
        val unmatched: Long,
        val pictures: Long,
        val deviceTotal: Long,
        val deviceFree: Long,
    ) {
        /** What TMPlayer holds altogether. */
        val app: Long
            get() = listOf(downloads, unfinished, recent, unmatched, pictures).sumOf { it.coerceAtLeast(0) }

        /**
         * Everything else that is used. The remainder rather than a measurement, floored: the
         * free space and TMPlayer's figures are read a moment apart.
         */
        val otherApps: Long
            get() = if (deviceTotal <= 0) 0 else (deviceTotal - deviceFree.coerceAtLeast(0) - app).coerceAtLeast(0)

        /** The ring's whole: the device, or TMPlayer's own parts when the device is not known. */
        val whole: Long
            get() = if (deviceTotal > 0) maxOf(deviceTotal, app + deviceFree.coerceAtLeast(0)) else app

        fun bytes(part: Part): Long = when (part) {
            Part.Downloads -> downloads
            Part.Unfinished -> unfinished
            Part.Recent -> recent
            Part.Unmatched -> unmatched
            Part.Pictures -> pictures
            Part.OtherApps -> otherApps
            Part.Free -> if (deviceTotal > 0) deviceFree else 0
        }.coerceAtLeast(0)

        /** A part's share of [whole], 0 to 1. */
        fun share(part: Part): Double = if (whole <= 0) 0.0 else bytes(part).toDouble() / whole

        /** Every part that holds anything, in drawing order. */
        val shown: List<Part> get() = Part.entries.filter { bytes(it) > 0 }
    }

    /** One slice of the ring, in degrees clockwise from the top. */
    data class Slice(val part: Part, val bytes: Long, val startDeg: Float, val sweepDeg: Float) {
        val endDeg: Float get() = startDeg + sweepDeg
        val midDeg: Float get() = startDeg + sweepDeg / 2
    }

    /** The smallest slice drawn, so a part under about one per cent is still a visible sliver. */
    const val MIN_SWEEP_DEG = 4.5f

    /**
     * The slices for [figures], starting at the top and going clockwise, with no gaps: the drawing
     * cuts its own. Sweeps add up to 360 whenever anything is shown.
     */
    fun slices(figures: Figures, minSweepDeg: Float = MIN_SWEEP_DEG): List<Slice> {
        val parts = figures.shown
        if (parts.isEmpty()) return emptyList()
        val bytes = parts.map { figures.bytes(it).toDouble() }
        val sweeps = sweeps(bytes, minSweepDeg.toDouble())
        var at = 0.0
        return parts.mapIndexed { i, part ->
            val slice = Slice(part, figures.bytes(part), at.toFloat(), sweeps[i].toFloat())
            at += sweeps[i]
            slice
        }
    }

    /**
     * Shares of 360 degrees, every one at least [min]. The small ones are raised to the floor and
     * the rest share what is left in proportion, again until nothing more falls under it.
     */
    internal fun sweeps(bytes: List<Double>, min: Double): List<Double> {
        val n = bytes.size
        if (n == 0) return emptyList()
        // Nothing can be guaranteed a floor that the parts together could not all have.
        val floor = minOf(min, 360.0 / n)
        val pinned = BooleanArray(n)
        while (true) {
            val free = 360.0 - floor * pinned.count { it }
            val rest = bytes.indices.filter { !pinned[it] }.sumOf { bytes[it] }
            var changed = false
            for (i in bytes.indices) {
                if (pinned[i]) continue
                val sweep = if (rest > 0) free * bytes[i] / rest else free / bytes.indices.count { !pinned[it] }
                if (sweep < floor) {
                    pinned[i] = true
                    changed = true
                }
            }
            if (!changed) {
                return bytes.indices.map { i ->
                    if (pinned[i]) floor else if (rest > 0) free * bytes[i] / rest else free / bytes.indices.count { !pinned[it] }
                }
            }
        }
    }

    /** The slice under [degrees] clockwise from the top, for a tap on the ring. */
    fun sliceAt(slices: List<Slice>, degrees: Float): Slice? {
        val d = ((degrees % 360f) + 360f) % 360f
        return slices.firstOrNull { d >= it.startDeg && d < it.endDeg } ?: slices.lastOrNull()?.takeIf { d >= it.startDeg }
    }
}

/**
 * The lines of "Free up space", chosen in one place for every device so each says exactly what it
 * removes and only offers what would help (user, 2026-10-18).
 */
object FreeUpSpace {

    enum class Kind {
        /** Low storage mode, offered while it is off and the device is short. */
        LowStorage,

        /** The recently played videos, partly played ones and unmatched files. Downloads stay. */
        Recent,

        /** The downloads that have not finished, with what they hold. */
        Unfinished,

        /** Thumbnails, previews and poster images. */
        Pictures,

        /** Into the selection, to pick downloads to delete. */
        ChooseDownloads,
    }

    data class Line(val kind: Kind, val bytes: Long = 0, val count: Int = 0, val enabled: Boolean = true)

    /** Below this much free space a device counts as short, and Low storage mode is offered. */
    const val SHORT_BELOW_BYTES = 3L * 1024 * 1024 * 1024

    /**
     * Whether the device is short of room: the sheet was opened because a video did not fit, or
     * the free space is under [SHORT_BELOW_BYTES] or a tenth of the disk.
     */
    fun isShort(freeBytes: Long, totalBytes: Long, askedForRoom: Boolean): Boolean {
        if (askedForRoom) return true
        if (freeBytes < 0 || totalBytes <= 0) return false
        return freeBytes < SHORT_BELOW_BYTES || freeBytes < totalBytes / 10
    }

    /**
     * The lines, in order. Low storage mode comes first when it is offered, since it is the one
     * answer that deletes nothing; Unfinished only appears when there is an unfinished download.
     * Lines with nothing to free stay, disabled, so the sheet keeps its shape.
     */
    fun lines(
        lowStorageOn: Boolean,
        short: Boolean,
        recentBytes: Long,
        recentCount: Int,
        unfinishedBytes: Long,
        unfinishedCount: Int,
        picturesBytes: Long,
        downloadCount: Int,
    ): List<Line> = buildList {
        if (!lowStorageOn && short) add(Line(Kind.LowStorage))
        add(Line(Kind.Recent, recentBytes, recentCount, enabled = recentBytes > 0))
        if (unfinishedCount > 0) add(Line(Kind.Unfinished, unfinishedBytes, unfinishedCount, enabled = true))
        add(Line(Kind.Pictures, picturesBytes, enabled = picturesBytes > 0))
        add(Line(Kind.ChooseDownloads, count = downloadCount, enabled = downloadCount > 0))
    }
}
