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
 * A host's End reaches only the guests whose app is on at that moment: relays do not keep it. A guest whose app was off
 * joined the room again at its next start, counted the host's absence from that start, and kept the room alive with
 * its own beat, so every later start brought it back for six more hours, with its radio off (review of 0.13, 18 Nov
 * 2026). Now a guest keeps the time it last heard the host, and the room ends six hours after it.
 */
class GuestDeadRoomTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val code = RoomCode.parse("K7QXM2PA9DTZ")!!
    private val key = "ab".repeat(32)
    private val minute = 60_000L
    private val hour = 60 * minute

    /** The service's roomAlive: at most once a minute, the time it is given, for this room. */
    private inner class Service(val memory: RoomMemory, var touchedAt: Long) {
        fun roomAlive(at: Long) {
            if (at - touchedAt < 60_000L) return
            touchedAt = at
            memory.touch(code, at)
        }
    }

    /** A session's ten-second beat from [from] for [forMs], the host heard at [heard] (0: never). */
    private fun beats(service: Service, owner: Boolean, from: Long, forMs: Long, heard: (Long) -> Long) {
        var now = from
        while (now < from + forMs) {
            HostAbsence.aliveAt(owner, now, heard(now))?.let(service::roomAlive)
            now += TogetherMessage.HERE_MS
        }
    }

    @Test
    fun `a guest keeps the time it last heard the host, never its own beat`() {
        assertEquals(5L, HostAbsence.aliveAt(owner = true, now = 5L, ownerHeardAt = 0L))
        assertNull(HostAbsence.aliveAt(owner = false, now = 5L, ownerHeardAt = 0L))
        assertEquals(40L, HostAbsence.aliveAt(owner = false, now = 50L, ownerHeardAt = 40L))
        // its last word, else when the guest came or the time the room kept
        assertEquals(70L, HostAbsence.lastSign(ownerHeardAt = 70L, since = 10L))
        assertEquals(10L, HostAbsence.lastSign(ownerHeardAt = 0L, since = 10L))
    }

    @Test
    fun `a room its host closed while the guest was off comes back only until six hours after the host's last word`() {
        val file = File(folder.root, RoomMemory.FILE)
        val lastWord = 10 * hour
        RoomMemory(file).save(code, owner = false, identity = key, now = lastWord)
        // the guest opens the app every two hours, half an hour each time, and never hears the host again
        for (start in listOf(lastWord + 1 * hour, lastWord + 3 * hour, lastWord + 5 * hour)) {
            val memory = RoomMemory(file)
            val saved = memory.load(start)
            assertNotNull("the room comes back at ${(start - lastWord) / hour} h", saved)
            assertEquals(lastWord, saved!!.aliveAt)
            val service = Service(memory, touchedAt = saved.aliveAt)
            beats(service, owner = false, from = start, forMs = 30 * minute) { 0L }
            assertEquals("the guest's own beat keeps nothing", lastWord, RoomMemory(file).load(start + 30 * minute)!!.aliveAt)
            // not over yet: its end is counted from the host's last word
            val sign = HostAbsence.lastSign(ownerHeardAt = 0L, since = saved.aliveAt)
            assertFalse(HostAbsence.gone(start + 30 * minute, sign))
        }
        // a session still on at six hours after the host's word ends there, not six hours after its own start
        assertTrue(HostAbsence.gone(lastWord + 6 * hour + 1, HostAbsence.lastSign(0L, lastWord)))
        // and the next start finds nothing
        assertNull(RoomMemory(file).load(lastWord + 6 * hour + minute))
    }

    @Test
    fun `a guest that hears its host keeps the room alive as of the host's word`() {
        val file = File(folder.root, RoomMemory.FILE)
        val memory = RoomMemory(file)
        memory.save(code, owner = false, identity = key, now = 0L)
        val service = Service(memory, touchedAt = 0L)
        // the host speaks every ten seconds for two hours, then its phone ends the app
        beats(service, owner = false, from = 0L, forMs = 3 * hour) { now -> minOf(now, 2 * hour) }
        val kept = RoomMemory(file).load(3 * hour)!!.aliveAt
        assertTrue("kept at the host's last word, $kept", kept in (2 * hour - minute)..(2 * hour))
        // the host's own room: alive while it is on
        val own = File(folder.root, "own.txt")
        RoomMemory(own).save(code, owner = true, identity = key, now = 0L)
        beats(Service(RoomMemory(own), touchedAt = 0L), owner = true, from = 0L, forMs = 3 * hour) { 0L }
        assertTrue(RoomMemory(own).load(3 * hour)!!.aliveAt >= 3 * hour - 2 * minute)
    }

    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
    private val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()

    @Test
    fun `the session and the service count from the host's last word`() {
        val here = session.substringAfter("fun start()").substringBefore("fun stop()")
        assertTrue("HostAbsence.aliveAt(owner, System.currentTimeMillis(), ownerHeardAt)?.let(service::roomAlive)" in here)
        assertFalse("service.roomAlive()" in session)
        assertTrue("private val hostSince = if (hostHeardAt > 0) hostHeardAt else startedAt" in session)
        val watch = session.substringAfter("private fun watchHost()").substringBefore("private fun sayHere()")
        // The end also goes into Report a problem's steps since 21 Nov 2026 (RoomJoinTest).
        val gone = watch.substringAfter("if (HostAbsence.gone(now, HostAbsence.lastSign(ownerHeardAt, hostSince))) {").substringBefore("\n        }")
        assertTrue(watch, "closed(R.string.together_host_gone)" in gone && "ReportTrail.Room.HOST_GONE" in gone)
        // the line that the host is away still waits for this session's first beats: a room that comes back while its
        // host is there does not say so for the moment before the host's next word
        assertTrue("HostAbsence.away(now, maxOf(ownerHeardAt, startedAt))" in watch)
        // the service keeps the time it is given, and a guest's room that comes back keeps its own
        val alive = service.substringAfter("fun roomAlive(at: Long)").substringBefore("fun startTogether(")
        assertTrue("scope.launch(Dispatchers.IO) { roomMemory.touch(room, at) }" in alive)
        val start = service.substringAfter("fun startTogether(").substringBefore("fun moveTogether(")
        assertTrue(start.indexOf("roomTouchedAt = hostHeardAt ?: System.currentTimeMillis()") in 0 until start.indexOf("roomMemory.save("))
        assertTrue("hostHeardAt = hostHeardAt ?: 0L" in start)
        val rejoin = service.substringAfter("private fun rejoinSavedRoom()").substringBefore("private var routedPlayer")
        assertTrue("hostHeardAt = saved.aliveAt.takeIf { !saved.owner }" in rejoin)
    }
}
