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

    private fun state(at: Long, playing: Boolean = true) =
        TogetherMessage.State("song", "", "", positionMs = 70_000, playing = playing, speed = 1.0, atMs = at)

    @Test
    fun `before the clocks are compared, clocks that seem to agree are taken at their word`() {
        // Sent at 1,000,000 by the host's clock, here 150 ms later by this one's: a believable trip.
        val guessed = Follow.guess(state(at = 1_000_000), heardAtMs = 1_000_150, nowMs = 1_000_650)
        assertEquals(Follow.target(state(at = 1_000_000), 1_000_650), guessed)
        assertEquals(70_650L, guessed)
    }

    @Test
    fun `a clock that is off does not send a guest that far off before the first pong`() {
        // This phone's clock 14 s behind the host's, as the emulator's was on 28 Oct 2026: the
        // word seems to arrive before it was sent. By when it came, behind by its trip alone.
        val behind = Follow.guess(state(at = 1_000_000), heardAtMs = 986_150, nowMs = 986_650)
        assertEquals(70_500L, behind)
        // And 14 s ahead: a trip of 14 s is not believed either.
        val ahead = Follow.guess(state(at = 1_000_000), heardAtMs = 1_014_150, nowMs = 1_014_650)
        assertEquals(70_500L, ahead)
        // A paused host is where it says, whatever the clocks.
        assertEquals(70_000L, Follow.guess(state(at = 1_000_000, playing = false), heardAtMs = 986_150, nowMs = 990_000))
    }

    @Test
    fun `before the first pong, the session places the leader by that guess`() {
        val at = session.substringAfter("private fun leaderAt(").substringBefore("\n\n")
        assertTrue(at.contains("if (gap.known) Follow.target(state, gap.hostNow(now)) else Follow.guess(state, lastStateAt, now)"))
        for (place in listOf("private fun follow()", "private fun toLeadersSong(", "private fun arrive(")) {
            val body = session.substringAfter(place).substringBefore("\n    }\n")
            assertFalse("$place takes the clocks as equal", body.contains("Follow.target(state, gap.hostNow(now))"))
            assertTrue("$place does not use leaderAt", body.contains("leaderAt(state, now)"))
        }
    }

    @Test
    fun `a guest says it is here as soon as a relay is up, so the leader can answer it`() {
        val start = session.substringAfter("fun start() {").substringBefore("\n    }\n")
        val connected = start.substringAfter("pool.connected.collect").substringBefore("scope.launch")
        assertTrue(connected.contains("if (n > before) sayHere()"))
    }

    @Test
    fun `a guest behind the leader past what has come in plays on until it has, then jumps into it`() {
        // Ready 3.5 s behind after a slow fetch, 1 s come in: a jump would ask for the song again.
        assertFalse(Follow.jumpNow(targetMs = 83_500, localMs = 80_000, bufferedMs = 81_000))
        // What has come in now reaches past the leader's spot: the jump costs nothing.
        assertTrue(Follow.jumpNow(targetMs = 85_000, localMs = 81_500, bufferedMs = 85_000 + Follow.LANDING_MS))
        // Not quite: the landing would be on the edge of what has come in.
        assertFalse(Follow.jumpNow(targetMs = 85_000, localMs = 81_500, bufferedMs = 85_200))
    }

    @Test
    fun `a leader far ahead, or behind, is jumped to at once`() {
        // The leader seeked a minute on: waiting for that much to come in would take too long.
        assertTrue(Follow.jumpNow(targetMs = 140_000, localMs = 80_000, bufferedMs = 90_000))
        assertTrue(Follow.jumpNow(targetMs = 80_000 + Follow.WAIT_FOR_DATA_MS + 1, localMs = 79_000, bufferedMs = 80_000))
        // Ahead of the leader: back to it, as ever.
        assertTrue(Follow.jumpNow(targetMs = 78_000, localMs = 80_000, bufferedMs = 81_000))
    }

    @Test
    fun `the session asks before every jump of the following`() {
        val follow = session.substringAfter("private fun follow()").substringBefore("\n    }\n")
        assertTrue(follow.contains("is Follow.Seek -> if (Follow.jumpNow(decision.positionMs, local, player.bufferedPosition))"))
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
        assertTrue(inQueue.contains("seekTo(index, (leaderAt(state, now)"))
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
