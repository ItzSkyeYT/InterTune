/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the listener keeps skipping across days, against their own habit. See SkipHabits. */
class SkipHabitsTest {

    private val day = 86_400_000L
    private val now = 1_000L * day
    private var next = 0L

    private val songs = HashMap<String, SongRow>()

    private fun song(id: String, artist: String, artistId: String? = "id-$artist", title: String = "Song $id") =
        SongRow(id, title, artistId, artist).also { songs[id] = it }

    /** A listen of [id], [daysAgo] days ago, ended as [reason] after [playedMs] of a 200 second song, each in a session of its own unless [session] says. */
    private fun listen(
        id: String, daysAgo: Double, reason: Int, playedMs: Long, learn: Boolean = true, session: Long? = null, durationMs: Long = 200_000L,
    ): ListenRow {
        val at = now - (daysAgo * day).toLong() + (next++)
        return ListenRow(id, at, at + playedMs, playedMs, durationMs, reason, 0, 0, session ?: at, 0, learn = learn)
    }

    private fun skip(id: String, daysAgo: Double, playedMs: Long = 60_000L, session: Long? = null) = listen(id, daysAgo, EndReason.SKIPPED, playedMs, session = session)
    private fun keep(id: String, daysAgo: Double) = listen(id, daysAgo, EndReason.ENDED, 200_000L)

    /** A listener with a steady background: forty songs by forty artists kept, ten skipped. */
    private fun background(): List<ListenRow> = (0 until 50).map { i ->
        song("bg$i", "Background $i")
        if (i < 40) keep("bg$i", 1.0 + i % 7) else skip("bg$i", 1.0 + i % 7)
    }

    @Test
    fun `a new install sits at one half and gives no penalty`() {
        val habits = SkipHabits(emptyList(), emptyMap(), now)
        assertEquals(0.5, habits.base, 1e-9)
        assertEquals(0.0, habits.artist("x", "X"), 1e-9)
        assertEquals(0.0, habits.of("Song", "x", "X", liked = false), 1e-9)
    }

    @Test
    fun `an artist skipped five times in a day is a lasting dislike, and one skip is a small penalty`() {
        val c = (0 until 5).map { song("c$it", "Band C") }
        val one = song("d0", "Band D")
        val rows = background() + c.map { skip(it.id, 0.2) } + skip(one.id, 0.2)
        val habits = SkipHabits(rows, songs, now)

        assertTrue("five skips with none kept", habits.artist("id-Band C", "Band C") >= SkipHabits.DISLIKE)
        val small = habits.artist("id-Band D", "Band D")
        assertTrue("one skip: $small", small > 0.0 && small < SkipHabits.DISLIKE)
    }

    @Test
    fun `a keep cancels skips`() {
        val c = (0 until 5).map { song("c$it", "Band C") }
        val rows = background() + c.map { skip(it.id, 0.5) } + (0 until 5).map { song("k$it", "Band C"); keep("k$it", 0.4) }
        assertTrue(SkipHabits(rows, songs, now).artist("id-Band C", "Band C") < SkipHabits.DISLIKE)
    }

    @Test
    fun `old skips fade`() {
        val c = (0 until 5).map { song("c$it", "Band C") }
        val fresh = SkipHabits(background() + c.map { skip(it.id, 0.5) }, songs, now).artist("id-Band C", "Band C")
        val old = SkipHabits(background() + c.map { skip(it.id, 60.0) }, songs, now).artist("id-Band C", "Band C")
        assertTrue("60 days ago is not a dislike: $old", old < SkipHabits.DISLIKE)
        assertTrue(old < fresh)
    }

    @Test
    fun `rows with no verdict do not count`() {
        val c = (0 until 6).map { song("c$it", "Band C") }
        val rows = background() + listOf(
            listen(c[0].id, 0.5, EndReason.SKIPPED, 10_000L, learn = false),
            listen(c[1].id, 0.5, EndReason.REPLACED, 10_000L),
            listen(c[2].id, 0.5, EndReason.STOPPED, 10_000L),
            listen(c[3].id, 0.5, EndReason.ERROR, 10_000L),
            listen(c[4].id, 0.5, EndReason.OPEN, 10_000L),
            listen(c[5].id, 0.5, EndReason.UNKNOWN, 10_000L),
        )
        assertEquals(0.0, SkipHabits(rows, songs, now).artist("id-Band C", "Band C"), 1e-9)
    }

