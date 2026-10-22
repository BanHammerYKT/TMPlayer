package com.tmplayer.data

import dev.g000sha256.tdl.dto.AlternativeVideo
import dev.g000sha256.tdl.dto.File
import dev.g000sha256.tdl.dto.LocalFile
import dev.g000sha256.tdl.dto.Photo
import dev.g000sha256.tdl.dto.PhotoSize
import dev.g000sha256.tdl.dto.RemoteFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramQualitiesTest {

    private fun file(id: Int, size: Long) =
        File(id, size, size, LocalFile("", true, true, false, false, 0, 0, 0), RemoteFile("r$id", "u$id", false, true, size))

    private fun alt(id: Int, height: Int, size: Long, codec: String = "h264") =
        AlternativeVideo(id.toLong(), height * 16 / 9, height, codec, file(id + 1000, 1_000), file(id, size))

    private val GB = 1_000_000_000L

    private fun film(
        qualities: List<TelegramQuality>,
        size: Long = 2 * GB,
        durationSec: Int = 7_200,
        onDevice: Boolean = false,
    ) = MediaItem(
        chatId = 1, messageId = 2, fileId = 10, title = "Film", sizeBytes = size, durationSec = durationSec,
        mimeType = "video/mp4", thumbnailFileId = 0, miniThumbnail = null, date = 0, onDevice = onDevice,
        width = 1920, height = 1080, qualities = qualities,
    )

    private val q720 = TelegramQuality(720, 1280, 720, "h264", 900_000_000)
    private val q480 = TelegramQuality(480, 854, 480, "h264", 450_000_000)
    private val q360 = TelegramQuality(360, 640, 360, "h264", 250_000_000)

    @Test
    fun `telegram's copies are kept lighter than the upload, one per height, best first`() {
        val copies = TelegramQualities.fromTdlib(
            listOf(
                alt(1, 480, 400_000_000),
                alt(2, 720, 800_000_000, codec = "h265"),
                alt(3, 720, 900_000_000),
                alt(4, 1080, 1_500_000_000),
                alt(5, 718, 850_000_000, codec = "av1"),
            ),
            uploadHeight = 1080,
            uploadSize = 2 * GB,
        )
        // 1080 is no lighter than the upload; of the three 720s H.264 wins over HEVC and AV1.
        assertEquals(listOf(720, 480), copies.map { it.height })
        assertEquals(3, copies.first().fileId)
    }

    @Test
    fun `a copy as big as the upload is no help`() {
        assertTrue(TelegramQualities.arrange(listOf(q720.copy(sizeBytes = 3 * GB)), uploadHeight = 0, uploadSize = 2 * GB).isEmpty())
    }

    @Test
    fun `heights are named as a viewer knows them`() {
        assertEquals("720p", TelegramQualities.label(718))
        assertEquals("480p", TelegramQualities.label(480))
        assertEquals("4K", TelegramQualities.label(2160))
        assertEquals("300p", TelegramQualities.label(300))
    }

    @Test
    fun `a quality played keeps the message and goes back to the upload`() {
        val item = film(listOf(q720, q480))
        val light = TelegramQualities.withQuality(item, q480)
        assertEquals(item.id, light.id)
        assertEquals(480, light.fileId)
        assertEquals(480, light.playingQuality)
        assertEquals(q480, TelegramQualities.playing(light))
        val back = TelegramQualities.upload(light)
        assertEquals(10, back.fileId)
        assertEquals(2 * GB, back.sizeBytes)
        assertEquals(0, back.playingQuality)
        // From one Telegram copy to another the upload is still the one remembered.
        val other = TelegramQualities.withQuality(light, q720)
        assertEquals(10, other.uploadFileId)
    }

    @Test
    fun `save mobile data plays the best copy no taller than 720p`() {
        val choice = TelegramQualities.choose(film(listOf(q720, q480)), saveData = true, lineBytesPerSec = null)
        assertEquals(q720, choice?.quality)
        assertEquals(TelegramQualities.Why.SaveData, choice?.why)
    }

    @Test
    fun `a fast line or an unmeasured one plays the upload`() {
        val item = film(listOf(q720, q480))
        assertNull(TelegramQualities.choose(item, saveData = false, lineBytesPerSec = null))
        // The upload needs about 278 kB a second; 1 MB a second carries it.
        assertNull(TelegramQualities.choose(item, saveData = false, lineBytesPerSec = 1_000_000))
    }

    @Test
    fun `a slow line plays the best copy it carries, else the lightest`() {
        val item = film(listOf(q720, q480, q360))
        // 150 kB a second: the 720p needs 125 kB with headroom 162 kB, the 480p 62 kB with 81 kB.
        assertEquals(q480, TelegramQualities.choose(item, saveData = false, lineBytesPerSec = 150_000)?.quality)
        assertEquals(q360, TelegramQualities.choose(item, saveData = false, lineBytesPerSec = 20_000)?.quality)
    }

    @Test
    fun `nothing is chosen for a copy on the device or one already chosen`() {
        assertNull(TelegramQualities.choose(film(listOf(q480), onDevice = true), saveData = true, lineBytesPerSec = null))
        val chosen = TelegramQualities.withQuality(film(listOf(q720, q480)), q720)
        assertNull(TelegramQualities.choose(chosen, saveData = true, lineBytesPerSec = null))
    }

    @Test
    fun `the stall sheet offers the next copy down`() {
        val item = film(listOf(q720, q480))
        assertEquals(q720, TelegramQualities.lighter(item))
        assertEquals(q480, TelegramQualities.lighter(TelegramQualities.withQuality(item, q720)))
        assertNull(TelegramQualities.lighter(TelegramQualities.withQuality(item, q480)))
    }

    @Test
    fun `a message read again gives the file of the height asked for`() {
        val fresh = film(listOf(q720.copy(fileId = 77), q480))
        assertEquals(77, TelegramQualities.fileFor(fresh, 720))
        assertEquals(10, TelegramQualities.fileFor(fresh, 0))
        assertEquals(10, TelegramQualities.fileFor(fresh, 1440))
    }

    @Test
    fun `the cover's card picture is the largest size a card draws`() {
        val photo = Photo(false, null, arrayOf(PhotoSize("s", file(1, 1), 90, 50, intArrayOf()), PhotoSize("m", file(2, 1), 320, 180, intArrayOf()), PhotoSize("y", file(3, 1), 1280, 720, intArrayOf())))
        assertEquals(2, MediaMapper.coverSize(photo)?.photo?.id)
        val big = Photo(false, null, arrayOf(PhotoSize("y", file(3, 1), 1280, 720, intArrayOf())))
        assertEquals(3, MediaMapper.coverSize(big)?.photo?.id)
    }

    @Test
    fun `a software heavy upload plays the tallest copy the hardware decodes, H264 first`() {
        val hevc720 = TelegramQuality(21, 1280, 720, "h265", 800_000_000)
        val av1720 = TelegramQuality(22, 1280, 720, "av1", 700_000_000)
        val hardware: (TelegramQuality) -> Boolean = { it.codec != "av1" }
        // H.264 before HEVC at the same height.
        assertEquals(q720, TelegramQualities.forSoftwareDecode(listOf(q480, hevc720, q720), saveData = false, inHardware = hardware))
        // An AV1 copy is no way out of software AV1.
        assertEquals(q480, TelegramQualities.forSoftwareDecode(listOf(av1720, q480), saveData = false, inHardware = hardware))
        // Nothing the hardware takes: nothing to switch to.
        assertNull(TelegramQualities.forSoftwareDecode(listOf(av1720), saveData = false, inHardware = hardware))
        assertNull(TelegramQualities.forSoftwareDecode(emptyList(), saveData = false, inHardware = hardware))
    }

    @Test
    fun `save mobile data keeps the software copy at 720p or under while there is one`() {
        val q1080 = TelegramQuality(30, 1920, 1080, "h264", 1_500_000_000)
        assertEquals(q720, TelegramQualities.forSoftwareDecode(listOf(q1080, q720, q480), saveData = true) { true })
        assertEquals(q1080, TelegramQualities.forSoftwareDecode(listOf(q1080, q720), saveData = false) { true })
        // Only taller copies: the lightest rule does not apply here, the tallest the hardware takes plays.
        assertEquals(q1080, TelegramQualities.forSoftwareDecode(listOf(q1080), saveData = true) { true })
    }

    @Test
    fun `copy codecs map to the mime types the codec list knows`() {
        assertEquals(SoftwareDecode.MIME_H264, TelegramQualities.mimeOf("h264"))
        assertEquals(SoftwareDecode.MIME_HEVC, TelegramQualities.mimeOf("H265"))
        assertEquals(SoftwareDecode.MIME_AV1, TelegramQualities.mimeOf("av1"))
        assertEquals(SoftwareDecode.MIME_VP9, TelegramQualities.mimeOf("vp9"))
        assertNull(TelegramQualities.mimeOf("theora"))
    }
}

