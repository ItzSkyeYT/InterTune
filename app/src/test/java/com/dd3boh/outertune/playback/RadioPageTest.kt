/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.models.MediaMetadata
import java.io.File
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What of a radio's next page joins the queue.
 *
 * The page is asked from a song already in the queue, so much of it is the queue again, and it was
 * appended whole: in the first half of October 2026, 3.9 percent of the songs that autoplayed had
 * played earlier in the same run, and 74 percent of those were skipped.
 */
class RadioPageTest {

    private fun song(id: String, title: String = "Song $id", artist: String? = "Artist $id") = MediaMetadata(
        id = id,
        title = title,
        artists = listOfNotNull(artist).map { MediaMetadata.Artist(id = null, name = it) },
        duration = 200,
        genre = null,
    )

    private fun ids(songs: List<MediaMetadata>) = songs.map { it.id }

    @Test
    fun `a song the queue holds does not join it again`() {
        val queue = listOf(song("A"), song("B"), song("C"))
        val page = listOf(song("B"), song("D"), song("C"), song("E"))

        assertEquals(listOf("D", "E"), ids(RadioPage.fresh(page, queue)))
    }

    @Test
    fun `a song reached in this run does not come back, even once it has left the queue`() {
        // B played, and was then taken out of the queue by hand.
        val queue = listOf(song("A"), song("C"))
        val page = listOf(song("B"), song("D"))

        assertEquals(listOf("D"), ids(RadioPage.fresh(page, queue, reached = setOf("A", "B", "C"))))
    }

    @Test
    fun `another version of a song already had counts as that song`() {
        val africa = song("A1", "Africa", "Toto")
        // Rosanna was heard and has left the queue since. Africa is still in it.
        val heard = AdaptiveQueue.Heard().apply { note(1L, "R1", "Rosanna", listOf("Toto")) }
        val page = listOf(
            song("A2", "Africa (Live)", "Toto"),
            song("R2", "Rosanna - 2008 Remaster", "Toto"),
            song("H1", "Hold the Line", "Toto"),
        )

        assertEquals(listOf("H1"), ids(RadioPage.fresh(page, listOf(africa), heard.reached, heard.reachedVersions)))
    }

    @Test
    fun `the same title by somebody else is another song`() {
        val page = listOf(song("W1", "Africa", "Weezer"))

        assertEquals(listOf("W1"), ids(RadioPage.fresh(page, listOf(song("A1", "Africa", "Toto")))))
    }

    @Test
    fun `the page keeps its order, and what it lists twice joins once`() {
        val page = listOf(song("D"), song("E"), song("D"), song("F", "Song E (Live)", "Artist E"), song("G"))

        assertEquals(listOf("D", "E", "G"), ids(RadioPage.fresh(page, listOf(song("A")))))
    }

    @Test
    fun `a title with nothing to compare is matched by its id alone`() {
        // A title that is nothing but brackets has no base, and two of them are not one song.
        val page = listOf(song("X", "【MV】", "Artist"), song("Y", "(Intro)", "Artist"))

        assertNull(RadioPage.versionOf("(Intro)", "Artist"))
        assertEquals(listOf("X", "Y"), ids(RadioPage.fresh(page, listOf(song("Z", "[Official Video]", "Artist")))))
    }

    @Test
    fun `the versions reached start over with the queue, as the songs reached do`() {
        val heard = AdaptiveQueue.Heard().apply { note(1L, "R1", "Rosanna", listOf("Toto")) }
        heard.note(2L, "X", "Something Else", listOf("Someone"))
        val page = listOf(song("R2", "Rosanna (Live)", "Toto"))

        assertEquals(listOf("R2"), ids(RadioPage.fresh(page, emptyList(), heard.reached, heard.reachedVersions)))
    }

    // The song the next page is asked from

    @Test
    fun `the next page comes from a song let finish first, then from what just joined`() {
        val joined = listOf(song("P"), song("Q"), song("R"), song("S"), song("T"))

        assertEquals("K", RadioPage.nextSeed("K", joined, listOf(song("J"), song("K")), asked = "J"))
        repeat(20) { n ->
            val seed = RadioPage.nextSeed(null, joined, listOf(song("J")), asked = "J", random = Random(n))
            assertTrue(seed, seed in listOf("Q", "R", "S", "T"))
        }
    }

    @Test
    fun `a page with nothing new adds nothing and still moves the radio on`() {
        // The page was asked from F, and everything on it is in the queue already.
        val queue = listOf("A", "B", "C", "D", "E", "F").map { song(it) }
        val fresh = RadioPage.fresh(listOf(song("F"), song("B"), song("C")), queue)
        assertTrue(fresh.isEmpty())

        // From one of the newest songs in the queue other than F, so the next song change asks
        // for another page, and from somewhere else, instead of the same page again.
        repeat(20) { n ->
            val seed = RadioPage.nextSeed(anchor = null, joined = fresh, queue = queue, asked = "F", random = Random(n))
            assertTrue(seed, seed in listOf("B", "C", "D", "E"))
        }
    }

    @Test
    fun `a queue of one song asks from that song again, having nothing else`() {
        assertEquals("A", RadioPage.nextSeed(null, emptyList(), listOf(song("A")), asked = "A"))
    }

    @Test
    fun `the radio's top-up appends only what is new to the run`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        val topUp = service.substringAfter("// Auto load more songs").substringBefore("\n        // The pause point")

        assertTrue("the page goes through RadioPage.fresh", "RadioPage.fresh(mediaItems.drop(1)" in topUp)
        assertTrue("what this run reached counts, by id and by version", "heard.reached, heard.reachedVersions" in topUp)
        assertTrue("a page with nothing new still moves the seed on", "RadioPage.nextSeed(" in topUp)
        assertFalse("the page is never appended whole", "enqueueEnd(mediaItems" in topUp)
    }
}
