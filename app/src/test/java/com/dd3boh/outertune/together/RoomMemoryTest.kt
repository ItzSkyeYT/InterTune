/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The room a phone is in is kept in a file, so that it comes back by itself when the app does (15 Nov 2026): the same
 * role, and for a host the same key, so that the guests see the same owner and not a stranger. It is forgotten when the
 * person leaves, and after six hours away.
 */
class RoomMemoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val code = RoomCode.parse("K7QXM2PA9DTZ")!!
    private val key = "ab".repeat(32)
    private val hour = 60 * 60 * 1000L

    private fun memory(name: String = "room.txt") = RoomMemory(File(folder.root, name))

    @Test
    fun `a room is kept with its code, its role and its key`() {
        val kept = memory()
        kept.save(code, owner = true, identity = key, now = 1_000L)
        val saved = memory().load(now = 2_000L)           // another instance: what the next service reads
        assertNotNull(saved)
        assertEquals("K7QXM2PA9DTZ", saved!!.code)
        assertTrue(saved.owner)
        assertEquals(key, saved.identity)
        assertEquals(1_000L, saved.aliveAt)
    }

    @Test
    fun `a guest is kept as a guest`() {
        memory().save(code, owner = false, identity = key, now = 0L)
        assertFalse(memory().load(now = 1L)!!.owner)
    }

    @Test
    fun `nothing is kept until a room is`() {
        assertNull(memory().load(now = 0L))
    }

    @Test
    fun `leaving forgets it`() {
        val kept = memory()
        kept.save(code, owner = true, identity = key, now = 0L)
        kept.forget()
        assertNull(memory().load(now = 1L))
        kept.forget()                                       // nothing to forget is no trouble
    }

    @Test
    fun `a room that was alive within six hours comes back, one that was not is forgotten`() {
        val kept = memory()
        kept.save(code, owner = true, identity = key, now = 0L)
        assertNotNull(kept.load(now = 6 * hour))
        assertNull(kept.load(now = 6 * hour + 1))
        // and it stays forgotten: the file is gone
        assertNull(memory().load(now = 0L))
    }

    @Test
    fun `a room still on is alive, so six hours are counted from the last time it was`() {
        val kept = memory()
        kept.save(code, owner = true, identity = key, now = 0L)
        kept.touch(code, now = 5 * hour)
        val saved = memory().load(now = 10 * hour)
        assertNotNull(saved)
        assertEquals(5 * hour, saved!!.aliveAt)
        assertEquals("K7QXM2PA9DTZ", saved.code)
        assertTrue(saved.owner)
    }

    @Test
    fun `touching with nothing kept keeps nothing`() {
        val kept = memory()
        kept.touch(code, now = 1L)
        assertNull(kept.load(now = 2L))
    }

    /**
     * The service touches the file off the main thread now (performance review, 18 Nov 2026), so a touch can come after
     * the person left the room or joined another: it must not bring the old room back, nor make the new one older.
     */
    @Test
    fun `a touch for a room that is no longer the one kept changes nothing`() {
        val kept = memory()
        val other = RoomCode.parse("ABCDEFGHJKLM")!!
        kept.save(other, owner = false, identity = key, now = 7 * hour)
        kept.touch(code, now = 8 * hour)
        assertEquals(7 * hour, memory().load(now = 9 * hour)!!.aliveAt)
        // and after a leave, a late touch keeps nothing
        kept.forget()
        kept.touch(other, now = 10 * hour)
        assertNull(memory().load(now = 10 * hour))
        // every access to the file one at a time
        val source = File("src/main/java/com/dd3boh/outertune/together/RoomMemory.kt").readText()
        for (f in listOf("fun save(", "fun load(", "fun touch(", "fun forget(")) {
            assertTrue("$f is not synchronized", source.substringBefore(f).trimEnd().endsWith("@Synchronized"))
        }
    }

    @Test
    fun `a file that cannot be read is no room`() {
        val file = File(folder.root, "room.txt")
        for (damaged in listOf("", "junk", "code=K7QXM2PA9DTZ\nowner=maybe\nidentity=$key\naliveAt=1\n",
            "code=NOPE\nowner=true\nidentity=$key\naliveAt=1\n", "code=K7QXM2PA9DTZ\nowner=true\nidentity=abc\naliveAt=1\n",
            "code=K7QXM2PA9DTZ\nowner=true\nidentity=$key\naliveAt=soon\n")) {
            file.writeText(damaged)
            assertNull(damaged, RoomMemory(file).load(now = 2L))
        }
    }

    @Test
    fun `a new room replaces the old one`() {
        val kept = memory()
        kept.save(code, owner = true, identity = key, now = 0L)
        val other = RoomCode.parse("ABCDEFGHJKLM")!!
        kept.save(other, owner = false, identity = "cd".repeat(32), now = 5L)
        val saved = memory().load(now = 6L)!!
        assertEquals("ABCDEFGHJKLM", saved.code)
        assertFalse(saved.owner)
        assertEquals("cd".repeat(32), saved.identity)
    }

    @Test
    fun `the host is away after two beats and a half, and gone after six hours`() {
        assertFalse(HostAbsence.away(now = 25_000L, lastSign = 0L))
        assertTrue(HostAbsence.away(now = 25_001L, lastSign = 0L))
        // the host speaks every ten seconds: one missed beat is no absence
        assertFalse(HostAbsence.away(now = 2 * TogetherMessage.HERE_MS, lastSign = 0L))
        assertFalse(HostAbsence.gone(now = 6 * hour, lastSign = 0L))
        assertTrue(HostAbsence.gone(now = 6 * hour + 1, lastSign = 0L))
        // a host heard again is back at once
        assertFalse(HostAbsence.away(now = 100_000L, lastSign = 99_000L))
    }

    @Test
    fun `the host's own phone and its guests forget after the same time`() {
        assertEquals(RoomMemory.FORGET_AFTER_MS, HostAbsence.GONE_MS)
        assertEquals(6 * hour, RoomMemory.FORGET_AFTER_MS)
    }
}
