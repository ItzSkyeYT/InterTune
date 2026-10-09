/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.zionhuang.innertube.models.Artist
import com.zionhuang.innertube.models.SongItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which search result may be played in place of a song whose own id is gone: the same recording,
 * and nothing that merely carries its name.
 *
 * The cost of being wrong is somebody tapping a song and hearing its slowed edit, or a cover, with
 * nothing on screen to say so. So what is not surely the song is turned down, and the song then
 * fails as it did before.
 */
class StandInTest {
    private fun result(id: String, title: String, seconds: Int?, vararg artists: String = arrayOf("Daft Punk"), explicit: Boolean = false) =
        SongItem(
            id = id,
            title = title,
            artists = artists.map { Artist(name = it, id = null) },
            album = null,
            duration = seconds,
            thumbnail = "",
            explicit = explicit,
        )

    private val wanted = StandIn.Wanted("Instant Crush", listOf("Daft Punk", "Julian Casablancas"), 337)
    private val own = "OLDID000001"

    private fun pick(vararg found: SongItem, of: StandIn.Wanted = wanted) = StandIn.pick(of, own, found.toList())?.id

    @Test
    fun `the same title, artists and length under another id is the song`() {
        assertEquals("NEWID000001", pick(result("NEWID000001", "Instant Crush", 337, "Daft Punk", "Julian Casablancas")))
    }

    @Test
    fun `a few seconds between two uploads of a recording do not matter`() {
        assertEquals("NEWID000001", pick(result("NEWID000001", "Instant Crush", 341, "Daft Punk", "Julian Casablancas")))
        // Six seconds in a hundred is another edit, where six in three hundred was a fade.
        val short = StandIn.Wanted("Interlude", listOf("Somebody"), 100)
        assertNull(pick(result("NEWID000002", "Interlude", 106, "Somebody"), of = short))
    }

    @Test
    fun `the song's own id is never its stand-in`() {
        assertNull(pick(result(own, "Instant Crush", 337, "Daft Punk", "Julian Casablancas")))
    }

    @Test
    fun `a version of the song is not the song`() {
        for (title in listOf(
            "Instant Crush (Slowed)", "Instant Crush (Live)", "Instant Crush (Skrillex Remix)",
            "Instant Crush (Extended Mix)", "Instant Crush - Sped Up", "Instant Crush (Instrumental)",
        )) {
            assertNull(title, pick(result("NEWID000001", title, 337, "Daft Punk", "Julian Casablancas")))
        }
    }

    @Test
    fun `a radio edit reads as the same title and is told apart by its length`() {
        assertNull(pick(result("NEWID000001", "Instant Crush (Radio Edit)", 210, "Daft Punk", "Julian Casablancas")))
        // The same label on the same recording is only a label.
        assertEquals("NEWID000002", pick(result("NEWID000002", "Instant Crush (Album Version)", 337, "Daft Punk", "Julian Casablancas")))
    }

    @Test
    fun `somebody else's recording of it is not the song`() {
        assertNull(pick(result("NEWID000001", "Instant Crush", 337, "Natalie Imbruglia")))
        assertNull(pick(result("NEWID000002", "Instant Crush", 337, "Karaoke Hits")))
    }

    @Test
    fun `the first artist alone will do, with the guest named in the title or nowhere`() {
        assertEquals("NEWID000001", pick(result("NEWID000001", "Instant Crush (feat. Julian Casablancas)", 337, "Daft Punk")))
        assertEquals("NEWID000002", pick(result("NEWID000002", "Instant Crush", 337, "Daft Punk")))
        // The guest alone is not the song's artist.
        assertNull(pick(result("NEWID000003", "Instant Crush", 337, "Julian Casablancas")))
    }

    @Test
    fun `a length that is not known on either side settles nothing`() {
        assertNull(pick(result("NEWID000001", "Instant Crush", null, "Daft Punk", "Julian Casablancas")))
        val unknown = StandIn.Wanted("Instant Crush", listOf("Daft Punk", "Julian Casablancas"), null)
        assertNull(pick(result("NEWID000001", "Instant Crush", 337, "Daft Punk", "Julian Casablancas"), of = unknown))
    }

    @Test
    fun `of two that are the song, the closer in length is played, then the one as explicit as it`() {
        assertEquals(
            "CLOSER00001",
            pick(
                result("FURTHER0001", "Instant Crush", 345, "Daft Punk", "Julian Casablancas"),
                result("CLOSER00001", "Instant Crush", 337, "Daft Punk", "Julian Casablancas"),
            ),
        )
        val explicit = StandIn.Wanted("Instant Crush", listOf("Daft Punk"), 337, explicit = true)
        assertEquals(
            "EXPLICIT001",
            pick(result("CLEAN000001", "Instant Crush", 337), result("EXPLICIT001", "Instant Crush", 337, explicit = true), of = explicit),
        )
        // And YouTube's own order when nothing tells them apart.
        assertEquals("FIRST000001", pick(result("FIRST000001", "Instant Crush", 337), result("SECOND00001", "Instant Crush", 337), of = StandIn.Wanted("Instant Crush", listOf("Daft Punk"), 337)))
    }

    @Test
    fun `only the first results are read`() {
        val noise = (1..StandIn.CANDIDATES).map { result("NOISE00000$it", "Something Else $it", 200, "Nobody") }
        assertNull(pick(*noise.toTypedArray(), result("NEWID000001", "Instant Crush", 337, "Daft Punk", "Julian Casablancas")))
    }

    @Test
    fun `the search is made for the title and the first artist`() {
        assertEquals("Instant Crush Daft Punk", wanted.query)
        assertEquals("Instant Crush", StandIn.Wanted("Instant Crush", emptyList(), 337).query)
    }
}
