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
