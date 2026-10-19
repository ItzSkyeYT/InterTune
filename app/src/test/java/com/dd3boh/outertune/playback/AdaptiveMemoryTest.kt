/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.engine.Features
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A radio closed by force comes back with what it had played (AdaptiveMemory): before this, the
 * queue sheet folded the songs already heard behind "N more songs waiting" after a reopen, and the
 * planner and the radio's next page started from nothing.
 */
class AdaptiveMemoryTest {

    private var now = 1_000_000_000L
    private fun heard() = AdaptiveQueue.Heard(clock = { now })

    /** A radio run of queue 5, run 77, that played A to D, skipped B and had a pick and a song put in by hand. */
    private fun played(): AdaptiveQueue.Heard = heard().apply {
        note(5L, "A", "Alpha", listOf("Band One"), 200, runId = 77L, radio = true)
        left(AdaptiveQueue.Leave.RAN_OUT)
        note(5L, "B", "Beta", listOf("Band Two"), 200, runId = 77L, radio = true)
        left(AdaptiveQueue.Leave.SKIPPED)
        note(5L, "C", "Gamma", listOf("Band Three"), 200, runId = 77L, radio = true)
        left(AdaptiveQueue.Leave.KEPT)
        note(5L, "D", "Delta", listOf("Band Four"), 200, runId = 77L, radio = true)
        putInByHand(5L, 77L, listOf("H"), radio = true)
        picked(mapOf("P" to "C"))
        note = AdaptiveNote.Note(AdaptiveNote.Kind.FINISH, 1, picks = setOf("P"), setAt = 4)
    }

    private fun reopened(text: String): AdaptiveQueue.Heard = heard().apply { offer(AdaptiveMemory.decode(text)) }

    @Test
    fun `a run comes back as it was when its queue plays again`() {
        val text = AdaptiveMemory.encode(played().saved())
        val back = reopened(text)
        // The song playing at the close, noted again as the queue loads.
        back.note(5L, "D", "Delta", listOf("Band Four"), 200, runId = 77L, radio = true)

        assertEquals(listOf("A", "B", "C", "D"), back.reached.toList())
        assertEquals("the song playing is not heard twice", listOf("Delta", "Gamma", "Beta", "Alpha"), back.titles(5))
        assertEquals(setOf("H"), back.byHand)
        assertEquals(setOf("P"), back.unreachedPicks())
        assertEquals(AdaptiveNote.Kind.FINISH, back.note?.kind)
        val view = back.view()
        assertEquals("the keeps come back as seeds", listOf("C", "A"), view.seeds)
        assertEquals(setOf("band two"), view.artistSkips)
        assertEquals("what it had played, the one playing included", 4, back.played)
        assertEquals(
            "Adaptive queue: restored run:77: 4 reached, 1 picks",
            back.takeRestored()?.let { AdaptivePass.restoredLine(it) },
        )
        assertNull("said once", back.takeRestored())
    }

    @Test
    fun `the fold is where it was after a reopen`() {
        val back = reopened(AdaptiveMemory.encode(played().saved()))
        back.note(5L, "D", "Delta", listOf("Band Four"), 200, runId = 77L, radio = true)
        val queue = listOf("A", "B", "C", "D", "E", "F", "G", "H", "I", "J")
        // Back at A after the reopen: everything played since stays on show.
        assertEquals(3, AdaptiveQueue.settledThrough(0, queue, { it }, back.reached))
    }

