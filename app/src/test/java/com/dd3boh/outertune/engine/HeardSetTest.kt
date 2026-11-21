/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** What a listener knows, the one rule behind Discover, its queues and the "New to you" caption. */
class HeardSetTest {
    private val now = 1_789_135_200_000L

    @Test
    fun `a song is known by its id, or as another upload of a known song by the same artist`() {
        val heard = HeardSet.of(listOf(KnownSong("a", "Take Me Away", "Someone"), KnownSong("b", "Toto - Africa", "Toto")))
        assertTrue(heard.knows("a", null, null))
        assertTrue(heard.knows("a2", "Take Me Away (Official Audio)", "Someone"))
        assertTrue(heard.knows("a3", "Take Me Away - Remastered 2011", "Someone"))
        // An "Artist - Track" upload is the track.
        assertTrue(heard.knows("b2", "Africa", "Toto"))
        // Another artist's song of the same name is another song, and an unknown one is unknown.
        assertFalse(heard.knows("c", "Take Me Away", "Someone Else"))
        assertFalse(heard.knows("d", "Never Heard Of It", "Someone"))
        assertEquals(heard.knows("a2", "Take Me Away (Live)", "Someone"), heard.knowsKey("a2", versionKey("Take Me Away (Live)", "Someone")))
        assertFalse(heard.knowsKey("x", null))
    }

    @Test
    fun `a title that is nothing but brackets matches nothing but its own id`() {
        val heard = HeardSet.of(listOf(KnownSong("odd", "【MV】", "Someone")))
        assertTrue(heard.knows("odd", "【MV】", "Someone"))
        assertFalse(heard.knows("other", "【MV】", "Someone"))
        assertFalse(heard.knows("other", "(Live)", "Someone"))
    }

    @Test
    fun `adding songs it holds changes nothing and reads no title`() {
        val heard = HeardSet.of(listOf(KnownSong("a", "Song", "Artist")))
        assertSame(heard, heard.plus(listOf(KnownSong("a", null, null))))
        assertSame(heard, heard.plus(emptyList()))
        val more = heard.plus(listOf(KnownSong("b", "Other", "Artist"), KnownSong("b", "Other", "Artist")))
        assertEquals(2, more.size); assertEquals(1, heard.size)
        assertTrue(more.knows("b", null, null)); assertFalse(heard.knows("b", null, null))
        assertEquals(0, HeardSet.EMPTY.size)
    }

    @Test
    fun `the engine adds what its own input says to what the database said`() {
        fun row(id: String, liked: Boolean = false, inLibrary: Boolean = false, isLocal: Boolean = false, downloaded: Boolean = false) =
            SongRow(id, "Title $id", "art", "Artist", liked, null, inLibrary, isLocal, downloaded = downloaded)
        val songs = listOf(row("liked", liked = true), row("saved", inLibrary = true), row("file", isLocal = true), row("dl", downloaded = true),
            row("played"), row("never")).associateBy { it.id }
        val listens = listOf(
            ListenRow("played", now - 1000, now - 900, 100, 200_000, EndReason.SKIPPED, PlayOrigin.SEARCH.code, 0, 1, 0),
            ListenRow("gone", now - 800, now - 700, 100, 200_000, EndReason.ENDED, PlayOrigin.SEARCH.code, 0, 1, 0),
        )
        val fromDatabase = HeardSet.of(listOf(KnownSong("listed", "In A Playlist", "Artist")))
        val heard = EngineRow.heardOf(EngineInput(now, songs, listens, emptyList(), known = fromDatabase))
        for (id in listOf("liked", "saved", "file", "dl", "played", "gone", "listed")) assertTrue(id, heard.knows(id, null, null))
        assertFalse(heard.knows("never", "Title never", "Artist"))
        // A song the table no longer holds is known by its id alone, and matches no other.
        assertFalse(heard.knows("x", null, null))
    }
}
