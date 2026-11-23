/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * A host can leave without closing its room (his find of 23 Nov 2026, hosting three testers: "there's no way to leave the
 * room without closing it"; Ti-Die the same evening: "the lobbies should be able to switch hosts when one leaves"). Who
 * gets the room, the host's last word read both ways, phones on older builds, and the session's wiring, read from the
 * source because a service and its relays cannot be had in a unit test.
 */
class RoomHandOnTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
    private val sheet = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
    private val dialog = File("src/main/java/com/dd3boh/outertune/ui/dialog/RoomLeaveDialog.kt").readText()

    private val mel = "aa".repeat(32)      // the host
    private val niko = "bb".repeat(32)
    private val tiDie = "cc".repeat(32)
    private val dvd = "dd".repeat(32)      // on a build from before this one

    /** The text of the function that starts with [signature]: from its opening brace to the one that closes it. */
    private fun body(text: String, signature: String): String {
        val from = text.indexOf(signature)
        assertTrue("no $signature", from >= 0)
        val open = text.indexOf('{', from)
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        return text.substring(open)
    }

    @Test
    fun `the room goes to whoever is in control, else to whoever has been there longest`() {
        val hands = RoomHandOn().apply {
            heard(dvd, 5_000L)
            heard(tiDie, 10_000L)
            heard(niko, 20_000L)
            said(dvd, false)
            said(tiDie, true)
            said(niko, true)
        }
        val last = mapOf(dvd to 99_500L, tiDie to 95_000L, niko to 99_000L)
        // Niko has control: Niko gets the room, and keeps the music going.
        assertEquals(niko, hands.heir(mel, leader = niko, lastHeard = last, now = 100_000L))
        // The host has it: whoever has been there longest of those who can take it (dvd came first, on an older build).
        assertEquals(tiDie, hands.heir(mel, leader = mel, lastHeard = last, now = 100_000L))
        assertEquals(tiDie, hands.heir(mel, leader = null, lastHeard = last, now = 100_000L))
        // dvd has it, on a build that cannot take the room: it goes to Ti-Die, and control with it.
        assertEquals(tiDie, hands.heir(mel, leader = dvd, lastHeard = last, now = 100_000L))
        // Someone who went quiet and came back has been there since they came back.
        hands.keep(setOf(dvd, niko))
        hands.heard(tiDie, 60_000L)
        hands.said(tiDie, true)
        assertEquals(niko, hands.heir(mel, leader = null, lastHeard = last, now = 100_000L))
    }

    @Test
    fun `nobody who has gone, nor anyone on an older build, is handed the room`() {
        val hands = RoomHandOn().apply {
            heard(niko, 0L)
            said(niko, true)
            heard(dvd, 0L)
            said(dvd, false)
        }
        val now = 100_000L
        // Niko not heard for longer than two of everyone's Here: gone, as far as a room can tell.
        assertNull(hands.heir(mel, niko, mapOf(niko to now - RoomHandOn.FRESH_MS - 1, dvd to now), now))
        assertEquals(niko, hands.heir(mel, niko, mapOf(niko to now - RoomHandOn.FRESH_MS, dvd to now), now))
        // Only dvd there, on a build that cannot take it: closing alone, and the host is told why.
        assertTrue(hands.olderOnly(mel, mapOf(niko to 0L, dvd to now), now))
        assertFalse(hands.olderOnly(mel, mapOf(niko to now, dvd to now), now))
        // Alone: nothing to hand on, and nothing to explain.
        assertNull(RoomHandOn().heir(mel, mel, emptyMap(), now))
        assertFalse(RoomHandOn().olderOnly(mel, emptyMap(), now))
        // Never the host itself.
        assertNull(RoomHandOn().apply { heard(mel, 0L); said(mel, true) }.heir(mel, mel, mapOf(mel to now), now))
    }

    @Test
    fun `the host's last word names the heir twice, and every build reads the part it knows`() {
        val word = TogetherMessage.Here("Mel", owner = true, lead = niko, leadSince = 1_732_400_000_000L, arranges = true, takesRoom = true, host = niko)
        val text = TogetherMessage.encode(word, sender = mel)
        assertEquals(word, TogetherMessage.decodeFrom(text, mel))
        // What a build before this one reads of it: its host's word that Niko leads.
        val o = Json.parseToJsonElement(text) as JsonObject
        assertEquals("here", o["t"]!!.jsonPrimitive.content)
        assertEquals("true", o["owner"]!!.jsonPrimitive.content)
        assertEquals(niko, o["lead"]!!.jsonPrimitive.content)
        // And what this one reads: Niko hosts the room now.
        assertEquals(niko, o["host"]!!.jsonPrimitive.content)
        // A phone of this build says it takes a room; one from before says nothing, and is read as one that does not.
        assertTrue("\"takes\":true" in TogetherMessage.encode(TogetherMessage.Here("Niko", owner = false, takesRoom = true)))
        val old = TogetherMessage.decode("""{"t":"here","name":"dvd","owner":false,"arranges":true}""") as TogetherMessage.Here
        assertFalse(old.takesRoom)
        assertNull(old.host)
        // Nothing but a key names the next host, and an everyday Here names none.
        val odd = TogetherMessage.decode("""{"t":"here","name":"x","owner":true,"lead":"$niko","host":"Niko"}""") as TogetherMessage.Here
        assertNull(odd.host)
        assertFalse("\"host\"" in TogetherMessage.encode(TogetherMessage.Here("Mel", owner = true, lead = mel, takesRoom = true)))
    }

    @Test
    fun `whose the room is follows the hand-ons in whatever order they come`() {
        assertEquals(tiDie, RoomHandOn().apply { handed(mel, niko); handed(niko, tiDie) }.hostAfter(mel))
        // Relays do not keep order: the second word first.
        assertEquals(tiDie, RoomHandOn().apply { handed(niko, tiDie); handed(mel, niko) }.hostAfter(mel))
        // A word from someone who never hosted the room changes nothing.
        assertEquals(mel, RoomHandOn().apply { handed(dvd, tiDie) }.hostAfter(mel))
        // Round in a circle (a host that leaves forgets its key, so it cannot happen): it stops all the same.
        RoomHandOn().apply { handed(mel, niko); handed(niko, mel) }.hostAfter(mel)
    }

    @Test
    fun `a build before this one follows the new host and takes its close`() {
        // It reads the host's last word as a word on who leads, newer than the host's last one: it follows Niko.
        val leave = body(session, "fun handOnAndLeave(): Boolean")
        assertTrue(leave, "lead = to, leadSince = maxOf(System.currentTimeMillis(), leadGivenAt + 1)," in leave)
        // Niko's Here says it hosts the room, so Niko's close is one the older build takes (RoomEnds).
        val ends = RoomEnds()
        assertFalse(ends.owner(mel))
        assertFalse(ends.owner(niko))
        assertTrue(ends.end(niko))
        assertTrue("TogetherMessage.End -> if (from == ownerKey || ends.end(from))" in session)
        assertTrue("if (message.owner && ends.owner(from)) {" in session)
    }

    @Test
    fun `a host that was handed the room gives control only to phones that take its word`() {
        val hands = RoomHandOn().apply {
            heard(niko, 0L)
            said(niko, true)
            heard(dvd, 0L)
            said(dvd, false)
        }
        // The one who made the room: every build takes its word.
        assertTrue(hands.canLead(dvd))
        hands.handedHere = true
        assertTrue(hands.canLead(niko))
        assertFalse(hands.canLead(dvd))
        // The sheet leaves it out, and the session would not do it.
        assertTrue("if (!member.leader && canGiveControl(member)) {" in body(sheet, "private fun People("))
        assertTrue("canGiveControl = room::canGiveControl," in sheet)
        assertTrue("!handOn.canLead(member.key)" in body(session, "fun handOver(member: Member)"))
        assertTrue("handOn.handedHere = true" in body(session, "private fun becomeHost()"))
    }

    @Test
    fun `the host's last word goes straight to the relays, then it is out of the room`() {
        val leave = body(session, "fun handOnAndLeave(): Boolean")
        assertTrue(leave, "takesRoom = true, host = to," in leave)
        assertTrue(leave, leave.indexOf("pool.publish(") in 0 until leave.indexOf("service.stopTogether()"))
        assertFalse(leave, "send(" in leave)       // not through the coroutine the stop would cancel
        assertTrue(leave, "R.string.together_hosts_now" in leave)
        assertTrue(leave, "val to = heirKey() ?: return false" in leave)
        // Every phone of this build says it takes a room.
        assertTrue("dev = devProof, takesRoom = true," in session.substringAfter("private fun sayHere() = send(").substringBefore("\n    )\n"))
    }

    @Test
    fun `the others take the new host's word from then on, and the new host has all a host has`() {
        val event = body(session, "private fun onEvent(event: NostrEvent)")
        // Before the owner's word on who leads, which is the new host's to say from then on.
        val handOnAt = event.indexOf("if (message.host != null) handOnHeard(from, message.host)")
        assertTrue(event, handOnAt in 0 until event.indexOf("leadWord.take(message.leadSince)"))
        assertTrue(event, "handOn.said(from, message.takesRoom)" in event)
        assertTrue(event, "handOn.heard(from, now)" in event)
        // From what the relays kept too, as a close is.
        assertTrue("handOnHeard(from, message.host)" in body(session, "private fun fromBefore(event: NostrEvent)"))
        val handed = body(session, "private fun handedOn(was: String, host: String)")
        for (line in listOf("ownerKey = host", "leadWord = LeadWord()", "sentOut += was", "heard.remove(was)", "news.now(host)", "if (host != leaderKey) lead(host)", "R.string.together_hosts_now", "becomeHost()")) {
            assertTrue(line, line in handed)
        }
        val become = body(session, "private fun becomeHost()")
        for (line in listOf("owner = true", "ownerKey = me", "if (!leading) lead(me)", "sayHere()", "service.roomHosted(code)", "R.string.together_host_yours", "prefs[TogetherEveryoneSeeksKey] == true", "prefs[TogetherEveryoneArrangesKey] == true")) {
            assertTrue(line, line in become)
        }
        // The sheet sees it change: a state, not a value fixed as the room was made.
        assertTrue("var owner by mutableStateOf(owner)\n        private set" in session)
    }

    @Test
    fun `a phone handed the room comes back to it as its host, with its key`() {
        val memory = RoomMemory(File(folder.root, "room.txt"))
        val code = RoomCode.parse("MNQ7RR8Z6XNE")!!
        memory.save(code, owner = false, identity = niko, now = 0L, lead = mel)
        memory.hosted(code)
        val saved = memory.load(now = 1L)!!
        assertTrue(saved.owner)
        assertEquals(niko, saved.identity)
        // Only the room kept: a late word for another room, or after leaving, changes nothing.
        memory.save(code, owner = false, identity = niko, now = 2L)
        memory.hosted(RoomCode.parse("K7QXM2PA9DTZ")!!)
        assertFalse(memory.load(now = 3L)!!.owner)
        memory.forget()
        memory.hosted(code)
        assertNull(memory.load(now = 4L))
        val source = File("src/main/java/com/dd3boh/outertune/together/RoomMemory.kt").readText()
        assertTrue(source.substringBefore("fun hosted(").trimEnd().endsWith("@Synchronized"))
        assertTrue("scope.launch(Dispatchers.IO) { roomMemory.hosted(code) }" in body(service, "fun roomHosted("))
    }

    @Test
    fun `the host's Leave offers both ways plainly, and closing alone when nobody can take the room`() {
        // Everyone's button says Leave; the host is asked how.
        assertTrue("Text(stringResource(R.string.together_leave))" in sheet)
        assertFalse("R.string.together_close_room else R.string.together_leave" in sheet)
        assertTrue("RoomLeaveDialog(\n            choice = room.leaveChoice()," in sheet)
        assertTrue("if (room.handOnAndLeave()) confirmingClose = false" in sheet)
        // Handing on first, then closing, each saying what it does to the others.
        val handOn = dialog.indexOf("title = stringResource(R.string.together_hand_on, heir)")
        val close = dialog.indexOf("title = stringResource(R.string.together_close_room)")
        assertTrue(handOn in 0 until close)
        assertTrue("line = stringResource(R.string.together_hand_on_text, heir)" in dialog)
        assertTrue("line = stringResource(R.string.together_close_room_text)" in dialog)
        // Nobody to hand it to: the close asked as it always was, and why when the others are on older builds.
        assertTrue("R.string.together_close_room_title" in dialog)
        assertTrue("if (choice.olderOnly) {" in dialog)
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            val strings = File(path).readText()
            for (name in listOf("together_leave_title", "together_hand_on", "together_hand_on_text", "together_close_room_older", "together_hosts_now", "together_host_yours")) {
                val line = strings.lines().firstOrNull { "name=\"$name\"" in it }
                assertTrue("$name missing from $path", line != null)
                assertFalse("$name in $path has a dash", "—" in line!! || "–" in line)
            }
            // The walk's stop on the host's Leave says both ways now.
            val walk = strings.lines().first { "name=\"together_walk_close\"" in it }
            assertFalse(walk, "This closes the room for everyone." in walk || "Ça ferme le salon pour tout le monde." in walk)
        }
    }
}