class PostStartTest {

    @Test
    fun `a link's moment wins, then the saved place, then the poster's`() {
        assertEquals(90_000L, PostStart.startMs(linkSec = 90, savedMs = 600_000, postSec = 30, durationSec = 3_600))
        assertEquals(600_000L, PostStart.startMs(linkSec = 0, savedMs = 600_000, postSec = 30, durationSec = 3_600))
        assertEquals(30_000L, PostStart.startMs(linkSec = 0, savedMs = 0, postSec = 30, durationSec = 3_600))
        assertEquals(0L, PostStart.startMs(linkSec = 0, savedMs = 0, postSec = 0, durationSec = 3_600))
    }

    @Test
    fun `a moment past the end is no moment`() {
        assertEquals(0L, PostStart.startMs(linkSec = 0, savedMs = 0, postSec = 4_000, durationSec = 3_600))
        assertEquals(5_000L, PostStart.startMs(linkSec = 9_000, savedMs = 5_000, postSec = 0, durationSec = 3_600))
    }

    @Test
    fun `the t of a link is read in every form people write`() {
        assertEquals(90, PostStart.fromLink("https://t.me/somechannel/123?t=90"))
        assertEquals(90, PostStart.fromLink("https://t.me/c/1234/56?single&t=1m30s"))
        assertEquals(3723, PostStart.fromLink("t.me/x/1?t=1h2m3s"))
        assertEquals(90, PostStart.fromLink("t.me/x/1?t=1:30"))
        assertEquals(45, PostStart.fromLink("t.me/x/1?t=45s"))
        assertNull(PostStart.fromLink("https://t.me/x/1"))
        assertNull(PostStart.fromLink("https://t.me/x/1?t=soon"))
    }
}
