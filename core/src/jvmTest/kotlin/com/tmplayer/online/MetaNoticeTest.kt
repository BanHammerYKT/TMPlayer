package com.tmplayer.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class MetaNoticeTest {
    @Test
    fun theNoteAndTheInviteAreRetired() {
        for (enabled in listOf(true, false)) for (dismissed in listOf(true, false)) {
            assertFalse(MetaNotice.shouldShow(enabled, dismissed))
        }
        assertFalse(MetaNotice.shouldInvite(MetaSettings(enabled = false), inviteSeen = false))
    }

    @Test
    fun postersAreTurnedBackOnOnceAndAChoiceAfterThatStands() {
        val dir = createTempDirectory("meta-notice").toFile()
        try {
            val file = File(dir, "metadata.properties")
            // What an older version wrote: off, even by choice.
            file.writeText("enabled=false\noff_by_choice=true\nskip_times=true\n")
            val store = MetadataStore(file)
            assertTrue(store.now.enabled)
            assertFalse(store.now.offByChoice)
            // Turned off after that: the mark is saved with it, and the next start keeps it off.
            store.update { it.copy(enabled = false, offByChoice = true) }
            val again = MetadataStore(file).now
            assertFalse(again.enabled)
            assertEquals(true, again.offByChoice)
        } finally {
            dir.deleteRecursively()
        }
    }
}
