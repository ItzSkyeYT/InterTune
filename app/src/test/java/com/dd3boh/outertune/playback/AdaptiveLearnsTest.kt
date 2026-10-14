/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.engine.SongTags
import com.dd3boh.outertune.engine.TagFit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The far end of the queue learning from what the listener does (Unreleased.ADAPTIVE_LEARNS).
 *
 * Before this it read only the words in titles, so in a run of plain recordings it never dropped
 * anything, and skipping three songs by one artist left that artist's other songs waiting.
 */
class AdaptiveLearnsTest {

    private data class Row(val title: String, val artist: String)

    private fun plainRun(vararg titles: String) = TagFit.context(titles.map { SongTags.of(it) })

    private val plain = plainRun("Plain One", "Plain Two", "Plain Three", "Plain Four")

    private fun song(id: String, title: String, artist: String, length: Int = 200) =
        AdaptiveQueue.Song(id, title, listOf(artist), length)

    private fun plan(tail: List<Row>, turnedDown: AdaptiveQueue.TurnedDown = AdaptiveQueue.TurnedDown(), plainRun: Boolean = false) =
        AdaptiveQueue.plan(tail, { it.title }, plain, artists = { listOf(it.artist) }, turnedDown = turnedDown, plainRun = plainRun)

    private val tail = listOf(
        Row("Morning", "Band A"), Row("Noon", "Band B"), Row("Evening", "Band A"),
        Row("Night", "Band C"), Row("Dawn", "Band D"), Row("Dusk", "Band A"),
        Row("Rain", "Band E"), Row("Snow", "Band F"), Row("Wind", "Band G"), Row("Sun", "Band H"),
    )

    @Test
    fun `two skips of one artist take their other songs out, one does not`() {
        val heard = AdaptiveQueue.Heard()
        heard.note(1L, "1", "First", listOf("Band A"), 200)
        heard.skipped(song("1", "First", "Band A"))
        assertTrue("one skip is a mood", heard.turnedDown().artists.isEmpty())

        heard.note(1L, "2", "Second", listOf("Band A"), 200)
        heard.skipped(song("2", "Second", "Band A"))
        val plan = plan(tail, heard.turnedDown())

        assertEquals(listOf("Morning", "Evening", "Dusk"), plan.dropped.map { it.title })
        assertEquals("order kept", tail.filter { it.artist != "Band A" }, plan.keep)
    }

    @Test
    fun `a song heard to its end forgives its artist`() {
        val heard = AdaptiveQueue.Heard()
        heard.skipped(song("1", "First", "Band A"))
        heard.finished(song("2", "Second", "Band A"))
        heard.skipped(song("3", "Third", "Band A"))

        assertTrue(heard.turnedDown().artists.isEmpty())
    }

    @Test
    fun `a skipped song takes its other versions with it`() {
        val heard = AdaptiveQueue.Heard()
        heard.skipped(song("1", "Paper Moon (Slowed + Reverb)", "Uploader One"))
        val withVersions = tail + Row("Paper Moon (Sped Up)", "Uploader Two") + Row("Paper Moon", "The Band")
        val plan = plan(withVersions, heard.turnedDown())

        assertEquals(listOf("Paper Moon (Sped Up)", "Paper Moon"), plan.dropped.map { it.title })
    }

    @Test
    fun `a plain run sheds the treated versions ahead, not remixes, edits or live takes`() {
        val mixed = listOf(
            Row("A (Slowed + Reverb)", "x"), Row("B (Radio Edit)", "x"), Row("C (Nightcore)", "x"),
            Row("D (Remix)", "x"), Row("E (Live)", "x"), Row("F", "x"), Row("G (Sped Up)", "x"),
            Row("H", "x"), Row("I", "x"), Row("J", "x"),
        )
        val before = plan(mixed)
        assertFalse("before, a plain run dropped nothing at all", before.changed)

        val after = plan(mixed, plainRun = true)
        assertEquals(listOf("A (Slowed + Reverb)", "C (Nightcore)", "G (Sped Up)"), after.dropped.map { it.title })
    }

    @Test
    fun `what was turned down still goes no further than the share`() {
        val heard = AdaptiveQueue.Heard()
        heard.skipped(song("1", "First", "Band A"))
        heard.skipped(song("2", "Second", "Band A"))
        val allA = (1..10).map { Row("Song $it", "Band A") }
        val plan = plan(allA, heard.turnedDown())

        assertEquals(4, plan.dropped.size)
        assertEquals("the nearest four go, the rest wait for the next pass", allA.drop(4), plan.keep)
    }

    @Test
    fun `a plain run is four plain songs in a row`() {
        assertTrue(AdaptiveQueue.plainRun(listOf("A", "B (Remix)", "C (Live)", "D (Radio Edit)", "E (Slowed)")))
        assertFalse("too few to call", AdaptiveQueue.plainRun(listOf("A", "B", "C")))
        assertFalse(AdaptiveQueue.plainRun(listOf("A", "B (Slowed)", "C", "D")))
    }

    @Test
    fun `left early means under half heard, or under half a minute when the length is not known`() {
        assertTrue(AdaptiveQueue.leftEarly(60_000, 200))
        assertFalse("the last chorus is not a verdict", AdaptiveQueue.leftEarly(180_000, 200))
        assertTrue(AdaptiveQueue.leftEarly(20_000, -1))
        assertFalse(AdaptiveQueue.leftEarly(45_000, -1))
        assertFalse("nothing known, nothing said", AdaptiveQueue.leftEarly(null, 200))
    }

    @Test
    fun `a new queue forgets what was turned down`() {
        val heard = AdaptiveQueue.Heard()
        heard.note(1L, "1", "First", listOf("Band A"), 200)
        heard.skipped(song("1", "First", "Band A"))
        heard.skipped(song("2", "Second", "Band A"))
        heard.note(2L, "9", "Other", listOf("Band Z"), 200)

        assertEquals(AdaptiveQueue.TurnedDown(), heard.turnedDown())
    }
}
