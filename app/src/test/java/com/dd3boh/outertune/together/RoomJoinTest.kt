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
 * His two phones, 21 Nov 2026: a room made on the S25U, its QR scanned on the Pixel, the room closed on the S25U while the
 * Pixel showed its Join, then Join. The Pixel joined anyway and waited on a ghost of the host, for six hours: a relay had
 * kept the host's last words, the guest took them as live, and the host's close, handed on first, was passed over
 * because the guest did not know yet who had made the room. Now what a relay kept is history, a close is never passed
 * over, a guest that joins is in only once the room answers live, and nothing in a room takes the app down.
 */
class RoomJoinTest {

    private fun main(path: String) = File("src/main/java/com/dd3boh/outertune/$path").readText()
    private val session by lazy { main("together/TogetherSession.kt") }

    @Test
    fun `a close counts whichever comes first, the close or the word that its sender made the room`() {
        val closeFirst = RoomEnds()
        assertFalse("a stranger's close alone ends nothing", closeFirst.end("host"))
        assertTrue("its sender turns out to have made the room", closeFirst.owner("host"))

        val ownerFirst = RoomEnds()
        assertFalse(ownerFirst.owner("host"))
        assertTrue(ownerFirst.end("host"))

        // Only the one who made the room can close it.
        val other = RoomEnds()
        assertFalse(other.owner("host"))
        assertFalse(other.end("guest"))
        assertFalse(other.owner("someone else"))
    }

    @Test
    fun `a guest gives up only after the wait with a relay up, never for want of a network`() {
        assertEquals(15_000L, JoinCheck.WAIT_MS)
        assertFalse(JoinCheck.nobody(now = 10_000, lookingSince = 0, relays = 3))
        assertTrue(JoinCheck.nobody(now = 15_001, lookingSince = 0, relays = 3))
        assertFalse("no relay up: not an empty room", JoinCheck.nobody(now = 60_000, lookingSince = 0, relays = 0))
        // The session moves the start of the wait on while no relay answers.
        val relays = session.substringAfter("pool.connected.collect { n ->").substringBefore("\n            }\n")
        assertTrue(relays, "if (n == 0) lookingSince = System.currentTimeMillis()" in relays)
    }

    @Test
    fun `what a relay kept is history, and only says who made the room and whether it was closed`() {
        assertTrue(session.contains("scope.launch { pool.events.collect { onIncoming(it) } }"))
        val incoming = session.substringAfter("private fun onIncoming(incoming: Incoming) {").substringBefore("\n    }\n")
        assertTrue(incoming, "if (incoming.live) onEvent(incoming.event) else fromBefore(incoming.event)" in incoming)
        val before = session.substringAfter("private fun fromBefore(event: NostrEvent) {").substringBefore("\n    }\n")
        // It never makes anyone heard, the host answered or a song followed.
        for (live in listOf("heard[", "ownerHeardAt", "lastState", "answered(", "lead(", "ownerKey =")) {
            assertFalse("history touches $live", live in before)
        }
        assertTrue(before, "message is TogetherMessage.Here && message.owner -> ends.owner(from)" in before)
        assertTrue(before, "message == TogetherMessage.End -> from == ownerKey || ends.end(from)" in before)
    }

    @Test
    fun `a live close is never passed over, and a room whose maker closed it ends at once`() {
        val event = session.substringAfter("private fun onEvent(event: NostrEvent) {").substringBefore("\n    }\n\n")
        assertTrue(event, "TogetherMessage.End -> if (from == ownerKey || ends.end(from)) {" in event)
        assertTrue(event, "if (message.owner && ends.owner(from)) {" in event)
    }

    @Test
    fun `a guest that joins now is in once the host or the leader answers live, else it leaves with a line`() {
        assertTrue(session.contains("private val joiningNow = !owner && hostHeardAt == 0L"))
        val event = session.substringAfter("private fun onEvent(event: NostrEvent) {").substringBefore("\n    }\n\n")
        assertTrue(event, "if (answeredAt == 0L && !owner && (from == ownerKey || from == leaderKey)) answered(now)" in event)
        val watch = session.substringAfter("private fun watchHost() {").substringBefore("\n    }\n")
        assertTrue(watch, "if (joiningNow && answeredAt == 0L) {" in watch)
        assertTrue(watch, "JoinCheck.nobody(now, lookingSince, _status.value.relaysConnected)" in watch)
        val nobody = session.substringAfter("private fun nobodyAnswered(").substringBefore("\n    }\n")
        assertTrue(nobody, "closed(R.string.together_nobody)" in nobody)
    }

    @Test
    fun `nothing in a room takes the app down`() {
        assertTrue(session.contains("private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + failure)"))
        val failed = session.substringAfter("private fun failed(e: Throwable) {").substringBefore("\n    }\n")
        assertTrue(failed, "closed(R.string.together_failed)" in failed)
        assertTrue(failed, "ReportTrail.thisRun.room(ReportTrail.Room.FAILED)" in failed)
        val incoming = session.substringAfter("private fun onIncoming(incoming: Incoming) {").substringBefore("\n    }\n")
        assertTrue(incoming, "ReportTrail.thisRun.room(ReportTrail.Room.BAD_WORD)" in incoming)
    }

    @Test
    fun `a room's moments go into a report, and never its code or anyone's name`() {
        val trail = main("utils/ReportTrail.kt")
        val words = Regex("""^\s+[A-Z_]+\("([^"]*)"\),?$""", RegexOption.MULTILINE)
            .findAll(trail.substringAfter("enum class Room(val words: String) {").substringBefore("\n    }\n"))
            .map { it.groupValues[1] }.toList()
        assertEquals(15, words.size)
        words.forEach { assertFalse(it, '$' in it || '%' in it) }
        // The session's log lines that reports keep never carry the code.
        assertFalse(Regex("""Log\.[iwe]\(TAG, "[^"\n]*\$\{?code\b""").containsMatchIn(session))
        assertTrue(main("utils/ProblemLog.kt").contains("\"Together\",\n    )"))
    }

    @Test
    fun `the new lines have their words in English and French`() {
        for (xml in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml").map { File(it).readText() }) {
            for (name in listOf("together_nobody", "together_failed")) {
                val text = Regex("""<string name="$name">([^<]*)</string>""").find(xml)?.groupValues?.get(1)
                assertTrue("$name is missing", text != null)
                assertTrue("$name: two sentences at most", text!!.split(Regex("""[.!?](\s|$)""")).count { it.isNotBlank() } <= 2)
                assertFalse("$name: apostrophe not escaped", Regex("""(?<!\\)'""").containsMatchIn(text))
            }
        }
    }
}
