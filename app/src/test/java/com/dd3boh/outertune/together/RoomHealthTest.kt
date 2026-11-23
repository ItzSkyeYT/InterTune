/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.utils.ProblemLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A room in one line a minute for Report a problem: see RoomHealth. His dad's report of 26 Oct
 * 2026, from a room, said nothing about the room at all: the room's lines were not kept.
 */
class RoomHealthTest {
    @Test
    fun `a follower says how close it followed since the last line, then starts again`() {
        val h = RoomHealth()
        listOf(12L, -30L, 8L, 140L, -20L).forEach(h::drift)
        assertEquals(
            "joined, following; 1 other; 7 of 8 relays answering; round trip 673 ms; half the time within 20 ms of the leader, 140 ms at worst",
            h.line(owner = false, leading = false, others = 1, relaysAnswering = 7, relays = 8, roundTripMs = 673),
        )
        assertEquals(
            "joined, following, paused here; 1 other; 7 of 8 relays answering; round trip 673 ms",
            h.line(owner = false, leading = false, others = 1, relaysAnswering = 7, relays = 8, roundTripMs = 673, pausedHere = true),
        )
    }

    @Test
    fun `the leader says who it leads and through how many relays`() {
        assertEquals(
            "made here, leading; 2 others; 8 of 8 relays answering",
            RoomHealth().line(owner = true, leading = true, others = 2, relaysAnswering = 8, relays = 8, roundTripMs = null),
        )
        assertEquals(
            "joined, waiting for the leader; 0 others; 0 of 8 relays answering",
            RoomHealth().line(owner = false, leading = false, others = 0, relaysAnswering = 0, relays = 8, roundTripMs = null, waitingForLeader = true),
        )
    }

    @Test
    fun `a report keeps the room's lines but not its look every two seconds`() {
        assertTrue(ProblemLog.keep(ProblemLog.Line("20:01:02.000", 'I', "Together", "room: joined, following")))
        assertFalse(ProblemLog.keep(ProblemLog.Line("20:01:02.000", 'I', "TogetherSync", "drift 12 ms, speed 1.000")))
    }

    @Test
    fun `the room's lines never carry its code or a name someone chose`() {
        val body = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
        val logged = Regex("""Log\.[a-z]\((?:TAG|SYNC_TAG), "([^\n]*)"\)""").findAll(body).map { it.groupValues[1] }.toList()
        assertTrue("no room lines found", logged.size > 10)
        logged.forEach { line ->
            assertFalse("the room's code in: $line", "code.pretty" in line || "code.raw" in line)
            assertFalse("a chosen name in: $line", ".by}" in line || ".by'" in line || ".name}" in line || "message.name" in line)
        }
    }
}
