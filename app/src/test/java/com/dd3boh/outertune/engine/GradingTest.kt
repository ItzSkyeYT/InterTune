/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradingTest {
    private val now = 1_789_135_200_000L
    private val hour = 3_600_000L
    private val songs = mapOf(
        "a" to SongRow("a", "Africa", "t", "Toto"),
        "a2" to SongRow("a2", "Africa (Live)", "t", "Toto"),
        "b" to SongRow("b", "Rosanna", "t", "Toto"),
    )
    private val groups = VersionGroups(songs.values)
    private fun imp(id: Long, song: String, visibleAt: Long, tappedAt: Long? = null) = ImpressionRow(id, song, 0, Lane.RELATED, DoubleArray(Features.COUNT), 0.1, visibleAt, tappedAt)
    private fun listen(song: String, startedAt: Long, playedMs: Long = 200_000, impressionId: Long? = null, depth: Int = 0, learn: Boolean = true, endReason: Int = EndReason.ENDED) =
        ListenRow(song, startedAt, startedAt + playedMs, playedMs, 200_000, endReason, PlayOrigin.QUICK_PICKS.code, depth, 1, 0, learn, impressionId = impressionId)

    @Test
    fun `a card played from the row takes its engagement at full weight`() {
        val g = Grading.grade(listOf(imp(1, "a", now - 2 * hour, tappedAt = now - 2 * hour + 1000)), listOf(listen("a", now - 2 * hour + 5000, playedMs = 90_000, impressionId = 1)), songs, groups, now)
        assertEquals(1, g.size); assertEquals(Outcome.PLAYED, g[0].outcome); assertEquals(0.5, g[0].y, 1e-9); assertEquals(1.0, g[0].u, 0.0)
    }

    @Test
    fun `a tapped card with no listen yet, or an open one, waits`() {
        assertTrue(Grading.grade(listOf(imp(1, "a", now - 3 * hour, tappedAt = now - 3 * hour)), emptyList(), songs, groups, now).isEmpty())
        val open = listen("a", now - 1000, impressionId = 1, endReason = EndReason.OPEN)
        assertTrue(Grading.grade(listOf(imp(1, "a", now - 30 * hour, tappedAt = now - 30 * hour)), listOf(open), songs, groups, now).isEmpty())
    }

    @Test
    fun `a tapped card whose listen never came is settled after a day, weighing nothing`() {
        val g = Grading.grade(listOf(imp(1, "a", now - 30 * hour, tappedAt = now - 30 * hour)), emptyList(), songs, groups, now).single()
        assertEquals(Outcome.LOST, g.outcome); assertEquals(0.0, g.u, 0.0)
    }

    @Test
    fun `a tap whose link was lost is graded by its song's play just after it`() {
        val tap = now - 30 * hour
        // No impressionId on the listen: the tap's moment did not reach the player.
        val g = Grading.grade(listOf(imp(1, "a", tap - 2000, tappedAt = tap)), listOf(listen("a2", tap + 1500, playedMs = 90_000).copy(id = 42)), songs, groups, now).single()
        assertEquals(Outcome.PLAYED, g.outcome); assertEquals(0.5, g.y, 1e-9); assertEquals(1.0, g.u, 0.0)
        // Recorded, so Forget last session can find the example by the play.
        assertEquals(42L, g.listenId)
        // Too late after the tap to be its play, and a play another card already claims, both stay out of it.
        val late = Grading.grade(listOf(imp(1, "a", tap - 2000, tappedAt = tap)), listOf(listen("a", tap + 10 * 60_000L)), songs, groups, now).single()
        assertEquals(Outcome.LOST, late.outcome)
        val claimed = Grading.grade(listOf(imp(1, "a", tap - 2000, tappedAt = tap)), listOf(listen("a", tap + 1000, impressionId = 9)), songs, groups, now).single()
        assertEquals(Outcome.LOST, claimed.outcome)
    }

    @Test
    fun `a version played elsewhere within the day is half a win, and only after the day`() {
        val seen = now - 30 * hour
        val played = listen("a2", seen + 3 * hour, playedMs = 200_000)
        val g = Grading.grade(listOf(imp(1, "a", seen)), listOf(played), songs, groups, now).single()
        assertEquals(Outcome.ELSEWHERE, g.outcome); assertEquals(0.5, g.y, 1e-9); assertEquals(0.5, g.u, 0.0)
        // Not yet a day old: nothing to say.
        assertTrue(Grading.grade(listOf(imp(2, "a", now - 3 * hour)), emptyList(), songs, groups, now).isEmpty())
    }

    @Test
    fun `seen and not played is an ignored card at a third of the weight`() {
        val g = Grading.grade(listOf(imp(1, "a", now - 30 * hour)), listOf(listen("b", now - 20 * hour)), songs, groups, now).single()
        assertEquals(Outcome.IGNORED, g.outcome); assertEquals(0.0, g.y, 0.0); assertEquals(0.3, g.u, 0.0)
    }

    @Test
    fun `a play down a radio does not count as elsewhere`() {
        val g = Grading.grade(listOf(imp(1, "a", now - 30 * hour)), listOf(listen("a", now - 20 * hour, depth = 2)), songs, groups, now).single()
        assertEquals(Outcome.IGNORED, g.outcome)
    }

    @Test
    fun `a listen that asked not to teach drops the impression`() {
        val g = Grading.grade(listOf(imp(1, "a", now - 2 * hour, tappedAt = now - 2 * hour)), listOf(listen("a", now - hour, impressionId = 1, learn = false)), songs, groups, now).single()
        assertEquals(Outcome.DROPPED, g.outcome); assertEquals(0.0, g.u, 0.0)
    }

    // A play that failed decides nothing

    @Test
    fun `a card whose play failed is settled at no weight, however much of it played`() {
        val tap = now - 30 * hour
        for (playedMs in listOf(10_000L, 120_000L, 200_000L)) {
            val died = listen("a", tap + 1000, playedMs = playedMs, impressionId = 1, endReason = EndReason.ERROR)
            val g = Grading.grade(listOf(imp(1, "a", tap, tappedAt = tap)), listOf(died), songs, groups, now).single()
            assertEquals(Outcome.DROPPED, g.outcome); assertEquals(0.0, g.y, 0.0); assertEquals(0.0, g.u, 0.0)
        }
        // Also when the tap's link was lost and the song's play just after it stands in.
        val unlinked = listen("a", tap + 1000, playedMs = 150_000, endReason = EndReason.ERROR)
        val g = Grading.grade(listOf(imp(1, "a", tap - 2000, tappedAt = tap)), listOf(unlinked), songs, groups, now).single()
        assertEquals(Outcome.DROPPED, g.outcome); assertEquals(0.0, g.u, 0.0)
    }

    @Test
    fun `a real skip and a real finish of a tapped card are graded as before`() {
        val tap = now - 2 * hour
        val skipped = listen("a", tap + 1000, playedMs = 90_000, impressionId = 1, endReason = EndReason.SKIPPED)
        val s = Grading.grade(listOf(imp(1, "a", tap, tappedAt = tap)), listOf(skipped), songs, groups, now).single()
        assertEquals(Outcome.PLAYED, s.outcome); assertEquals(0.5, s.y, 1e-9); assertEquals(1.0, s.u, 0.0)
        val finished = listen("a", tap + 1000, playedMs = 200_000, impressionId = 1, endReason = EndReason.ENDED)
        val f = Grading.grade(listOf(imp(1, "a", tap, tappedAt = tap)), listOf(finished), songs, groups, now).single()
        assertEquals(Outcome.PLAYED, f.outcome); assertEquals(1.0, f.y, 1e-9); assertEquals(1.0, f.u, 0.0)
        // A skip too early to say anything is a loss at full weight, as it always was.
        val glance = listen("a", tap + 1000, playedMs = 20_000, impressionId = 1, endReason = EndReason.SKIPPED)
        val k = Grading.grade(listOf(imp(1, "a", tap, tappedAt = tap)), listOf(glance), songs, groups, now).single()
        assertEquals(Outcome.PLAYED, k.outcome); assertEquals(0.0, k.y, 0.0); assertEquals(1.0, k.u, 0.0)
    }

    // A play carried on after it stopped or failed

    @Test
    fun `a card whose play failed and was resumed is graded by the whole play`() {
        val tap = now - 3 * hour
        val died = listen("a", tap + 1000, playedMs = 60_000, impressionId = 1, endReason = EndReason.ERROR).copy(id = 10)
        val finished = listen("a", tap + 20 * 60_000, playedMs = 140_000).copy(id = 11, continuesListenId = 10)
        val g = Grading.grade(listOf(imp(1, "a", tap, tappedAt = tap)), listOf(died, finished), songs, groups, now).single()
        assertEquals(Outcome.PLAYED, g.outcome); assertEquals(1.0, g.y, 1e-9); assertEquals(1.0, g.u, 0.0)
        assertEquals(10L, g.listenId)                      // the card's own row
        // Resumed and then skipped: 60 s and 30 s heard, one play of 90 s.
        val skipped = finished.copy(playedMs = 30_000, endedAt = finished.startedAt + 30_000, endReason = EndReason.SKIPPED)
        val k = Grading.grade(listOf(imp(1, "a", tap, tappedAt = tap)), listOf(died, skipped), songs, groups, now).single()
        assertEquals(Outcome.PLAYED, k.outcome); assertEquals(0.5, k.y, 1e-9); assertEquals(1.0, k.u, 0.0)
        // Resumed and still playing: the card waits for the whole play.
        val playing = finished.copy(endReason = EndReason.OPEN)
        assertTrue(Grading.grade(listOf(imp(1, "a", tap, tappedAt = tap)), listOf(died, playing), songs, groups, now).isEmpty())
        // Followed further: failed, resumed and stopped, resumed again and finished.
        val stopped = listen("a", tap + 20 * 60_000, playedMs = 40_000, endReason = EndReason.STOPPED).copy(id = 11, continuesListenId = 10)
        val rest = listen("a", tap + 40 * 60_000, playedMs = 100_000).copy(id = 12, continuesListenId = 11)
        val chain = Grading.grade(listOf(imp(1, "a", tap, tappedAt = tap)), listOf(rest, died, stopped), songs, groups, now).single()
        assertEquals(Outcome.PLAYED, chain.outcome); assertEquals(1.0, chain.y, 1e-9)
    }

    @Test
    fun `a card whose play failed waits for a resume, and is dropped once none came`() {
        val died = listen("a", now - 3 * hour, playedMs = 120_000, impressionId = 1, endReason = EndReason.ERROR).copy(id = 10)
        // Waiting is said outright, so the card can be kept out of what is learned meanwhile.
        val waiting = Grading.grade(listOf(imp(1, "a", now - 3 * hour, tappedAt = now - 3 * hour)), listOf(died), songs, groups, now).single()
        assertEquals(Outcome.WAITING, waiting.outcome); assertEquals(0.0, waiting.y, 0.0); assertEquals(0.0, waiting.u, 0.0)
        val tap = now - 30 * hour
        val longAgo = died.copy(startedAt = tap + 1000, endedAt = tap + 121_000)
        val g = Grading.grade(listOf(imp(1, "a", tap, tappedAt = tap)), listOf(longAgo), songs, groups, now).single()
        assertEquals(Outcome.DROPPED, g.outcome); assertEquals(0.0, g.y, 0.0); assertEquals(0.0, g.u, 0.0)
        // Resumed and failed again: the whole play ended in an error, so the same.
        val again = listen("a", tap + 3 * hour, playedMs = 50_000, endReason = EndReason.ERROR).copy(id = 11, continuesListenId = 10)
        val twice = Grading.grade(listOf(imp(1, "a", tap, tappedAt = tap)), listOf(longAgo, again), songs, groups, now).single()
        assertEquals(Outcome.DROPPED, twice.outcome); assertEquals(0.0, twice.u, 0.0)
        val recently = again.copy(startedAt = now - 2 * hour, endedAt = now - 2 * hour + 50_000)
        assertEquals(Outcome.WAITING, Grading.grade(listOf(imp(1, "a", tap, tappedAt = tap)), listOf(longAgo, recently), songs, groups, now).single().outcome)
    }

    @Test
    fun `a card whose play was stopped and resumed is graded by the whole play too`() {
        val tap = now - 3 * hour
        val stopped = listen("a", tap + 1000, playedMs = 60_000, impressionId = 1, endReason = EndReason.STOPPED).copy(id = 10)
        // Not carried on: what was heard, as before.
        val alone = Grading.grade(listOf(imp(1, "a", tap, tappedAt = tap)), listOf(stopped), songs, groups, now).single()
        assertEquals(Outcome.PLAYED, alone.outcome); assertEquals(0.2 / 0.7, alone.y, 1e-9)
        val finished = listen("a", tap + 20 * 60_000, playedMs = 140_000).copy(id = 11, continuesListenId = 10)
        val g = Grading.grade(listOf(imp(1, "a", tap, tappedAt = tap)), listOf(stopped, finished), songs, groups, now).single()
        assertEquals(Outcome.PLAYED, g.outcome); assertEquals(1.0, g.y, 1e-9); assertEquals(1.0, g.u, 0.0)
    }

    @Test
    fun `a skip, a finish and a glance are graded as before beside plays that were carried on`() {
        val tap = now - 2 * hour
        // Another play of the song, stopped and carried on, touches none of them.
        val other = listen("a", tap - 5 * hour, playedMs = 60_000, endReason = EndReason.STOPPED).copy(id = 20)
        val otherRest = listen("a", tap - 4 * hour, playedMs = 140_000).copy(id = 21, continuesListenId = 20)
        for ((played, reason, y) in listOf(Triple(90_000L, EndReason.SKIPPED, 0.5), Triple(200_000L, EndReason.ENDED, 1.0), Triple(20_000L, EndReason.SKIPPED, 0.0))) {
            val own = listen("a", tap + 1000, playedMs = played, impressionId = 1, endReason = reason).copy(id = 30)
            val g = Grading.grade(listOf(imp(1, "a", tap, tappedAt = tap)), listOf(other, otherRest, own), songs, groups, now).single()
            assertEquals(Outcome.PLAYED, g.outcome); assertEquals(y, g.y, 1e-9); assertEquals(1.0, g.u, 0.0); assertEquals(30L, g.listenId)
        }
    }

    @Test
    fun `a song that failed when played elsewhere is not a card ignored, nor a win`() {
        val seen = now - 30 * hour
        val died = listen("a2", seen + 3 * hour, playedMs = 200_000, endReason = EndReason.ERROR)
        val g = Grading.grade(listOf(imp(1, "a", seen)), listOf(died), songs, groups, now).single()
        assertEquals(Outcome.DROPPED, g.outcome); assertEquals(0.0, g.y, 0.0); assertEquals(0.0, g.u, 0.0)
        // Played again after it failed, and heard: that play is what counts.
        val heard = listen("a", seen + 4 * hour, playedMs = 200_000, endReason = EndReason.ENDED)
        val again = Grading.grade(listOf(imp(1, "a", seen)), listOf(died, heard), songs, groups, now).single()
        assertEquals(Outcome.ELSEWHERE, again.outcome); assertEquals(0.5, again.y, 1e-9); assertEquals(0.5, again.u, 0.0)
        // And a real skip elsewhere is graded as before: half its engagement, at half the weight.
        val skipped = listen("a", seen + 4 * hour, playedMs = 90_000, endReason = EndReason.SKIPPED)
        val k = Grading.grade(listOf(imp(1, "a", seen)), listOf(died, skipped), songs, groups, now).single()
        assertEquals(Outcome.ELSEWHERE, k.outcome); assertEquals(0.25, k.y, 1e-9); assertEquals(0.5, k.u, 0.0)
    }

    @Test
    fun `features round-trip through the stored text`() {
        val x = DoubleArray(Features.COUNT) { it / 10.0 }
        val text = x.joinToString(",") { String.format(java.util.Locale.ROOT, "%.4f", it) }
        assertEquals(x.toList(), Grading.parseFeatures(text)!!.toList())

        // A vector written before a feature existed is padded, not thrown away. Refusing it would
        // mean every stored impression dropped out of the learner the day a feature was added.
        val short = Grading.parseFeatures("1,2")!!
        assertEquals(Features.COUNT, short.size)
        assertEquals(listOf(1.0, 2.0), short.take(2))
        assertTrue(short.drop(2).all { it == 0.0 })

        // Wider than the model is not something this version can read, and nonsense is nonsense.
        assertEquals(null, Grading.parseFeatures((0..Features.COUNT).joinToString(",")))
        assertEquals(null, Grading.parseFeatures(""))
        assertEquals(null, Grading.parseFeatures(null))
    }
}
