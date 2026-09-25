/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import androidx.media3.exoplayer.offline.Download
import com.dd3boh.outertune.playback.DownloadUtil.Companion.STATE_DOWNLOADING
import com.dd3boh.outertune.playback.DownloadUtil.Companion.STATE_INVALID
import com.dd3boh.outertune.playback.LikedCatchUp.End
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import kotlin.random.Random

/**
 * When the liked-songs catch up is finished, and what it says it did.
 *
 * Both of its bugs lived here. It counted a song the moment it was queued, so a 300 song run
 * announced itself done in seconds, and once that was fixed a single failed download held it at
 * 299 of 300 until somebody stopped it, because a failed song never lands and the run waited for
 * all of them to.
 */
class LikedCatchUpTest {

    /**
     * One run, fed the same updates media3 gives DownloadUtil's listener and treated the same way:
     * the map through stateToLocalDateTime, dropping anything that comes out invalid, and the
     * endings through afterUpdate.
     */
    private class Run(val batch: Set<String>) {
        var map: Map<String, LocalDateTime> = emptyMap()
        var ends: Map<String, End> = emptyMap()
        private var clock = 1_000_000L

        fun update(id: String, state: Int) {
            val value = stateToLocalDateTime(state, clock++)
            map = if (value == STATE_INVALID) map - id else map + (id to value)
            ends = LikedCatchUp.afterUpdate(ends, batch, id, state)
        }

        fun updateAll(state: Int) = batch.forEach { update(it, state) }

        val tally get() = LikedCatchUp.tally(batch, map, ends)
    }

    private fun ids(n: Int) = (1..n).map { "song$it" }.toSet()

    @Test
    fun `a queued or downloading song has not landed`() {
        val run = Run(ids(300))
        run.updateAll(Download.STATE_QUEUED)
        run.update("song1", Download.STATE_DOWNLOADING)

        // The first half of the bug: 300 queued songs used to read as 300 landed.
        assertEquals(0, run.tally.landed)
        assertEquals(300, run.tally.open)
        assertFalse(run.tally.settled)
    }

    @Test
    fun `a song media3 has not answered for yet keeps the run open`() {
        val run = Run(ids(3))

        // Straight after the enqueue loop, before the service has handed anything to media3.
        assertEquals(3, run.tally.open)
        assertFalse(run.tally.settled)

        run.update("song1", Download.STATE_COMPLETED)
        run.update("song2", Download.STATE_COMPLETED)
        assertFalse(run.tally.settled)
    }

    @Test
    fun `a failed download settles the run instead of holding it one short`() {
        val run = Run(ids(300))
        run.updateAll(Download.STATE_QUEUED)
        (1..299).forEach { run.update("song$it", Download.STATE_COMPLETED) }
        run.update("song300", Download.STATE_DOWNLOADING)
        assertFalse(run.tally.settled)

        run.update("song300", Download.STATE_FAILED)

        // What the listener leaves behind for a failure: nothing at all in the map, the same as a
        // song nobody queued. That is why the run cannot learn about it from the map.
        assertFalse("song300" in run.map)
        assertEquals(LikedCatchUp.Tally(landed = 299, failed = 1, removed = 0, open = 0), run.tally)
        assertTrue(run.tally.settled)
    }

    @Test
    fun `a run where everything fails still ends`() {
        val run = Run(ids(5))
        run.updateAll(Download.STATE_QUEUED)
        run.updateAll(Download.STATE_DOWNLOADING)
        run.updateAll(Download.STATE_FAILED)

        assertEquals(LikedCatchUp.Tally(landed = 0, failed = 5, removed = 0, open = 0), run.tally)
        assertTrue(run.tally.settled)
    }

    @Test
    fun `a song taken off the queue settles without counting as a failure`() {
        val run = Run(ids(3))
        run.updateAll(Download.STATE_QUEUED)
        run.update("song1", Download.STATE_COMPLETED)
        run.update("song2", Download.STATE_COMPLETED)

        // Cancelled from the song's own menu while the run was going.
        run.update("song3", Download.STATE_REMOVING)

        assertEquals(LikedCatchUp.Tally(landed = 2, failed = 0, removed = 1, open = 0), run.tally)
        assertTrue(run.tally.settled)
    }

    @Test
    fun `the user's own stop is not counted as failures`() {
        val run = Run(ids(10))
        run.updateAll(Download.STATE_QUEUED)
        (1..3).forEach { run.update("song$it", Download.STATE_COMPLETED) }
        run.update("song4", Download.STATE_FAILED)
        run.update("song5", Download.STATE_DOWNLOADING)

        // What cancelLikedDownloads sends for everything still queued or in flight. media3 cancels
        // the running task rather than failing it, so these only ever come back as removing.
        run.map.filterValues { it == STATE_DOWNLOADING }.keys.forEach {
            run.update(it, Download.STATE_REMOVING)
        }

        assertEquals(LikedCatchUp.Tally(landed = 3, failed = 1, removed = 6, open = 0), run.tally)
    }

