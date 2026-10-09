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
        load = { done += "load"; already },
        mark = { id, at -> done += "mark $id at $at" },
        lift = { ids -> done += "lift $ids" },
        now = { 1_000L },
    )

    @Test
    fun `a song called gone is written once, however often it is called that`() {
        val songs = songs()

        songs.gone("OLDID000001")
        songs.gone("OLDID000001")
        songs.gone("OLDID000002")

        assertEquals(listOf("load", "mark OLDID000001 at 1000", "mark OLDID000002 at 1000"), done)
    }

    @Test
    fun `a song already on the list from an earlier launch is not written again`() {
        val songs = songs(already = listOf("OLDID000001"))

        songs.gone("OLDID000001")

        assertEquals(listOf("load"), done)
    }

    @Test
    fun `a song that plays under its own id comes off the list, and one that was never on it costs nothing`() {
        val songs = songs(already = listOf("OLDID000001"))

        songs.plays("ANOTHER0001")
        songs.plays("OLDID000001")
        songs.plays("OLDID000001")

        assertEquals(listOf("load", "lift [OLDID000001]"), done)
    }

    @Test
    fun `a run that is not believed takes back what it wrote, and only that`() {
        val songs = songs(already = listOf("EARLIER0001"))
        songs.gone("OLDID000001")
        songs.gone("OLDID000002")
        done.clear()

        // The third of the run was never written: StandIns reports the run in its place.
        songs.doubted(listOf("OLDID000001", "OLDID000002", "OLDID000003"))

        assertEquals(listOf("lift [OLDID000001, OLDID000002]"), done)
        // What was gone before the run stays gone.
        songs.gone("EARLIER0001")
        assertEquals(listOf("lift [OLDID000001, OLDID000002]"), done)
        // And a song of the run can be called gone again another day.
        songs.gone("OLDID000001")
        assertEquals("mark OLDID000001 at 1000", done.last())
    }

    @Test
    fun `with a database that does not answer a song is still written, a run still taken back, and nothing is lifted on a guess`() {
        val songs = songs(already = null)

        songs.gone("OLDID000001")
        songs.plays("OLDID000001")
        songs.doubted(listOf("OLDID000001", "OLDID000002"))

        assertEquals(listOf("load", "mark OLDID000001 at 1000", "load", "load", "lift [OLDID000001, OLDID000002]"), done)
    }
}
