/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Who is in a room (Niko's room, #testers 23 Nov 2026): Mel left it and stayed on Niko's list, "your ghost is in the room
 * ... but its only for you", while Ti-Die's leave showed. A phone not heard for a while came off the list only when
 * another word came in, and after the last guest left none did. Earlier the same evening Mel was on the list "before i
 * sent the link": he had joined with the code Niko posted in #testers at 20:57 and gone again, the same ghost.
 */
class RoomPresenceTest {

    private val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()

    private val mel = "aa".repeat(32)
    private val tiDie = "bb".repeat(32)

    /** The text of the function that starts with [signature]: from its opening brace to the one that closes it. */
    private fun body(signature: String): String {
        val from = session.indexOf(signature)
        assertTrue("no $signature", from >= 0)
        val open = session.indexOf('{', from)
        var depth = 0
        for (i in open until session.length) {
            when (session[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return session.substring(open, i + 1)
            }
        }
        return session.substring(open)
    }

    @Test
    fun `the last one to leave comes off the list though nobody else speaks`() {
        val heard = RoomPresence(timeoutMs = 45_000L)
        heard[mel] = 1_000L
        // Mel leaves at once: nothing more from anyone. Still there while it may be a pause...
        assertFalse(heard.dropQuiet(now = 46_000L))
        assertEquals(setOf(mel), heard.keys)
        // ...and gone at the look after, with no word from anyone to make it so.
        assertTrue(heard.dropQuiet(now = 46_001L))
        assertTrue(heard.isEmpty())
        assertFalse(heard.dropQuiet(now = 90_000L))
    }

    @Test
    fun `whoever still speaks stays, whoever does not goes`() {
        val heard = RoomPresence(timeoutMs = 45_000L)
        heard[mel] = 0L
        heard[tiDie] = 0L
        heard[mel] = 40_000L               // Mel's Here, every ten seconds
        assertTrue(heard.dropQuiet(now = 50_000L))
        assertEquals(setOf(mel), heard.keys)
    }

    @Test
    fun `a new room starts with nobody in it, and a word kept from before adds nobody`() {
        assertTrue(RoomPresence(timeoutMs = 45_000L).isEmpty())
        // Each room its own, made with the session: nothing kept from the room before, nor shared between rooms.
        assertTrue("    private val heard = RoomPresence(MEMBER_TIMEOUT_MS)\n" in session)
        assertTrue("private val said = mutableMapOf<String, Pair<String, Boolean>>()" in session)
        assertFalse("companion object" in session.substringBefore("private val heard = RoomPresence(MEMBER_TIMEOUT_MS)"))
        // A word a relay kept from before this phone came says nothing of who is there now (Incoming.live).
        val before = body("private fun fromBefore(event: NostrEvent)")
        for (map in listOf("heard", "said")) {
            assertFalse(before, "$map[" in before || "$map." in before)
        }
    }

    @Test
    fun `a room after another is a new session, and nothing of who was in the one before goes into it`() {
        val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
        val start = service.substringAfter("    fun startTogether(").substringBefore("\n    }\n")
        // Another code: the room before stops, and a session of its own is made for this one.
        assertTrue(start, "if (moving) it.stop() else it.closeNow()" in start)
        assertTrue(start, "TogetherSession(this, code, owner, relays, name" in start)
        // Nothing of who was in a room is handed to the next: no parameter of a session carries anyone.
        val params = session.substringAfter("class TogetherSession(").substringBefore(") {\n    data class Status(")
            .lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("*") && !it.startsWith("/") }
            .mapNotNull { Regex("""^(?:private val |val )?(\w+):\s*(.+?),?$""").find(it)?.groupValues }
        assertTrue(params.toString(), params.size >= 5)
        for ((_, name, type) in params) {
            assertFalse("$name: $type", name in setOf("heard", "said", "members", "people") || "Member" in type || "RoomPresence" in type)
        }
    }

    @Test
    fun `the room's look takes off whoever has gone, whether or not a word comes in`() {
        val look = session.substringAfter("castLook()\n").substringBefore("withTimeoutOrNull(every)")
        assertTrue(look, "watchMembers()" in look)
        val watch = body("private fun watchMembers()")
        assertTrue(watch, "heard.dropQuiet(System.currentTimeMillis())" in watch)
        assertTrue(watch, "said.keys.retainAll(heard.keys)" in watch)
        assertTrue(watch, "publish()" in watch)
        // And a word coming in still does it, by the same rule.
        assertTrue("heard.dropQuiet(now)" in body("private fun onEvent(event: NostrEvent)"))
        assertFalse("heard.entries.removeAll" in session)
    }
}