    @Test
    fun `a play carried on after a stop is one play, judged by all of it`() {
        val c = (0 until 6).map { song("c$it", "Band C") }
        // Each heard to 3:00 of 3:20, the app closed, then resumed there and left at 3:10.
        var rowId = 1_000L
        val resumed = c.flatMap { s ->
            val at = now - day / 2 + (next++) * 1_000_000L
            val first = ListenRow(s.id, at, at + 180_000, 180_000, 200_000, EndReason.STOPPED, 0, 0, at, 0, id = ++rowId)
            val rest = ListenRow(s.id, at + 600_000, at + 610_000, 10_000, 200_000, EndReason.SKIPPED, 0, 0, at + 600_000, 0, id = rowId + 1, continuesListenId = rowId)
            rowId++
            listOf(first, rest)
        }
        assertEquals("heard nearly through, not skipped", 0.0, SkipHabits(background() + resumed, songs, now).artist("id-Band C", "Band C"), 1e-9)

        // Its first part a failed play, which the engine's input leaves out: the rest says nothing alone.
        val orphans = resumed.filter { it.continuesListenId != null }
        assertEquals(0.0, SkipHabits(background() + orphans, songs, now).artist("id-Band C", "Band C"), 1e-9)
        // Read row by row, as before, the same rows were six quick skips.
        val alone = orphans.map { it.copy(continuesListenId = null) }
        assertTrue(SkipHabits(background() + alone, songs, now).artist("id-Band C", "Band C") >= SkipHabits.DISLIKE)
    }

    @Test
    fun `a skip past half is a keep`() {
        val c = (0 until 5).map { song("c$it", "Band C") }
        val rows = background() + c.map { skip(it.id, 0.5, playedMs = 140_000L) }
        assertEquals(0.0, SkipHabits(rows, songs, now).artist("id-Band C", "Band C"), 1e-9)
    }

    @Test
    fun `a liked song has no habit`() {
        val c = (0 until 5).map { song("c$it", "Band C") }
        val habits = SkipHabits(background() + c.map { skip(it.id, 0.5) }, songs, now)
        assertEquals(0.0, habits.of("Anything", "id-Band C", "Band C", liked = true), 1e-9)
    }

    @Test
    fun `an artist skipped at the listener's own rate gets nothing, for a light skipper and a heavy one`() {
        for (keptOutOfTen in listOf(9, 2)) {
            songs.clear()
            val rows = (0 until 30).flatMap { a ->
                (0 until 10).map { k ->
                    val s = song("s$a-$k", "Artist $a")
                    if (k < keptOutOfTen) keep(s.id, 1.0 + k % 5) else skip(s.id, 1.0 + k % 5)
                }
            }
            val habits = SkipHabits(rows, songs, now)
            assertEquals("kept $keptOutOfTen in ten", 0.0, habits.artist("id-Artist 3", "Artist 3"), 0.05)
        }
    }

    @Test
    fun `another version of a disliked song carries the song's penalty`() {
        // The band's other songs are kept, so the band itself is not disliked: one song of it is.
        val kept = (0 until 10).map { song("k$it", "The Band", title = "Other $it") }.map { keep(it.id, 0.5) }
        val versions = listOf("Paper Moon (Slowed)", "Paper Moon (Sped Up)", "Paper Moon", "Paper Moon (Remix)", "Paper Moon (Live)", "Paper Moon (Nightcore)")
            .mapIndexed { i, t -> song("v$i", "The Band", title = t) }.map { skip(it.id, 0.2) }
        val habits = SkipHabits(background() + kept + versions, songs, now)

        assertTrue(habits.song("Paper Moon (Acoustic)", "The Band") >= SkipHabits.DISLIKE)
        assertTrue(habits.of("Other 3", "id-The Band", "The Band", liked = false) < SkipHabits.DISLIKE)
    }

    @Test
    fun `an artist with no id is found by its name`() {
        val c = (0 until 5).map { song("c$it", "Band C", artistId = "generated-$it") }
        val habits = SkipHabits(background() + c.map { skip(it.id, 0.5) }, songs, now)
        assertTrue("the radio's copy of the artist has no id", habits.artist(null, "band c") >= SkipHabits.DISLIKE)
    }

    @Test
    fun `a burst of quick skips counts as one skip shared between its songs`() {
        val c = (0 until 5).map { song("c$it", "Band ${'C' + it}") }
        // Five nexts in a row inside one session, a few seconds each: scanning, not five verdicts.
        val burst = c.map { s -> skip(s.id, 0.5, playedMs = 3_000L, session = 77L) }
        val habits = SkipHabits(background() + burst, songs, now)
        for (s in c) assertTrue(habits.artist(s.artistId, s.artistName) < 0.3)
        // The same five spread over the day, each on its own, count in full.
        songs.clear()
        val spread = (0 until 6).map { song("d$it", "Band D") }.map { skip(it.id, 0.5, playedMs = 3_000L) }
        assertTrue(SkipHabits(background() + spread, songs, now).artist("id-Band D", "Band D") >= SkipHabits.DISLIKE)
    }
}
