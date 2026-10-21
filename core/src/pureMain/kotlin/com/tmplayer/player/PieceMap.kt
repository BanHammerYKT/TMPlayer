package com.tmplayer.player

/**
 * Where a byte of a video cut into pieces lives: the pieces' sizes laid end to end, so a position
 * in the joined stream maps to one piece and an offset in it. What Android's joined data source
 * and the desktop's joined stream both read through, so seeking across pieces is the same sums.
 */
class PieceMap(sizes: List<Long>) {

    /** Each piece's length in bytes, as given. */
    val sizes: List<Long> = sizes.map { it.coerceAtLeast(0) }

    private val starts: LongArray = LongArray(this.sizes.size).also { out ->
        var at = 0L
        this.sizes.forEachIndexed { index, size ->
            out[index] = at
            at += size
        }
    }

    /** The joined stream's length: every piece's, added up. */
    val total: Long = this.sizes.sum()

    val count: Int get() = sizes.size

    /** One byte's place: the piece it is in and how far into that piece. */
    data class Spot(val index: Int, val offset: Long)

    /**
     * The piece and offset of [position], or null at or past the end of the stream (or before its
     * start). A position on a seam is the first byte of the later piece; an empty piece holds none.
     */
    fun locate(position: Long): Spot? {
        if (position < 0 || position >= total) return null
        // Binary search for the last piece that starts at or before the position and is not empty.
        var low = 0
        var high = starts.size - 1
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (starts[mid] <= position) low = mid else high = mid - 1
        }
        var index = low
        while (index < sizes.size && position - starts[index] >= sizes[index]) index++
        if (index >= sizes.size) return null
        return Spot(index, position - starts[index])
    }

    /** Where piece [index] starts in the joined stream. */
    fun startOf(index: Int): Long = starts[index]

    /** How many bytes from [position] to the end of the piece it is in, 0 at the end of the stream. */
    fun leftInPiece(position: Long): Long {
        val spot = locate(position) ?: return 0
        return sizes[spot.index] - spot.offset
    }
}
