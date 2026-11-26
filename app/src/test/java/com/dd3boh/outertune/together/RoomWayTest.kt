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
 * A room that comes back as a new session keeps how this phone was listening in it (26 Nov 2026). The move to a new code
 * when the host sends someone out (MusicService.moveTogether), and a room joined again after the service ended
 * (rejoinSavedRoom), each made a session that followed the room and played: a guest who had paused just for themselves
 * heard the room's song aloud with no tap, and one on their own music had the room's queue put in its place. Now paused
 * here stays paused here, and Play brings it back with the room; its own way stays its own, and Catch up brings it back.
 * After the service ended it comes back paused here unless this phone was with the room and playing (RoomWay). This
 * reads the source where a service and its relays would be needed.
 */
class RoomWayTest {
    private val src = File("src/main/java/com/dd3boh/outertune")
    private val session = File(src, "together/TogetherSession.kt").readText()
    private val service = File(src, "playback/MusicService.kt").readText()

    @get:Rule
    val folder = TemporaryFolder()

    /** The text of the function that starts with [signature]: from its opening brace to the one that closes it. */
    private fun body(text: String, signature: String): String {
        val from = text.indexOf(signature)
        assertTrue("no $signature", from >= 0)
        val open = text.indexOf('{', from)
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
            i++
        }
        error("no end to $signature")
    }

    @Test
    fun `a session that comes back starts paused here or muted, as this phone was, and its sheet says so`() {
        assertTrue("private val cameBackAs = way.takeIf { !leading }" in session)
        // Own way in a file from before 26 Nov 2026 comes back paused here: nothing aloud by itself.
        assertTrue("private var pausedHere = cameBackAs == RoomWay.PAUSED_HERE || cameBackAs == RoomWay.OWN_WAY" in session)
        val status = session.substringAfter("private val _status = MutableStateFlow(").substringBefore("val status: StateFlow<Status>")
        assertTrue(status, "pausedHere = pausedHere," in status)
    }

    @Test
    fun `the move to a new code carries the way over from the room it leaves`() {
        assertTrue("way = room.way()" in body(service, "fun moveTogether("))
        val start = body(service, "fun startTogether(")
        // Both ways of making the session take it.
        assertTrue(start, "identity, hostHeardAt = hostHeardAt ?: 0L, lead = lead, way = way)" in start)
        assertTrue(start, "hostHeardAt = hostHeardAt ?: 0L, carried = carried, way = way)" in start)
    }

    @Test
    fun `a room joined again after the service ended comes back by the rule for a restart`() {
        val rejoin = body(service, "private fun rejoinSavedRoom()")
        assertTrue(rejoin, "way = RoomWay.afterRestart(saved.way)," in rejoin)
    }

    @Test
    fun `the way is kept with the room as it starts, as it changes, and as the service ends`() {
        assertTrue("roomMemory.save(code, owner, session.identityHex, roomTouchedAt, lead, session.way())" in body(service, "fun startTogether("))
        assertTrue("roomMemory.way(code, way)" in body(service, "fun roomWay("))
        // The end of the service, on the spot: the room's next look never comes for a service that ends.
        val paused = body(service, "private fun pauseTogether()")
        assertTrue(paused, paused.indexOf("roomMemory.way(it.code, it.way())") in 0 until paused.indexOf("together.value?.stop()"))
        // As it changes, at the room's look.
        assertTrue("service.roomWay(code, now)" in body(session, "private fun keepWay()"))
        assertTrue("keepWay()" in session.substringAfter("castLook()\n").substringBefore("withTimeoutOrNull(every)"))
    }

    /** His word of 26 Nov 2026 (Spotify's Jam): nobody goes their own way in a room, and a guest's pause is a mute. */
    @Test
    fun `the way kept is paused here by the phone, muted, or with the room`() {
        val way = session.substringAfter("fun way(): RoomWay = RoomWay.of(").substringBefore("\n    )")
        assertTrue(way, "pausedHere = pausedHere," in way)
        assertTrue(way, "muted = muted," in way)
        assertTrue(way, "playing = service.exoPlayer.playWhenReady," in way)
        assertFalse(way, "ownWay" in way)
        // A room that comes back keeps the mute; one joined now starts with the sound on.
        assertTrue("muted = cameBackAs == RoomWay.MUTED" in session)
        assertTrue("private var ownWay = false" in session)
    }

    @Test
    fun `after the service ended only a phone that was with the room and playing goes on by itself`() {
        assertEquals(RoomWay.PLAYING, RoomWay.afterRestart(RoomWay.PLAYING))
        assertEquals(RoomWay.PAUSED_HERE, RoomWay.afterRestart(RoomWay.PAUSED_HERE))
        assertEquals(RoomWay.MUTED, RoomWay.afterRestart(RoomWay.MUTED))
        // Own way, in a file from before 26 Nov 2026: paused here, nothing aloud by itself.
        assertEquals(RoomWay.PAUSED_HERE, RoomWay.afterRestart(RoomWay.OWN_WAY))
        // With the room but not playing here: paused here, so that the room's next play is not heard aloud with no tap.
        assertEquals(RoomWay.PAUSED_HERE, RoomWay.afterRestart(RoomWay.FOLLOWING))
        // A file from before kept no way.
        assertEquals(RoomWay.PAUSED_HERE, RoomWay.afterRestart(null))
    }

    @Test
    fun `the way is read from how the session has it, paused here first`() {
        assertEquals(RoomWay.PAUSED_HERE, RoomWay.of(pausedHere = true, muted = true, playing = false))
        assertEquals(RoomWay.PAUSED_HERE, RoomWay.of(pausedHere = true, muted = false, playing = false))
        assertEquals(RoomWay.MUTED, RoomWay.of(pausedHere = false, muted = true, playing = true))
        assertEquals(RoomWay.PLAYING, RoomWay.of(pausedHere = false, muted = false, playing = true))
        assertEquals(RoomWay.FOLLOWING, RoomWay.of(pausedHere = false, muted = false, playing = false))
    }

    @Test
    fun `the way is kept in the room's file for the room kept only, and a file from before has none`() {
        val code = RoomCode.parse("K7QXM2PA9DTZ")!!
        val key = "ab".repeat(32)
        val file = File(folder.root, "room.txt")
        RoomMemory(file).save(code, owner = false, identity = key, now = 1_000L, way = RoomWay.PAUSED_HERE)
        assertEquals(RoomWay.PAUSED_HERE, RoomMemory(file).load(now = 2_000L)!!.way)
        RoomMemory(file).way(code, RoomWay.OWN_WAY)
        val saved = RoomMemory(file).load(now = 2_000L)!!
        assertEquals(RoomWay.OWN_WAY, saved.way)
        assertEquals(1_000L, saved.aliveAt)
        assertEquals(key, saved.identity)
        // A touch keeps it.
        RoomMemory(file).touch(code, now = 3_000L)
        assertEquals(RoomWay.OWN_WAY, RoomMemory(file).load(now = 3_000L)!!.way)
        // A late word for another room changes nothing, and after a leave nothing comes back.
        RoomMemory(file).way(RoomCode.parse("ABCDEFGHJKLM")!!, RoomWay.PLAYING)
        assertEquals(RoomWay.OWN_WAY, RoomMemory(file).load(now = 3_000L)!!.way)
        RoomMemory(file).forget()
        RoomMemory(file).way(code, RoomWay.PLAYING)
        assertNull(RoomMemory(file).load(now = 3_000L))
        // A file from before 26 Nov 2026, and one with a way this build does not know: the room, with no way.
        for (line in listOf("", "way=SOMETHING_NEW\n")) {
            file.writeText("code=K7QXM2PA9DTZ\nowner=false\nidentity=$key\naliveAt=1\n$line")
            val before = RoomMemory(file).load(now = 2L)
            assertNotNull(line, before)
            assertNull(line, before!!.way)
        }
    }
}
