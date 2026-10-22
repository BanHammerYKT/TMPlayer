package com.tmplayer.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Test

/** Subtitle files in the encodings people actually save them in, read back as the same text. */
class SubtitleTextTest {

    private val cue = "1\n00:00:01,000 --> 00:00:02,500\n"

    private fun bytes(text: String, charset: String, bom: ByteArray = ByteArray(0)): ByteArray =
        bom + text.toByteArray(Charset.forName(charset))

    @Test
    fun `utf-8 with and without a byte order mark`() {
        val text = cue + "Ça va? Привет, مرحبا\n"
        assertEquals(text, SubtitleText.decode(bytes(text, "UTF-8")))
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        assertEquals(text, SubtitleText.decode(bytes(text, "UTF-8", bom)))
    }

    @Test
    fun `utf-16 both ways round, with a mark and without`() {
        val text = cue + "مرحبا بالعالم\nHello\n"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        val be = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
        assertEquals(text, SubtitleText.decode(bytes(text, "UTF-16LE", le)))
        assertEquals(text, SubtitleText.decode(bytes(text, "UTF-16BE", be)))
        assertEquals(text, SubtitleText.decode(bytes(text, "UTF-16LE")))
        assertEquals(text, SubtitleText.decode(bytes(text, "UTF-16BE")))
    }

    @Test
    fun `utf-32 with a mark`() {
        val text = cue + "Hello\n"
        val le = byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0, 0)
        assertEquals(text, SubtitleText.decode(bytes(text, "UTF-32LE", le)))
    }

    @Test
    fun `a code page is read as the subtitle's language wrote it`() {
        val arabic = cue + "مرحبا بالعالم\n"
        assertEquals(arabic, SubtitleText.decode(bytes(arabic, "windows-1256"), "ar"))
        assertEquals(arabic, SubtitleText.decode(bytes(arabic, "windows-1256"), "fa-IR"))
        val russian = cue + "Привет, мир\n"
        assertEquals(russian, SubtitleText.decode(bytes(russian, "windows-1251"), "ru"))
        val greek = cue + "Καλημέρα\n"
        assertEquals(greek, SubtitleText.decode(bytes(greek, "windows-1253"), "ell"))
        val polish = cue + "Zażółć gęślą jaźń\n"
        assertEquals(polish, SubtitleText.decode(bytes(polish, "windows-1250"), "pl"))
    }

    @Test
    fun `the western page when the language is unknown or written in it`() {
        val french = cue + "Ça va très bien, merci\n"
        assertEquals(french, SubtitleText.decode(bytes(french, "windows-1252")))
        assertEquals(french, SubtitleText.decode(bytes(french, "windows-1252"), "fr"))
        assertEquals(french, SubtitleText.decode(bytes(french, "windows-1252"), "nonsense"))
    }

    @Test
    fun `chinese tags pick the simplified or the traditional page`() {
        assertEquals("GBK", SubtitleText.codePageFor("zh").name())
        assertEquals("GBK", SubtitleText.codePageFor("zh-CN").name())
        assertEquals("Big5", SubtitleText.codePageFor("zh-TW").name())
        assertEquals("Big5", SubtitleText.codePageFor("zh_Hant_HK").name())
        assertEquals("windows-1252", SubtitleText.codePageFor(null).name())
    }

    @Test
    fun `an online download is read in the hit's language, zipped or not`() {
        val arabic = cue + "مرحبا\n"
        val plain = SubtitleText.from(bytes(arabic, "windows-1256"), "film.srt", language = "ar")
        assertEquals(arabic, plain?.text)
        val zip = ByteArrayOutputStream().also { out ->
            ZipOutputStream(out).use { z ->
                z.putNextEntry(ZipEntry("film.ar.srt"))
                z.write(bytes(arabic, "windows-1256"))
                z.closeEntry()
            }
        }.toByteArray()
        val zipped = SubtitleText.from(zip, "film.zip", language = "ar")
        assertNotNull(zipped)
        assertEquals(arabic, zipped?.text)
    }
}