    @Test
    fun `a failed song queued again is open again, and can still land`() {
        val run = Run(setOf("a"))
        run.update("a", Download.STATE_QUEUED)
        run.update("a", Download.STATE_FAILED)
        assertEquals(1, run.tally.failed)

        run.update("a", Download.STATE_QUEUED)
        assertEquals(LikedCatchUp.Tally(landed = 0, failed = 0, removed = 0, open = 1), run.tally)

        run.update("a", Download.STATE_DOWNLOADING)
        run.update("a", Download.STATE_COMPLETED)
        assertEquals(LikedCatchUp.Tally(landed = 1, failed = 0, removed = 0, open = 0), run.tally)
    }

    @Test
    fun `a song removed and then queued again while the removal runs is open again`() {
        // Stop, then start straight away: the new run's add lands on a download media3 is still
        // removing, which media3 reports as restarting and then queued.
        val run = Run(setOf("a"))
        run.update("a", Download.STATE_REMOVING)
        assertTrue(run.tally.settled)

        run.update("a", Download.STATE_RESTARTING)
        assertFalse(run.tally.settled)
        run.update("a", Download.STATE_QUEUED)
        assertFalse(run.tally.settled)
    }

    @Test
    fun `the map outranks an ending recorded earlier`() {
        val batch = setOf("a", "b")
        val ends = mapOf("a" to End.FAILED, "b" to End.REMOVED)
        val map = mapOf("a" to STATE_DOWNLOADING, "b" to LocalDateTime.of(2026, 9, 25, 12, 0))

        assertEquals(
            LikedCatchUp.Tally(landed = 1, failed = 0, removed = 0, open = 1),
            LikedCatchUp.tally(batch, map, ends),
        )
    }

    @Test
    fun `a song that landed and then left the map still counts as landed`() {
        val run = Run(setOf("a", "b"))
        run.updateAll(Download.STATE_QUEUED)
        run.update("a", Download.STATE_COMPLETED)
        run.update("b", Download.STATE_COMPLETED)

        // deleteSong drops the entry without media3 hearing about it.
        run.map = run.map - "a"

        assertEquals(LikedCatchUp.Tally(landed = 2, failed = 0, removed = 0, open = 0), run.tally)
    }

    @Test
    fun `a song still marked invalid after a scan is open until media3 answers`() {
        // A rescan can put STATE_INVALID in the map for a song media3 once failed, and that song is
        // exactly the kind the catch up picks up again.
        val batch = setOf("a")
        assertEquals(1, LikedCatchUp.tally(batch, mapOf("a" to STATE_INVALID), emptyMap()).open)
    }

    @Test
    fun `other downloads in the map are none of the run's business`() {
        val run = Run(setOf("a"))
        run.update("a", Download.STATE_COMPLETED)

        // An album started by hand, still going and failing alongside the run.
        run.update("album1", Download.STATE_DOWNLOADING)
        run.update("album2", Download.STATE_FAILED)

        assertEquals(LikedCatchUp.Tally(landed = 1, failed = 0, removed = 0, open = 0), run.tally)
        assertEquals(setOf("a"), run.ends.keys)
    }

    @Test
    fun `a song the download service refused counts as failed`() {
        val ends = LikedCatchUp.neverSent(emptyMap(), "a")
        val tally = LikedCatchUp.tally(setOf("a", "b"), mapOf("b" to STATE_DOWNLOADING), ends)
        assertEquals(LikedCatchUp.Tally(landed = 0, failed = 1, removed = 0, open = 1), tally)
    }

    @Test
    fun `over any order of updates, the counts add up and settle exactly when every song has ended`() {
        val states = listOf(
            Download.STATE_QUEUED,
            Download.STATE_DOWNLOADING,
            Download.STATE_COMPLETED,
            Download.STATE_FAILED,
            Download.STATE_REMOVING,
            Download.STATE_RESTARTING,
        )
        val random = Random(9)
        val run = Run(ids(40))
        val last = mutableMapOf<String, Int>()

        repeat(3_000) {
            val id = if (random.nextInt(10) == 0) "other${random.nextInt(5)}" else "song${random.nextInt(1, 41)}"
            val state = states[random.nextInt(states.size)]
            run.update(id, state)
            if (id in run.batch) last[id] = state

            val tally = run.tally
            assertEquals(run.batch.size, tally.landed + tally.failed + tally.removed + tally.open)
            assertEquals(last.values.count { it == Download.STATE_COMPLETED }, tally.landed)
            assertEquals(last.values.count { it == Download.STATE_FAILED }, tally.failed)
            assertEquals(last.values.count { it == Download.STATE_REMOVING }, tally.removed)
            val ended = run.batch.all {
                last[it] in setOf(Download.STATE_COMPLETED, Download.STATE_FAILED, Download.STATE_REMOVING)
            }
            assertEquals(ended, tally.settled)
        }
    }
}
