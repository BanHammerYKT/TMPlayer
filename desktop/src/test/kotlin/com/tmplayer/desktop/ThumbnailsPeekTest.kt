package com.tmplayer.desktop

import com.tmplayer.data.Thumbnails
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import java.nio.file.Files
import javax.imageio.ImageIO

/**
 * A provider's picture scrolled back into view is drawn from memory on its first frame, whatever
 * width the tile asks for before it is measured.
 */
class ThumbnailsPeekTest {

    private fun png(width: Int, height: Int): File {
        val file = Files.createTempFile("tm-peek", ".png").toFile()
        ImageIO.write(BufferedImage(width, height, BufferedImage.TYPE_INT_RGB), "png", file)
        file.deleteOnExit()
        return file
    }

    @Test
    fun aLargerCopyFetchedForAMeasuredTileIsFoundAtTheDefaultWidth() = runBlocking {
        val named = "https://image.tmdb.org/t/p/w342/peek-test-poster.jpg"
        val file = png(780, 1170)
        val fetched = mutableListOf<String>()
        // Measured 700 px wide: the w780 copy is fetched in place of the named w342.
        val sharp = Thumbnails.sharp(named, pixels = 700, maxWidth = 342) { url -> fetched += url; file }
        assertNotNull(sharp)
        assertEquals("https://image.tmdb.org/t/p/w780/peek-test-poster.jpg", fetched.single())
        // Composed again, not yet measured: it asks at its default width, which names the w342.
        assertSame(sharp, Thumbnails.peekSharp(named, pixels = 342, maxWidth = 342))
    }

    @Test
    fun aPictureFetchedAtItsNamedSizeIsPeekedAtAnyWidthItDoesNotOutgrow() = runBlocking {
        val named = "https://image.tmdb.org/t/p/w500/peek-test-other.jpg"
        val sharp = Thumbnails.sharp(named, pixels = 400, maxWidth = 342) { png(500, 750) }
        assertNotNull(sharp)
        assertSame(sharp, Thumbnails.peekSharp(named, pixels = 300, maxWidth = 342))
    }
}
