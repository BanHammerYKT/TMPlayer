package com.tmplayer.data

import com.tmplayer.online.MetaSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HiddenChatsTest {

    @After
    fun clear() = HiddenChats.set(emptySet())

    @Test
    fun `stored chats read back in order, with names that held line breaks flattened`() {
        val chats = listOf(HiddenChat(-1001234, "Film\tClub\nArchive"), HiddenChat(42, "Lectures"))
        val back = HiddenChats.decode(HiddenChats.encode(chats))
        assertEquals(listOf(HiddenChat(-1001234, "Film Club Archive"), HiddenChat(42, "Lectures")), back)
    }

    @Test
    fun `lines that do not parse are skipped, and a chat is listed once`() {
        assertEquals(listOf(HiddenChat(7, "a")), HiddenChats.decode("nonsense\n7\ta\n7\tb\n\n"))
        assertEquals(emptyList<HiddenChat>(), HiddenChats.decode(null))
    }

    @Test
    fun `hiding a chat again brings its name up to date rather than listing it twice`() {
        val list = HiddenChats.with(listOf(HiddenChat(1, "Old")), HiddenChat(1, "New"))
        assertEquals(listOf(HiddenChat(1, "New")), list)
        assertEquals(2, HiddenChats.with(list, HiddenChat(2, "Other")).size)
    }

    @Test
    fun `a hidden chat's items are left out`() {
        HiddenChats.set(setOf(5L))
        assertFalse(HiddenChats.shows(5))
        assertTrue(HiddenChats.shows(6))
        assertEquals(listOf(6L, 7L), HiddenChats.filter(listOf(5L, 6L, 5L, 7L)) { it })
    }

    @Test
    fun `hidden chats travel in a backup`() {
        val settings = mapOf<String, Any>(HiddenChats.KEY to HiddenChats.encode(listOf(HiddenChat(9, "Nine"))))
        val backup = Backups.collect(settings, emptyMap(), MetaSettings(), createdAt = 0, appVersion = "test")
        assertEquals(listOf(HiddenChat(9, "Nine")), HiddenChats.decode(backup.settings[HiddenChats.KEY] as String))
    }
}
