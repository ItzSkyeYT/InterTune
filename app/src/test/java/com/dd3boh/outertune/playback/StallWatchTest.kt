/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.playback.StallWatch.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Silence at the speaker, a second at a time. */
class StallWatchTest {
    private fun tick(
        now: Long,
        bytes: Long = 0,
        song: String = "song",
        wantsToPlay: Boolean = true,
        buffering: Boolean = true,
        streamed: Boolean = true,
        resolving: Boolean = false,
        casting: Boolean = false,
        waitingForNetwork: Boolean = false,
        online: Boolean = true,
        allowed: Boolean = true,
        connectingMs: Long? = null,
    ) = StallWatch.Tick(song, wantsToPlay, buffering, streamed, resolving, casting, waitingForNetwork, online, bytes, now, allowed, connectingMs)

    /** Every action over [seconds] of ticks a second apart, starting at [from]. */
    private fun StallWatch.run(seconds: Int, from: Long = 0, each: (Long) -> StallWatch.Tick): List<Pair<Long, Action>> =
        (0..seconds).map { from + it * 1_000L }.map { now -> now to tick(each(now)) }.filter { it.second != Action.NOTHING }

    @Test
    fun `six seconds of silence cut the connection, twelve change the route, eighteen give up`() {
        val actions = StallWatch().run(30) { now -> tick(now) }
        assertEquals(listOf(6_000L to Action.CUT_CONNECTION, 12_000L to Action.CHANGE_ROUTE, 18_000L to Action.GIVE_UP), actions)
    }

    @Test
    fun `a slow stream that still comes in is never cut`() {
        val actions = StallWatch().run(60) { now -> tick(now, bytes = now / 2_000) }
        assertEquals(emptyList<Pair<Long, Action>>(), actions)
    }

    @Test
    fun `bytes after a cut start the count again`() {
        val watch = StallWatch()
        val first = watch.run(7) { now -> tick(now) }
        assertEquals(listOf(6_000L to Action.CUT_CONNECTION), first)
        val after = watch.run(11, from = 8_000) { now -> tick(now, bytes = if (now >= 8_000) 10 else 0) }
        assertEquals("six seconds after the last byte, the first step again", listOf(14_000L to Action.CUT_CONNECTION), after)
    }

    @Test
    fun `paused, casting, waiting for the network, resolving, a cached song or offline are never stalls`() {
        val quiet = listOf<(Long) -> StallWatch.Tick>(
            { tick(it, wantsToPlay = false) },
            { tick(it, buffering = false) },
            { tick(it, casting = true) },
            { tick(it, waitingForNetwork = true) },
            { tick(it, resolving = true) },
            { tick(it, streamed = false) },
            { tick(it, online = false) },
            { tick(it, allowed = false) },
        )
        for (each in quiet) assertEquals(emptyList<Pair<Long, Action>>(), StallWatch().run(30, each = each))
    }

    @Test
    fun `a new song starts the count again`() {
        val watch = StallWatch()
        assertEquals(listOf(6_000L to Action.CUT_CONNECTION), watch.run(10) { now -> tick(now) })
        assertEquals(listOf(17_000L to Action.CUT_CONNECTION), watch.run(10, from = 11_000) { now -> tick(now, song = "next") })
    }

    @Test
    fun `a watch started again after a pause or a quiet retry counts from then, not from the old silence`() {
        val watch = StallWatch()
        // Five silent seconds, then no ticks: the person paused, or the player stopped on an error.
        assertEquals(emptyList<Pair<Long, Action>>(), watch.run(5) { now -> tick(now) })
        // Forty seconds later it buffers and wants to play again, still with nothing coming in.
        val after = watch.run(20, from = 45_000) { now -> tick(now) }
        assertEquals(listOf(51_000L to Action.CUT_CONNECTION, 57_000L to Action.CHANGE_ROUTE, 63_000L to Action.GIVE_UP), after)
    }

    @Test
    fun `a new route's resolve in the middle of a stall does not start the count again`() {
        val actions = StallWatch().run(30) { now -> tick(now, resolving = now in 13_000L..15_000L) }
        assertEquals(listOf(6_000L to Action.CUT_CONNECTION, 12_000L to Action.CHANGE_ROUTE, 22_000L to Action.GIVE_UP), actions)
    }

    @Test
    fun `bytes from the new route after its resolve start the count again`() {
        val actions = StallWatch().run(30) { now -> tick(now, resolving = now in 13_000L..15_000L, bytes = if (now >= 16_000L) 5 else 0) }
        assertEquals(listOf(6_000L to Action.CUT_CONNECTION, 12_000L to Action.CHANGE_ROUTE, 22_000L to Action.CUT_CONNECTION, 28_000L to Action.CHANGE_ROUTE), actions)
    }

    @Test
    fun `a connection that takes eight seconds to bring its first byte is never cut`() {
        // DNS, TCP, TLS and the request, four or five round trips of 1.5 to 2 s on a link whose
        // uplink a photo backup fills: the first byte at 8 s, then the song comes in. The six
        // seconds of silence are counted from a connection's first byte, not from its opening.
        val actions = StallWatch().run(20) { now ->
            tick(now, bytes = if (now >= 8_000) now else 0, connectingMs = now.takeIf { it < 8_000 })
        }
        assertEquals(emptyList<Pair<Long, Action>>(), actions)
    }

    @Test
    fun `a connection with no first byte after fifteen seconds takes the next step, and the next connection gets as long`() {
        // Opened at 0 and never answering; the cut at 15 s makes media3 open another at once.
        val actions = StallWatch().run(40) { now -> tick(now, connectingMs = if (now <= 15_000) now else now - 15_000) }
        assertEquals(listOf(15_000L to Action.CUT_CONNECTION, 30_000L to Action.CHANGE_ROUTE), actions)
    }

    @Test
    fun `a connection still waiting a moment after its cut is not cut again`() {
        val actions = StallWatch().run(25) { now -> tick(now, connectingMs = now) }
        assertEquals(listOf(15_000L to Action.CUT_CONNECTION), actions)
    }

    @Test
    fun `silence after bytes is still cut at six seconds, and the connection made after it gets its fifteen`() {
        // Bytes until 5 s, then the connection goes silent and is cut at 11 s; the next one never answers.
        val actions = StallWatch().run(40) { now ->
            tick(now, bytes = minOf(now, 5_000), connectingMs = if (now > 11_000) now - 11_000 else null)
        }
        assertEquals(listOf(11_000L to Action.CUT_CONNECTION, 26_000L to Action.CHANGE_ROUTE), actions)
    }

    @Test
    fun `a connection waits for its first byte from its opening until that byte comes, song by song`() {
        val first = Any()
        val second = Any()
        StreamProgress.opening(first, "WAITINGSONG", at = 1_000_000_000L)
        assertEquals(2_000L, StreamProgress.waitingMs("WAITINGSONG", now = 3_000_000_000L))
        assertNull(StreamProgress.waitingMs("ANOTHERSONG", now = 3_000_000_000L))
        assertNull(StreamProgress.waitingMs(null, now = 3_000_000_000L))
        // The retry's connection while the cut one is still being let go: the newest is the one waited on.
        StreamProgress.opening(second, "WAITINGSONG", at = 2_500_000_000L)
        assertEquals(500L, StreamProgress.waitingMs("WAITINGSONG", now = 3_000_000_000L))
        StreamProgress.answered(second)
        StreamProgress.answered(first)
        assertNull("both brought a byte, failed or were closed", StreamProgress.waitingMs("WAITINGSONG", now = 3_000_000_000L))
    }
}
