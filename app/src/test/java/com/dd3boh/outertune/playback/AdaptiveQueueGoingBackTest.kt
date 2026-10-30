/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Going back to an earlier song with the queue keeping itself up to date.
 *
 * The queue folded away every song more than four after the one playing, and the planning
 * treated the same songs as upcoming. So somebody who played eight songs and went back to the
 * first saw the seven they had just heard disappear, and the pass that followed could really drop
 * them: going back counted as a skip, which reaches furthest, and the songs were judged against
 * the one song before the first, which is itself.
 */
class AdaptiveQueueGoingBackTest {

    private val queue = ('A'..'P').map { it.toString() }

    private fun heardInOrder(vararg ids: String) = AdaptiveQueue.Heard().apply {
        ids.forEach { note(1L, it, "$it title") }
    }

    @Test
    fun `back at the first song, everything played since is settled`() {
        val heard = heardInOrder("A", "B", "C", "D", "E", "F", "G", "H", "A")

        val settled = AdaptiveQueue.settledThrough(0, queue, { it }, heard.reached)

        assertEquals("H, the furthest song reached", 7, settled)
        // The provisional tail starts after the songs seen waiting behind H, as it did while H played.
        assertEquals(12, AdaptiveQueue.tailStart(settled, queue.size))
    }

    @Test
    fun `moving forward, the tail starts where it always did`() {
        val heard = heardInOrder("A", "B", "C")

        val settled = AdaptiveQueue.settledThrough(2, queue, { it }, heard.reached)

        assertEquals(2, settled)
        assertEquals(7, AdaptiveQueue.tailStart(settled, queue.size))
    }

    @Test
    fun `nothing heard yet is the song playing`() {
        assertEquals(3, AdaptiveQueue.settledThrough(3, queue, { it }, emptySet()))
    }

    @Test
    fun `a song reached is found by its id after the queue moved under it`() {
        // C was played and then removed; D, E and F moved up one place each.
        val shorter = queue - "C"
        val heard = heardInOrder("A", "B", "C", "D", "E", "F", "A")

        assertEquals("F is now at 4", 4, AdaptiveQueue.settledThrough(0, shorter, { it }, heard.reached))
    }

    @Test
    fun `what was heard is read in the order it was heard, not by place`() {
        val heard = heardInOrder("A", "B", "C", "D", "E", "F", "A")

        assertEquals(listOf("A title", "F title", "E title", "D title", "C title"), heard.titles(5))
    }

    @Test
    fun `a new queue starts over`() {
        val heard = heardInOrder("A", "B", "C")
        heard.note(2L, "X", "X title")

        assertEquals(setOf("X"), heard.reached)
        assertEquals(listOf("X title"), heard.titles(5))
    }

    @Test
    fun `a long session keeps the newest`() {
        val heard = AdaptiveQueue.Heard(recentCap = 3, reachedCap = 4)
        listOf("A", "B", "C", "D", "E", "F").forEach { heard.note(1L, it, it) }

        assertEquals(listOf("F", "E", "D"), heard.titles(5))
        assertEquals(setOf("C", "D", "E", "F"), heard.reached)
        assertFalse("A" in heard.reached)
    }

    private val sources = File("src/main/java/com/dd3boh/outertune")

    @Test
    fun `the pass and the queue sheet both count from the furthest song reached`() {
        val service = File(sources, "playback/MusicService.kt").readText()
        val pass = service.substringAfter("private fun replanQueueTail(").substringBefore("\n    }\n")
        assertTrue(
            "the pass must start its tail after the furthest song reached",
            "AdaptiveQueue.tailStart(settled, count, locked)" in pass &&
                "AdaptiveQueue.settledThrough(current, ids, { it }, heard.reached)" in pass,
        )
        assertTrue("the pass must read what was heard, not the places before the song", "heard.titles(TagFit.WINDOW)" in pass)
        // The pass that also puts picks in counts its tail the same way.
        val snapshot = service.substringAfter("private fun adaptiveSnapshot(").substringBefore("\n    }\n")
        assertTrue(
            "the pass that puts picks in must start its tail after the furthest song reached too",
            "AdaptiveQueue.tailStart(settled, count, locked)" in snapshot &&
                "AdaptiveQueue.settledThrough(current, ids, { it }, heard.reached)" in snapshot,
        )
        assertFalse("current downTo" in snapshot)
        assertFalse("the places before the song are what was heard only while moving forward", "current downTo" in pass)
        assertTrue(
            "going back must not get the strong pass",
            "strong = reason == Player.MEDIA_ITEM_TRANSITION_REASON_SEEK && !transitionWentBack" in service,
        )

        assertTrue(
            "a queue just put in the player, new or brought back on a reopen, is not planned: nothing of it is heard yet",
            "if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) {\n            replanQueueTail(" in service,
        )

        val sheet = File(sources, "ui/player/Queue.kt").readText()
        assertTrue(
            "the queue sheet must show everything up to the furthest song reached",
            "AdaptiveQueue.settledThrough(currentWindowIndex, songs, { it.id }, reachedInQueue)" in sheet &&
                "songs.take((settled + 1 + ADAPTIVE_VISIBLE_AHEAD)" in sheet,
        )
    }
}
