/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A song change calls the store's setNowPlaying 4 to 10 times within two seconds, mostly with nothing
 * new, and every call redrew the widget and sent its pictures to the launcher again: the new title took
 * 0.9 to 2 s to show on the Pixel 5. A write that changes nothing the widget shows is skipped now, with
 * its redraw.
 */
class WidgetSameWriteTest {

    private fun song(id: String, art: String? = "/art/$id.png") =
        WidgetSong(id = id, title = "Title $id", artist = "Artist", artPath = art, durationSec = 200)

    private fun snapshot() = WidgetSnapshot(
        nowPlaying = song("x"),
        isPlaying = true,
        recent = listOf(song("x"), song("w")),
        upNext = listOf(song("y"), song("z")),
        updatedAt = 10,
    )

    @Test
    fun `a write that only moves the clock changes nothing the widget shows`() {
        val s = snapshot()
        assertEquals(emptyList<String>(), s.changesTo(s.copy(updatedAt = 99)))
        // As the store compares: the snapshot read back from its file against the one it would write.
        assertEquals(emptyList<String>(), WidgetCodec.decode(WidgetCodec.encode(s)).changesTo(s.copy(updatedAt = 11)))
    }

    @Test
    fun `each part the widget shows is named when it changes`() {
        val s = snapshot()
        assertEquals(listOf("song"), s.changesTo(s.copy(nowPlaying = song("q"))))
        assertEquals(listOf("song's details"), s.changesTo(s.copy(nowPlaying = song("x", art = "/art/x-big.png"))))
        assertEquals(listOf("play"), s.changesTo(s.copy(isPlaying = false)))
        assertEquals(listOf("like"), s.changesTo(s.copy(isLiked = true)))
        assertEquals(listOf("shuffle"), s.changesTo(s.copy(isShuffled = true)))
        assertEquals(listOf("up next"), s.changesTo(s.copy(upNext = listOf(song("z")))))
        assertEquals(listOf("recent"), s.changesTo(s.copy(recent = listOf(song("x")))))
        assertEquals(listOf("lists"), s.changesTo(s.copy(picks = listOf(song("p")))))
    }

    @Test
    fun `a change of the queue's next songs alone is a change of nothing where no widget shows them`() {
        val s = snapshot()
        val queued = s.copy(upNext = listOf(song("q")))
        assertEquals("a tablet's widget shows them", listOf("up next"), s.changesTo(queued))
        assertEquals("a phone's does not", emptyList<String>(), s.changesTo(queued, upNextShown = false))
        // everything else is still a change, shown or not
        assertEquals(listOf("song"), s.changesTo(s.copy(nowPlaying = song("q"), upNext = listOf(song("q"))), upNextShown = false))
        assertEquals(listOf("play"), s.changesTo(queued.copy(isPlaying = false), upNextShown = false))
    }

    @Test
    fun `the store skips the write and the redraw when nothing changed, and still fetches a missing picture`() {
        val store = File("src/main/java/com/dd3boh/outertune/widget/WidgetStore.kt").readText()
        val set = store.substringAfter("suspend fun setNowPlaying(").substringBefore("\n    }\n")
        val skippedWrite = Regex("""if \(changed\.isNotEmpty\(\)\) write\(context, next\.copy\(updatedAt = System\.currentTimeMillis\(\)\)\)""")
        assertEquals("both ways (a song, and nothing playing) write only a change", 2, skippedWrite.findAll(set).count())
        assertEquals(2, Regex("""changed = old\.changesTo\(next, upNextShown\)""").findAll(set).count())
        val draw = set.substringAfter("if (widgets) {\n            if (changed.isEmpty()) {")
        assertTrue("not drawn again" in draw.substringBefore("} else {"))
        assertFalse("updateAll(context)" in draw.substringBefore("} else {"))
        assertTrue("MusicWidget().updateAll(context)" in draw.substringAfter("} else {").substringBefore("\n        }\n"))
        // The artwork owed to a song does not wait for a change.
        assertTrue(set.indexOf("owed = song to sizes") > set.lastIndexOf("changed = old.changesTo(next)"))
        assertFalse(Regex("""if \(changed\.isNotEmpty\(\)\) \{[^}]*owed""").containsMatchIn(set))
    }
}
