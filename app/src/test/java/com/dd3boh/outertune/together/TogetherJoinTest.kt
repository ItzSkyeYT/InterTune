/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A guest who joins a room gets the song sooner (his ask of 26 Oct 2026: the song took seconds to
 * come once the person who joined had to fetch it). The leader answers a newcomer at once, the
 * guest takes the leader's queue even when it comes before where the leader is, and the song is
 * fetched from the leader's spot, never first from 0:00.
 */
class TogetherJoinTest {

    private val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()

    @Test
    fun `a song loading from its start goes to the leader's spot at once`() {
        assertEquals(60_000L, Follow.arrival(targetMs = 60_000, positionMs = 0, durationMs = null))
        assertEquals(60_000L, Follow.arrival(targetMs = 60_000, positionMs = 0, durationMs = 213_000))
        assertEquals(30_000L, Follow.arrival(targetMs = 30_000, positionMs = 29_000, durationMs = 213_000))
    }

    @Test
    fun `a song already loading at the leader's spot, or ahead of it, is left to load`() {
        assertNull(Follow.arrival(targetMs = 60_300, positionMs = 60_000, durationMs = 213_000))
        assertNull(Follow.arrival(targetMs = 60_000 + Follow.DRIFT_MS, positionMs = 60_000, durationMs = null))
        // Ahead: once it is ready, following brings it back, as for any gap.
        assertNull(Follow.arrival(targetMs = 59_000, positionMs = 60_000, durationMs = null))
    }

    @Test
    fun `never to the song's end or past it, where the leader is moving on`() {
        assertNull(Follow.arrival(targetMs = 213_000, positionMs = 0, durationMs = 213_000))
        assertNull(Follow.arrival(targetMs = 214_500, positionMs = 0, durationMs = 213_000))
    }

    @Test
    fun `the leader answers a newcomer with where it is and its queue at once`() {
        val newcomer = session.substringAfter("if (from !in heard && leading) {").substringBefore("}")
        assertTrue(newcomer.contains("sendState()"))
        assertTrue(newcomer.contains("sendQueue()"))
    }

    @Test
    fun `a queue that comes before where the leader is names the leader, as where it is does`() {
        val state = session.substringAfter("is TogetherMessage.State ->").substringBefore("is TogetherMessage.Queue ->")
        val queue = session.substringAfter("is TogetherMessage.Queue ->").substringBefore("is TogetherMessage.Propose ->")
        assertTrue(state.contains("if (leaderKey == null) lead(from)"))
        assertTrue(queue.contains("if (leaderKey == null) lead(from)"))
        assertTrue(queue.contains("roomQueue = message"))
    }

    @Test
    fun `the leader's song is asked for at the leader's spot, never first from its start`() {
        val switch = session.substringAfter("private fun toLeadersSong(").substringBefore("private fun matchQueue(")
        assertFalse(switch.contains("seekTo(index, 0)"))
        val inQueue = switch.substringAfter("indexOf(player, state.songId)?.let { index ->").substringBefore("return\n")
        assertTrue(inQueue.contains("seekTo(index, (Follow.target(state"))
        assertTrue("a second jump while it loads would throw the first load away", inQueue.contains("arriving = false"))
        // A new queue starts its song at 0:00: it is marked, and follow sends it on while it loads.
        assertTrue(switch.substringAfter("val queue = roomQueue?.songs").contains("arriving = true"))
        val follow = session.substringAfter("private fun follow()").substringBefore("private fun toLeadersSong(")
        assertTrue(follow.contains("if (arriving) arrive(player, state, now)"))
    }

    @Test
    fun `a jump of the room's own never shortens the longer window it gave a song change`() {
        val mine = session.substringAfter("private fun mine(").substringBefore("\n    }\n")
        assertTrue(mine.contains("maxOf(ownCommandUntil,"))
    }
}