    @Test
    fun `runs read back and not played again yet stay in the file through the next process's writes`() {
        val two = heard().apply {
            note(4L, "R", "Rho", listOf("Band R"), 200, runId = 66L, radio = true)
            left(AdaptiveQueue.Leave.RAN_OUT)
        }
        now += 60_000L
        two.note(5L, "A", "Alpha", listOf("Band One"), 200, runId = 77L, radio = true)
        val back = reopened(AdaptiveMemory.encode(two.saved()))

        // A write before anything plays, a song swiped from the saved queue for instance.
        assertEquals(listOf("run:77", "run:66"), back.saved().map { it.key })
        // Radio 77 plays again, and the app is closed once more before 66 does.
        back.note(5L, "A", "Alpha", listOf("Band One"), 200, runId = 77L, radio = true)
        val again = reopened(AdaptiveMemory.encode(back.saved()))
        again.note(4L, "R", "Rho", listOf("Band R"), 200, runId = 66L, radio = true)

        assertEquals("what it played came through both closes", listOf("R"), again.reached.toList())
        assertEquals(listOf("R"), again.view().seeds)
    }

    @Test
    fun `a run saved for another queue or run is left alone`() {
        val back = reopened(AdaptiveMemory.encode(played().saved()))
        back.note(6L, "X", "Other", listOf("Band Nine"), 200, runId = 78L, radio = true)

        assertEquals(setOf("X"), back.reached)
        assertTrue(back.byHand.isEmpty())
        assertTrue(back.unreachedPicks().isEmpty())
    }

    @Test
    fun `over an hour and a half later what it played comes back and what it learned does not`() {
        val text = AdaptiveMemory.encode(played().saved())
        now += Features.RUN_WINDOW_MS + 60_000L
        val back = reopened(text)
        back.note(5L, "D", "Delta", listOf("Band Four"), 200, runId = 77L, radio = true)

        assertEquals(listOf("A", "B", "C", "D"), back.reached.toList())
        assertEquals(setOf("H"), back.byHand)
        assertEquals(setOf("P"), back.unreachedPicks())
        assertNull("the note described a sitting that is over", back.note)
        assertEquals(
            "Adaptive queue: restored run:77: 4 reached, 1 picks, older than 90 minutes so skips and keeps left out",
            back.takeRestored()?.let { AdaptivePass.restoredLine(it) },
        )
        val view = back.view()
        assertTrue("no skips from that sitting", view.artistSkips.isEmpty())
        assertEquals(listOf("Delta"), back.titles(5))
        assertEquals("nor its keeps: the run's first song is the one noted now", listOf("D"), view.seeds)
    }

    @Test
    fun `while listening history is paused the skips and keeps stay out of the file`() {
        val back = reopened(AdaptiveMemory.encode(played().saved(), withEvidence = false))
        back.note(5L, "D", "Delta", listOf("Band Four"), 200, runId = 77L, radio = true)

        assertEquals(listOf("A", "B", "C", "D"), back.reached.toList())
        assertTrue(back.view().artistSkips.isEmpty())
    }

    @Test
    fun `text that is not the file gives nothing and never throws`() {
        assertTrue(AdaptiveMemory.decode("").isEmpty())
        assertTrue(AdaptiveMemory.decode("{not json").isEmpty())
        assertTrue(AdaptiveMemory.decode("""{"v":99,"runs":[]}""").isEmpty())
        assertTrue(AdaptiveMemory.decode("""{"v":1,"runs":[{"key":"run:1"}]}""").isEmpty())
        assertTrue(AdaptiveMemory.decode("[1,2,3]").isEmpty())
    }

    @Test
    fun `only radio runs are kept, the newest four, each with its newest five hundred songs`() {
        val h = heard()
        h.note(1L, "P1", "Playlist song", runId = 1L, radio = false)
        for (run in 2L..7L) {
            for (i in 0 until 600) h.note(run, "s$run-$i", "Song $i", listOf("Band"), 200, runId = run, radio = true)
        }
        val saved = AdaptiveMemory.decode(AdaptiveMemory.encode(h.saved()))

        assertEquals(listOf("run:7", "run:6", "run:5", "run:4"), saved.map { it.key })
        assertEquals(AdaptiveMemory.KEEP, saved.first().reached.size)
        assertEquals("s7-599", saved.first().reached.last())
        assertEquals("s7-100", saved.first().reached.first())
    }
}
