/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.playback.StandInMemory.Entry
import com.dd3boh.outertune.playback.StandInMemory.Memory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** What is kept of the songs played from another id, and for how long. */
class StandInMemoryTest {
    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_791_000_000_000L

    @Test
    fun `a stand-in is used for a month, and then the song's own id is asked again`() {
        val memory = StandInMemory.remember(Memory(), "OLDID000001", "NEWID000001", t0)

        assertEquals("NEWID000001", StandInMemory.known(memory, "OLDID000001", t0))
        assertEquals("NEWID000001", StandInMemory.known(memory, "OLDID000001", t0 + 29 * day))
        assertNull(StandInMemory.known(memory, "OLDID000001", t0 + 30 * day))
        assertNull(StandInMemory.known(memory, "ANOTHER0001", t0))
        // A clock set back does not make an entry from the future a fresh one.
        assertNull(StandInMemory.known(memory, "OLDID000001", t0 - 1))
    }

    @Test
    fun `a song found again takes its new stand-in and its new date`() {
        val first = StandInMemory.remember(Memory(), "OLDID000001", "NEWID000001", t0)
        val second = StandInMemory.remember(first, "OLDID000001", "NEWID000002", t0 + day)

        assertEquals(mapOf("OLDID000001" to Entry("NEWID000002", t0 + day)), second.of)
    }

    @Test
    fun `forgetting a song that was never remembered changes nothing`() {
        val memory = StandInMemory.remember(Memory(), "OLDID000001", "NEWID000001", t0)

        assertSame(memory, StandInMemory.forget(memory, "ANOTHER0001"))
        assertEquals(Memory(), StandInMemory.forget(memory, "OLDID000001"))
    }

    @Test
    fun `no more than the limit is kept, and the oldest go first`() {
        var memory = Memory()
        for (i in 0 until StandInMemory.MAX + 5) memory = StandInMemory.remember(memory, "OLD%07d".format(i), "NEW%07d".format(i), t0 + i)

        assertEquals(StandInMemory.MAX, memory.of.size)
        assertNull(memory.of["OLD0000004"])
        assertEquals(Entry("NEW0000005", t0 + 5), memory.of["OLD0000005"])
        assertEquals(Entry("NEW%07d".format(StandInMemory.MAX + 4), t0 + StandInMemory.MAX + 4), memory.of["OLD%07d".format(StandInMemory.MAX + 4)])
    }

    @Test
    fun `what is stored reads back as it was`() {
        var memory = StandInMemory.remember(Memory(), "OLDID-00_01", "NEWID-00_01", t0)
        memory = StandInMemory.remember(memory, "OLDID000002", "NEWID000002", t0 + day)

        assertEquals("OLDID-00_01=NEWID-00_01@$t0;OLDID000002=NEWID000002@${t0 + day}", StandInMemory.encode(memory))
        assertEquals(memory, StandInMemory.decode(StandInMemory.encode(memory)))
        assertEquals("", StandInMemory.encode(Memory()))
    }

    @Test
    fun `what does not read as two ids and a time is dropped, the rest kept`() {
        assertEquals(Memory(), StandInMemory.decode(null))
        assertEquals(Memory(), StandInMemory.decode(""))
        assertEquals(
            mapOf("OLDID000002" to Entry("NEWID000002", t0)),
            StandInMemory.decode("OLDID000001=NEWID000001;=NEWID000003@5;OLD ID=NEWID000004@5;OLDID000005=@5;OLDID000006=NEWID000006@soon;OLDID000002=NEWID000002@$t0").of,
        )
    }
}
