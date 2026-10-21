package com.tmplayer.data

import com.tmplayer.online.MetaSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoCorrectionsTest {

    @Test
    fun `a correction reads back as it was written`() {
        val all = VideoCorrection(separate = true, wrongMatch = true, title = "Night Lantern (2022)")
        assertEquals(all, VideoCorrection.decode(all.encode()))
        assertEquals(VideoCorrection(title = "Two\nlines"), VideoCorrection.decode("title=Two\nlines").copy(title = "Two\nlines"))
        assertEquals("title=Two lines", VideoCorrection(title = "Two\nlines").encode())
        assertTrue(VideoCorrection.decode(null).isEmpty)
        assertTrue(VideoCorrection.decode("nonsense").isEmpty)
        assertTrue(VideoCorrection(title = "  ").isEmpty)
    }

    @Test
    fun `stored names stand for message ids, channels' negative ones too`() {
        assertEquals("fix_-1001234_56", VideoCorrections.keyName(-1001234, 56))
        assertEquals("-1001234:56", VideoCorrections.idOf("fix_-1001234_56"))
        assertNull(VideoCorrections.idOf("fix_nonsense"))
        assertNull(VideoCorrections.idOf("topic_5"))
    }

    @Test
    fun `corrections and a chat's sort travel in a backup`() {
        val settings = mapOf<String, Any>(
            "fix_-1001234_56" to "separate",
            "vsort_-1001234" to "Name",
            "vfilter_-1001234" to "Unwatched",
        )
        val backup = Backups.collect(settings, emptyMap(), MetaSettings(), createdAt = 0, appVersion = "test")
        assertEquals("separate", backup.memory["fix_-1001234_56"])
        assertEquals("Name", backup.memory["vsort_-1001234"])
        assertEquals("Unwatched", backup.memory["vfilter_-1001234"])
    }
}
