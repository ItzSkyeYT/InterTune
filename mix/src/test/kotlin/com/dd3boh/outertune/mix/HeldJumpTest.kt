/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The held end after a jump (8 Nov 2026): a plan worked out on an end held before the jump is
 * never taken by the one held after it; an end held with the next song's entry open keeps that
 * song's first frames waiting until the change says where it comes in, and lets it in from its
 * first frame once the change is given up; and a change joined part-way crosses from the end as
 * held into the change as rendered, never in one step.
 */
class HeldJumpTest {

    private val rate = 44_100
    private val tailSeconds = 10

    /** An end of [tailSeconds] taken in, and the next song waiting: the drain is next. */
    private fun heldEnd(entryOpen: Boolean, entry: Long = 0): Pair<HeldTail, ShortArray> {
        val held = HeldTail(2, rate, 60 * rate)
        val ring = ShortArray(20 * rate * 2)
        assertTrue(held.arm(ring, entry, entryOpen))
        val frames = tailSeconds * rate
        val input = ShortArray(frames * 2) { 1000 }
        val out = ShortArray(0)
        var taken = 0
        while (taken < frames) {
            held.process(input, taken, minOf(4096, frames - taken), out, 0, 0)
            taken += held.lastConsumed
        }
        held.endOfStream(true, true)
        assertEquals(HeldTail.State.DRAIN, held.state)
        return held to ring
    }

    private fun blend(entry: Long, crossIn: Int = 0) = MixPlan(
        style = Style.BLEND, reason = "a test", sampleRate = rate, channels = 2, tailFrames = tailSeconds * rate.toLong(),
        bStart = 8L * rate, aMap = TimeMap.IDENTITY, aFirstProcessed = 5L * rate, aGone = 9L * rate,
        overlapStart = 8.0 * rate, overlapEnd = 9.0 * rate, gainA = Curve.constant(1.0), gainB = Curve.constant(1.0),
        filtersA = emptyList(), filtersB = emptyList(), headroom = Headroom.NONE, incomingLoad = DoubleArray(0),
        attenuationA = 1.0, attenuationB = 1.0, shapedUntil = 9L * rate, lockFrom = 0, lockTo = 0, speed = 1.0, bars = 0,
        bEntry = entry, crossIn = crossIn,
    )

    private val outgoing = RenderedOutgoing(5L * rate, 4 * rate, 2, ShortArray(4 * rate * 2) { -1000 }, Headroom.NONE)

    private fun incoming(entry: Long) = RenderedIncoming(8L * rate, rate, 2, ShortArray(rate * 2), ShortArray(30 * rate * 2), 1.0, rate / 2L, entry)

    /** One call of the drain, giving out up to [frames]. */
    private fun drain(held: HeldTail, frames: Int): ShortArray {
        val out = ShortArray(frames * 2)
        val n = held.process(ShortArray(0), 0, 0, out, 0, frames)
        return out.copyOf(n * 2)
    }

    @Test
    fun `a plan worked out on an end held before a jump is not taken by the one held now`() {
        val (held, ring) = heldEnd(entryOpen = false)
        val before = ShortArray(ring.size)
        held.offer(blend(0), outgoing, incoming(0), ring = before)
        drain(held, 16)
        assertEquals("refused: planned on an end held before", held.note)
        held.offer(blend(0), outgoing, incoming(0), ring = ring)
        drain(held, 16)
        assertTrue(held.note, held.note.startsWith("planned: "))
        // A late plan for another end does not undo the one taken either, even one that would.
        held.offer(MixPlan.gapless(rate, 2, tailSeconds * rate.toLong(), 1.0, 1.0, "the end before"), outgoing, replaces = true, ring = before)
        drain(held, 16)
        assertEquals("refused: planned on an end held before", held.note)
        assertTrue(held.takenPlan != null)
    }

    @Test
    fun `an open entry keeps the next song waiting until the change says where it comes in`() {
        val (held, ring) = heldEnd(entryOpen = true)
        assertTrue(held.entryOpen)
        assertEquals(HeldTail.ENTRY_WAIT, held.entryToSkip())
        val entry = 12L * rate
        held.offer(blend(entry), outgoing, incoming(entry), ring = ring)
        drain(held, 16)
        assertTrue(held.note, held.note.startsWith("planned: "))
        assertEquals(entry, held.entryToSkip())
        assertTrue(!held.entryOpen)
        // Skipped to it, the plan plays on and the next song comes in on its planned frame.
        held.entrySkipped(true)
        assertEquals(entry, held.entry)
    }

    @Test
    fun `an open entry is said by the controller for its own end only, or given up for the first frame`() {
        val (held, ring) = heldEnd(entryOpen = true)
        held.decideEntry(5L * rate, ShortArray(ring.size))
        assertEquals("for another end: still open", HeldTail.ENTRY_WAIT, held.entryToSkip())
        held.decideEntry(5L * rate, ring)
        assertEquals(5L * rate, held.entryToSkip())
        // Then only a plan for that entry is taken.
        held.offer(blend(7L * rate), outgoing, incoming(7L * rate), ring = ring)
        drain(held, 16)
        assertTrue(held.note, held.note.startsWith("refused: planned for the next song from its frame"))

        val (given, _) = heldEnd(entryOpen = true)
        given.abort("a test")
        assertEquals("given up: the next song from its first frame", 0L, given.entryToSkip())

        // A gapless plan says the next song comes in from its first frame.
        val (plain, plainRing) = heldEnd(entryOpen = true)
        plain.offer(MixPlan.gapless(rate, 2, tailSeconds * rate.toLong(), 1.0, 1.0, "nothing fits"), outgoing, ring = plainRing)
        drain(plain, 16)
        assertEquals(0L, plain.entryToSkip())
    }

    @Test
    fun `joined part-way, the change is crossed into from the end as held, never in one step`() {
        val (held, ring) = heldEnd(entryOpen = false)
        val crossIn = 100
        held.offer(blend(0, crossIn), outgoing, incoming(0), ring = ring)
        val heard = ShortList()
        while (held.read < 5L * rate + 2 * crossIn) {
            val chunk = drain(held, 1024)
            heard.add(chunk, 0, chunk.size)
        }
        val at = (5 * rate)
        assertEquals("the end as held before", 1000, heard[(at - 1) * 2].toInt())
        var last = 1000
        for (k in 0 until crossIn) {
            val v = heard[(at + k) * 2].toInt()
            val x = (k + 1).toDouble() / crossIn
            assertEquals("frame $k of the cross", Math.round(1000 * (1 - x) - 1000 * x).toInt(), v)
            assertTrue("a step of ${last - v}", last - v <= 2000 / crossIn + 1)
            last = v
        }
        assertEquals("the change as rendered after", -1000, heard[(at + crossIn) * 2].toInt())
    }

    /** A ShortArray that grows. */
    private class ShortList {
        private var data = ShortArray(1 shl 16)
        var size = 0
            private set

        fun add(source: ShortArray, from: Int, count: Int) {
            while (size + count > data.size) data = data.copyOf(data.size * 2)
            System.arraycopy(source, from, data, size, count)
            size += count
        }

        operator fun get(i: Int): Short = data[i]
    }
}
