/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What is written for the recommendation rows of a song nobody can play any more, and when it is
 * taken back. The database is three functions here, and each call to them is written down.
 */
class GoneSongsTest {
    private val done = mutableListOf<String>()

    private fun songs(already: List<String>? = emptyList()) = GoneSongs(
        load = { at -> done += "load at $at"; already },
        mark = { id, at -> done += "mark $id at $at" },
        lift = { ids -> done += "lift $ids" },
        now = { 1_000L },
    )

    @Test
    fun `a song called gone once is not written`() {
        val songs = songs()

        songs.gone("OLDID000001")

        assertEquals(emptyList<String>(), done)
    }

    @Test
    fun `called gone again with nothing played in between, it is still not written`() {
        // The player asks again at every retry, and YouTube can say "unavailable" for a while of
        // songs that are there: neither is a second opinion.
        val songs = songs()

        songs.gone("OLDID000001")
        songs.gone("OLDID000001")
        songs.gone("OLDID000001")

        assertEquals(emptyList<String>(), done)
    }

    @Test
    fun `called gone, then another song is had, then called gone again, it is written`() {
        val songs = songs()

        songs.gone("OLDID000001")
        songs.plays("FINE0000001")
        done.clear()
        songs.gone("OLDID000001")

        assertEquals(listOf("mark OLDID000001 at 1000"), done)
    }

    @Test
    fun `a spell of false answers writes nothing, however many songs it takes`() {
        val songs = songs()

        for (id in listOf("OLDID000001", "OLDID000002", "OLDID000003", "OLDID000001", "OLDID000002")) songs.gone(id)

        assertEquals(emptyList<String>(), done)
        // And once it is over, each of them plays, and nothing was ever said of them.
        songs.plays("OLDID000001")
        songs.gone("OLDID000001")
        assertEquals("what was counted before it played is forgotten", listOf("load at 1000"), done)
    }

    @Test
    fun `a song written comes off when a stream is had for it, and one never written costs nothing`() {
        val songs = songs(already = listOf("OLDID000001"))

        songs.plays("ANOTHER0001")
        songs.plays("OLDID000001")
        songs.plays("OLDID000001")

        assertEquals(listOf("load at 1000", "lift [OLDID000001]"), done)
    }

    @Test
    fun `a song written, played, and gone twice more is written again`() {
        val songs = songs()
        songs.gone("OLDID000001"); songs.plays("FINE0000001"); songs.gone("OLDID000001")
        songs.plays("OLDID000001")
        done.clear()

        songs.gone("OLDID000001")
        songs.plays("FINE0000001")
        songs.gone("OLDID000001")

        assertEquals(listOf("mark OLDID000001 at 1000"), done)
    }

    @Test
    fun `with a database that does not answer a song is still written, and nothing is lifted on a guess`() {
        val songs = songs(already = null)

        songs.gone("OLDID000001")
        songs.plays("FINE0000001")
        songs.gone("OLDID000001")
        songs.plays("OLDID000001")

        assertEquals(listOf("load at 1000", "mark OLDID000001 at 1000", "load at 1000", "load at 1000"), done)
    }
}
