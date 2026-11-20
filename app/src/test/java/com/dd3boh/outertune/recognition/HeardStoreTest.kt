/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The list of everything heard as it is kept in the settings, a JSON array ([RecognitionHistory.decode],
 * [RecognitionHistory.encode]), and the picture each row shows ([Heard.picture]).
 */
class HeardStoreTest {

    /** As 0.12.11 wrote the list (org.json), with an entry from before 0.12.5 at the end. */
    private val written0121 =
        """[{"title":"Billie Jean","artist":"Michael Jackson","videoId":"billiejean","at":1763659500000,"checked":true},""" +
            """{"title":"Take Your Time","artist":"FROG MAN","videoId":"","at":1762361000000,"checked":true},""" +
            """{"title":"Sarah","artist":"Jasiah","videoId":"sarah","at":1761000000000}]"""

    @Test
    fun `entries from before covers read as they did`() {
        val entries = RecognitionHistory.decode(written0121)
        assertEquals(listOf("Billie Jean", "Take Your Time", "Sarah"), entries.map { it.title })
        assertEquals(listOf("Michael Jackson", "FROG MAN", "Jasiah"), entries.map { it.artist })
        assertEquals(listOf("billiejean", null, "sarah"), entries.map { it.videoId })
        assertEquals(listOf(1763659500000L, 1762361000000L, 1761000000000L), entries.map { it.at })
        // No "checked" is an entry from before 0.12.5, which is what tells them apart.
        assertEquals(listOf(true, true, false), entries.map { it.checked })
        assertEquals(listOf(null, null, null), entries.map { it.cover })
    }

    @Test
    fun `a cover is kept and read back`() {
        val entries = listOf(
            Heard("Billie Jean", "Michael Jackson", "billiejean", 2_000L, checked = true, cover = "https://is1-ssl.mzstatic.com/a/400x400cc.jpg"),
            Heard("Take Your Time", "FROG MAN", null, 1_000L, checked = true),
        )
        val text = RecognitionHistory.encode(entries)
        assertEquals(entries, RecognitionHistory.decode(text))
        // Written only when there is one: an older build reads the rest as it always has.
        assertEquals(1, Regex("\"cover\"").findAll(text).count())
        assertTrue(text, text.contains("\"videoId\":\"\""))
    }

    @Test
    fun `slashes escaped by the old writer read as slashes`() {
        val entry = RecognitionHistory.decode(
            """[{"title":"A\/B","artist":"C","videoId":"x","at":1,"checked":true,"cover":"https:\/\/is1-ssl.mzstatic.com\/a.jpg"}]"""
        ).single()
        assertEquals("A/B", entry.title)
        assertEquals("https://is1-ssl.mzstatic.com/a.jpg", entry.cover)
    }

    @Test
    fun `an empty cover is no cover`() {
        assertNull(RecognitionHistory.decode("""[{"title":"A","artist":"B","videoId":"","at":1,"checked":true,"cover":""}]""").single().cover)
    }

    @Test
    fun `nothing kept, or a value that is not a list, is an empty list and never a throw`() {
        assertTrue(RecognitionHistory.decode(null).isEmpty())
        assertTrue(RecognitionHistory.decode("").isEmpty())
        assertTrue(RecognitionHistory.decode("{\"title\":\"A\"}").isEmpty())
        assertTrue(RecognitionHistory.decode("[{\"title\":").isEmpty())
    }

    @Test
    fun `a row's picture is the frame of the video a tap plays`() {
        val entry = Heard("Billie Jean", "Michael Jackson", "billiejean", 0L, checked = true, cover = "https://shazam/cover.jpg")
        assertEquals("https://i.ytimg.com/vi/billiejean/mqdefault.jpg", entry.picture(entry.idToPlay(null)))
    }

    @Test
    fun `with no video it is the cover Shazam gave, or none`() {
        val placed = Heard("Take Your Time", "FROG MAN", null, 0L, checked = true, cover = "https://shazam/cover.jpg")
        assertEquals("https://shazam/cover.jpg", placed.picture(placed.idToPlay(null)))
        assertNull(Heard("Take Your Time", "FROG MAN", null, 0L, checked = true).picture(null))
    }

    /** "I Think Of You" by Sarsh was saved with "Sarah" by Jasiah before ids were checked: never that video's frame. */
    @Test
    fun `an old entry the library has not vouched for never shows its video's frame`() {
        val old = Heard("I Think Of You (Unreleased)", "Sarsh", "sarah", 0L, checked = false)
        assertNull(old.idToPlay(null))
        assertNull(old.picture(old.idToPlay(null)))
        assertFalse(old.picture(null)?.contains("sarah") == true)
    }
}
