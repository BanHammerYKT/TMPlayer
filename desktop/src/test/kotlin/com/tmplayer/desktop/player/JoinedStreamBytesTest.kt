package com.tmplayer.desktop.player

import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.random.Random

/** A video cut into byte pieces, read back through the streaming input as the one file it was. */
class JoinedStreamBytesTest {

    private lateinit var dir: File
    private lateinit var whole: ByteArray
    private lateinit var pieces: List<File>

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("joined").toFile()
        whole = Random(7).nextBytes(PIECE * 2 + LAST)
        pieces = listOf(0 until PIECE, PIECE until PIECE * 2, PIECE * 2 until whole.size).mapIndexed { i, range ->
            File(dir, "video.mkv.%03d".format(i + 1)).apply { writeBytes(whole.copyOfRange(range.first, range.last + 1)) }
        }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private val openedPieces = mutableListOf<Int>()
    private val left = mutableListOf<Int>()

    private fun joined() = JoinedStreamBytes(
        sizes = pieces.map { it.length() },
        open = { index, _ -> openedPieces += index; LocalFileBytes(pieces[index]) },
        onLeft = { left += it },
    )

    private fun readAll(input: StreamInput, length: Int): ByteArray {
        val out = ByteArray(length)
        var done = 0
        while (done < length) {
            val n = input.read(out, done, length - done)
            if (n < 0) break
            done += n
        }
        return out.copyOf(done)
    }

    @Test
    fun `reading straight through gives the whole file back across the seams`() {
        val bytes = joined()
        assertEquals(whole.size.toLong(), bytes.size)
        val input = StreamInput(bytes, Job())
        assertArrayEquals(whole, readAll(input, whole.size))
        assertEquals(-1, input.read(ByteArray(8), 0, 8))
        assertEquals(listOf(0, 1, 2), openedPieces)
        assertTrue(left.isEmpty())
        input.close()
        bytes.close()
    }

    @Test
    fun `a seek into a later piece opens only that piece, and leaving one closes it`() {
        val bytes = joined()
        val input = StreamInput(bytes, Job())
        assertArrayEquals(whole.copyOfRange(0, 100), readAll(input, 100))
        val at = PIECE * 2L + 10
        input.seekTo(at)
        assertArrayEquals(whole.copyOfRange(at.toInt(), at.toInt() + 200), readAll(input, 200))
        assertEquals(listOf(0, 2), openedPieces)
        assertEquals(listOf(0), left)
        // Back to the first piece: opened again.
        input.seekTo(5)
        assertArrayEquals(whole.copyOfRange(5, 50), readAll(input, 45))
        assertEquals(listOf(0, 2, 0), openedPieces)
        // A read that straddles a seam is two reads, the second from the next piece.
        input.seekTo(PIECE - 4L)
        assertArrayEquals(whole.copyOfRange(PIECE - 4, PIECE + 4), readAll(input, 8))
        input.close()
        bytes.close()
    }

    private companion object {
        const val PIECE = 64 * 1024
        const val LAST = 10 * 1024 + 17
    }
}
