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

    /** The host's word handing the room on, an expression to its closing parenthesis (body() would find the next brace). */
    private fun handOnWord(): String {
        val signature = "private fun handOnWord(handing: RoomHandOn.Handing) = TogetherMessage.Here("
        assertTrue("no $signature", signature in session)
        return session.substringAfter(signature).substringBefore("\n    )\n")
    }

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
        val everyone: (String) -> Boolean = { true }
        assertEquals(tiDie, RoomHandOn().apply { handed(mel, niko, 1L); handed(niko, tiDie, 2L) }.hostAfter(mel, dvd, everyone))
        // Relays do not keep order: the second word first.
        assertEquals(tiDie, RoomHandOn().apply { handed(niko, tiDie, 2L); handed(mel, niko, 1L) }.hostAfter(mel, dvd, everyone))
        // A word from someone who never hosted the room changes nothing.
        assertEquals(mel, RoomHandOn().apply { handed(dvd, tiDie, 1L) }.hostAfter(mel, niko, everyone))
        // Round in a circle (a host that leaves forgets its key, so it cannot happen): it stops all the same.
        RoomHandOn().apply { handed(mel, niko, 1L); handed(niko, mel, 2L) }.hostAfter(mel, dvd, everyone)
    }

    @Test
    fun `a hand-on counts once the phone it names says it hosts the room, and the host's newest word is the one`() {
        // Ti-Die watches Mel leave and name Niko.
        val hands = RoomHandOn().apply { handed(mel, niko, 1_000L) }
        var hosting = emptySet<String>()
        // Until Niko says it hosts the room, Mel is still the host: its next word, for another phone or the close, is taken.
        assertEquals(mel, hands.hostAfter(mel, tiDie) { it in hosting })
        hosting = setOf(niko)
        assertEquals(niko, hands.hostAfter(mel, tiDie) { it in hosting })
        // Niko itself takes it at once: its own Here then says it hosts the room, which is Mel's answer.
        assertEquals(niko, RoomHandOn().apply { handed(mel, niko, 1_000L) }.hostAfter(mel, niko) { false })
        // Niko never answered (gone, or its connection down), and Mel named Ti-Die next: Ti-Die takes it at once.
        hands.handed(mel, tiDie, 16_000L)
        assertEquals(tiDie, hands.hostAfter(mel, tiDie) { false })
        // A late copy of the first word changes nothing: the newest of each host's words counts.
        hands.handed(mel, niko, 1_000L)
        assertEquals(tiDie, hands.hostAfter(mel, tiDie) { false })
        // Two hand-ons, the middle host never heard saying it hosts: the last that says it does, the room's host now.
        val chain = RoomHandOn().apply { handed(mel, niko, 1L); handed(niko, dvd, 2L) }
        assertEquals(dvd, chain.hostAfter(mel, tiDie) { it == dvd })
        assertEquals(niko, chain.hostAfter(mel, tiDie) { it == niko })
    }

    @Test
    fun `the host that leaves passes over a phone that did not take the room, and its words get newer`() {
        val hands = RoomHandOn().apply {
            heard(niko, 0L)
            said(niko, true)
            heard(tiDie, 1_000L)
            said(tiDie, true)
        }
        val now = 30_000L
        val last = mapOf(niko to now, tiDie to now)
        assertEquals(niko, hands.heir(mel, null, last, now))
        val first = hands.handTo(niko, now, after = 5_000L)
        assertEquals(niko, hands.handing?.to)
        assertTrue(first.since > 5_000L && first.since >= now)
        // Waiting for Niko's word: not yet given up on it.
        assertFalse(hands.unanswered(now + RoomHandOn.ANSWER_MS - 1))
        assertTrue(hands.unanswered(now + RoomHandOn.ANSWER_MS))
        // Niko never answered: Ti-Die next, never Niko again, in a word newer than the first even with the clock set back.
        hands.passOver()
        assertNull(hands.handing)
        assertEquals(tiDie, hands.heir(mel, niko, last, now))
        val second = hands.handTo(tiDie, now - 60_000L, after = 5_000L)
        assertTrue(second.since > first.since)
        hands.passOver()
        // Nobody left to take it: the room closes (TogetherSession.handOnNext).
        assertNull(hands.heir(mel, null, last, now))
        // Nobody passed over makes the others look like older builds to the Leave.
        assertFalse(hands.olderOnly(mel, last, now))
        assertFalse(hands.unanswered(now + 60_000L))
    }

    @Test
    fun `a build before this one follows the new host and takes its close`() {
        // It reads the host's word as a word on who leads, newer than the host's last one: it follows Niko.
        val word = handOnWord()
        assertTrue(word, "name, owner = true, lead = handing.to, leadSince = handing.since," in word)
        assertTrue("val handing = handOn.handTo(to, System.currentTimeMillis(), after = leadGivenAt)" in body(session, "private fun handTo(to: String)"))
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
    fun `the host's word is said until the phone it names hosts the room, and only then is it out`() {
        val leave = body(session, "fun handOnAndLeave(to: String? = null): Boolean")
        assertTrue(leave, "val heir = heirKey() ?: return false" in leave)
        // The phone the Leave named, or nothing: the Leave then says who gets it now.
        assertTrue(leave, "if (to != null && to != heir) return false" in leave)
        assertTrue(leave, "handTo(heir)" in leave)
        assertFalse(leave, "stopTogether" in leave)
        assertTrue("takesRoom = true, host = handing.to," in handOnWord())
        // Every Here it says meanwhile is that word, every couple of seconds and at once to a newcomer or a relay back.
        val here = session.substringAfter("private fun sayHere() = send(").substringBefore("\n    )\n")
        assertTrue(here, "handOn.handing?.let(::handOnWord) ?: TogetherMessage.Here(" in here)
        val hand = body(session, "private fun handTo(to: String)")
        assertTrue(hand, "sayHere()" in hand && "delay(TogetherMessage.HEARTBEAT_MS)" in hand)
        assertTrue(hand, "handOn.unanswered(System.currentTimeMillis())" in hand && "handOnNext()" in hand)
        // Out once that phone's own Here says it hosts the room, with the line that names it.
        val event = body(session, "private fun onEvent(event: NostrEvent)")
        assertTrue(event, "if (message.owner && from == handOn.handing?.to) {\n                handedAway(from)" in event)
        val away = body(session, "private fun handedAway(to: String)")
        assertTrue(away, away.indexOf("R.string.together_hosts_now") in 0 until away.indexOf("service.stopTogether()"))
        // Not taken in time, or the phone left: the next one, and with nobody left the room closes for everyone.
        val next = body(session, "private fun handOnNext()")
        assertTrue(next, "handOn.passOver()" in next && "handTo(next)" in next)
        assertTrue(next, "R.string.together_nobody_took" in next && "leave()" in next)
        assertTrue("if (handOn.handing?.to == key) handOnNext()" in body(session, "private fun left(key: String)"))
        // Meanwhile the host's other powers wait: control stays as it is for the new host to take.
        for (signature in listOf("fun handOver(member: Member)", "fun takeBack()", "fun kick(member: Member)", "private fun watchLeader()")) {
            assertTrue(signature, "handOn.handing != null" in body(session, signature))
        }
        // Every phone of this build says it takes a room.
        assertTrue("dev = devProof, takesRoom = true," in here)
    }

    @Test
    fun `the others take the new host's word from then on, and the new host has all a host has`() {
        val event = body(session, "private fun onEvent(event: NostrEvent)")
        // Before the owner's word on who leads, which is the new host's to say from then on.
        val handOnAt = event.indexOf("if (message.host != null) handOnHeard(from, message.host, message.leadSince)")
        assertTrue(event, handOnAt in 0 until event.indexOf("leadWord.take(message.leadSince)"))
        // The word that hands the room on is not one on who leads: the room plays on as it was until the new host hosts.
        assertTrue(event, "if (from == ownerKey && message.host == null && message.lead != null && leadWord.take(message.leadSince))" in event)
        // The named phone's own word that it hosts the room, before or after the host's.
        assertTrue(event, "else if (message.owner && from != ownerKey) settleHost()" in event)
        assertTrue("handOn.hostAfter(was, me) { key -> said[key]?.second == true }" in body(session, "private fun settleHost()"))
        assertTrue(event, "handOn.said(from, message.takesRoom)" in event)
        assertTrue(event, "handOn.heard(from, now)" in event)
        // From what the relays kept too, as a close is.
        assertTrue("handOnHeard(from, message.host, message.leadSince)" in body(session, "private fun fromBefore(event: NostrEvent)"))
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
        // Everyone's button says Leave (with the flag); the host is asked how.
        assertTrue("Text(stringResource(if (room.owner && !Unreleased.ROOM_HAND_ON) R.string.together_close_room else R.string.together_leave))" in sheet)
        assertTrue("RoomLeaveDialog(\n            choice = status.leaveChoice," in sheet)
        assertTrue("if (room.handOnAndLeave(status.leaveChoice.heirKey)) confirmingClose = false" in sheet)
        // Handing on first, then closing, each saying what it does to the others.
        val handOn = dialog.indexOf("title = stringResource(R.string.together_hand_on, heir)")
        val close = dialog.indexOf("title = stringResource(R.string.together_close_room)")
        assertTrue(handOn in 0 until close)
        // The two ways are their names alone: the lines under them only repeated the buttons (his word of 24 Nov 2026).
        assertFalse("together_hand_on_text" in dialog)
        assertFalse("line = " in dialog)
        // Nobody to hand it to: the close asked as it always was, and why when the others are on older builds.
        assertTrue("R.string.together_close_room_title" in dialog)
        assertTrue("if (choice.olderOnly) {" in dialog)
        for (path in listOf("src/main/res/values/strings-ot.xml", "src/main/res/values-fr/strings-ot.xml")) {
            val strings = File(path).readText()
            for (name in listOf("together_leave_title", "together_hand_on", "together_hand_on_text", "together_close_room_older", "together_hosts_now", "together_host_yours", "together_walk_leave_host", "together_handing_on", "together_nobody_took")) {
                val line = strings.lines().firstOrNull { "name=\"$name\"" in it }
                assertTrue("$name missing from $path", line != null)
                assertFalse("$name in $path has a dash", "—" in line!! || "–" in line)
            }
        }
    }

    @Test
    fun `handing a room on is offered in every build from 0_13, and every build understands it`() {
        val flags = File("src/main/java/com/dd3boh/outertune/constants/Unreleased.kt").readText()
        // On for everyone in 0.13 (his word of 23 Nov 2026); ReleasedFlagsTest keeps it there.
        assertTrue("val ROOM_HAND_ON = true" in flags)
        // The switch is the offer alone: without it the host's Leave closes the room, asked first, as before, and the walk
        // says so.
        assertTrue("if (!Unreleased.ROOM_HAND_ON) return RoomLeaveChoice(heir = null, olderOnly = false)" in body(session, "fun leaveChoice(): RoomLeaveChoice"))
        assertTrue("if (!Unreleased.ROOM_HAND_ON) return false" in body(session, "fun handOnAndLeave(to: String? = null): Boolean"))
        val walk = File("src/main/java/com/dd3boh/outertune/ui/dialog/RoomWalk.kt").readText()
        assertTrue("LEAVE(R.string.together_walk_leave, if (Unreleased.ROOM_HAND_ON) R.string.together_walk_leave_host else R.string.together_walk_close)," in walk)
        // Taking a room handed on, and saying it does, are in every build: a phone on a release can be handed one.
        for (signature in listOf("private fun handOnHeard(", "private fun settleHost()", "private fun handedOn(", "private fun becomeHost()", "private fun left(")) {
            assertFalse(signature, "ROOM_HAND_ON" in body(session, signature))
        }
        assertFalse("ROOM_HAND_ON" in handOnWord())
        assertFalse("ROOM_HAND_ON" in session.substringAfter("private fun sayHere() = send(").substringBefore("\n    )\n"))
    }
}
