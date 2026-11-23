/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Control of a room moves only when the owner moves it, or when the phone that has it is gone, and everyone is told
 * (testers, 22 Nov 2026: "dvd has control now", dvd "wait so do I just control it now or", and later the host "oh wait
 * im in control rn???? since when"). The parts that decide are tested here; the session's own wiring is read from the
 * source, because a service and its relays cannot be had in a unit test.
 */
class RoomControlTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
    private val sheet = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()

    private val host = "aa".repeat(32)
    private val dvd = "bb".repeat(32)
    private val niko = "cc".repeat(32)

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
    fun `each change of control is told once, and what a phone learns first is no change`() {
        val news = ControlNews()
        assertFalse(news.now(host))              // just joined: the host is in control, nothing to tell
        assertFalse(news.now(host))              // the host's next word says the same
        assertTrue(news.now(dvd))                // given to dvd: told
        assertFalse(news.now(dvd))               // once
        assertTrue(news.now(host))               // back to the host: told again
        // A room that comes back to the one it had says nothing; to another, it says so.
        assertFalse(ControlNews(before = dvd).now(dvd))
        assertTrue(ControlNews(before = dvd).now(host))
        assertEquals(niko, ControlNews(before = dvd).apply { now(niko) }.told)
    }

    @Test
    fun `an older word on who leads is never taken after a newer one`() {
        val word = LeadWord()
        assertTrue(word.take(1_000L))            // the host gave control to Niko at 1000
        assertTrue(word.take(5_000L))            // and to dvd at 5000
        assertFalse(word.take(1_000L))           // a relay hands on the word from 1000 late: Niko does not lead again
        assertTrue(word.take(5_000L))            // the host's next beat says the same as the newest
        assertTrue(word.take(null))              // a host on a build from before says no time: taken, as ever
    }

    @Test
    fun `the host takes control back only from a phone that has gone, and never while it hears nothing itself`() {
        val q = LeaderGone.QUIET_MS
        // dvd was given control at 0, last said anything at 10 s.
        assertFalse(LeaderGone.now(nowMs = 10_000L + q, givenAt = 0L, heardAt = 10_000L, hearingSince = 0L, relaysAnswering = 3))
        assertTrue(LeaderGone.now(nowMs = 10_001L + q, givenAt = 0L, heardAt = 10_000L, hearingSince = 0L, relaysAnswering = 3))
        // No relay answers here: the silence is the host's own.
        assertFalse(LeaderGone.now(nowMs = 600_000L, givenAt = 0L, heardAt = 10_000L, hearingSince = 0L, relaysAnswering = 0))
        // A relay answers again at 300 s, or the host's phone woke then: dvd gets the whole wait from there.
        assertFalse(LeaderGone.now(nowMs = 300_000L + q, givenAt = 0L, heardAt = 10_000L, hearingSince = 300_000L, relaysAnswering = 3))
        assertTrue(LeaderGone.now(nowMs = 300_001L + q, givenAt = 0L, heardAt = 10_000L, hearingSince = 300_000L, relaysAnswering = 3))
        // Given control to a phone that never says a word: taken back all the same, the room never waits on it.
        assertTrue(LeaderGone.now(nowMs = 50_001L + q, givenAt = 50_000L, heardAt = 0L, hearingSince = 0L, relaysAnswering = 1))
    }

    @Test
    fun `the host's word on who leads says since when, and builds from before still read it`() {
        val here = TogetherMessage.Here("Ti-Die", owner = true, lead = dvd, leadSince = 1_732_300_000_000L)
        assertEquals(here, TogetherMessage.decode(TogetherMessage.encode(here)))
        // From a build before 22 Nov 2026: no time, taken as ever.
        val old = TogetherMessage.decode("""{"t":"here","name":"Ti-Die","owner":true,"lead":"$dvd"}""") as TogetherMessage.Here
        assertEquals(dvd, old.lead)
        assertNull(old.leadSince)
        // A guest says no lead, and no time.
        assertFalse("\"since\"" in TogetherMessage.encode(TogetherMessage.Here("dvd", owner = false)))
        assertTrue("TogetherMessage.Here(name, owner, lead = if (owner) leaderKey else null, leadSince = if (owner) leadGivenAt else null)" in session)
        assertTrue("leadGivenAt = maxOf(System.currentTimeMillis(), leadGivenAt + 1)" in body(session, "private fun lead(key: String)"))
    }

    @Test
    fun `control moves only by the owner's newest word, and every change is told`() {
        val event = body(session, "private fun onEvent(event: NostrEvent)")
        assertTrue(event, "if (from == ownerKey && message.lead != null && leadWord.take(message.leadSince)) {" in event)
        assertTrue(event, "inControl(message.lead)" in event)
        // The owner's own moves are told on its phone too.
        assertTrue("inControl(member.key)" in body(session, "fun handOver(member: Member)"))
        assertTrue("inControl(me)" in body(session, "fun takeBack()"))
        // The texts: whoever gets it, the others, and the host whose guest had gone.
        val told = body(session, "private fun inControl(")
        for (text in listOf("R.string.together_control_theirs", "R.string.together_control_back", "R.string.together_control_yours")) {
            assertTrue(text, text in told)
        }
        assertTrue(told, "if (!news.now(key)) return" in told)
        // A move to a new code (someone sent out) leaves the owner in control: said before the move, by name.
        val move = event.substringAfter("is TogetherMessage.Move ->").substringBefore("TogetherMessage.End ->")
        assertTrue(move, move.indexOf("inControl(from)") in 0 until move.indexOf("service.moveTogether(next)"))
        assertTrue("R.string.together_removed_control" in body(session, "fun kick(member: Member)"))
    }

    @Test
    fun `the host takes control back in one place, and not because its own connection was down`() {
        // follow() no longer hands the lead back: a leader that plays nothing for a while is waited for.
        val follow = body(session, "private fun follow()")
        assertFalse(follow, "lead(me)" in follow)
        assertTrue(follow, "if (!owner) leaderKey = null" in follow)
        val watch = body(session, "private fun watchLeader()")
        assertTrue(watch, "LeaderGone.now(now, leadGivenAt, heard[key] ?: 0L, hearingSince, _status.value.relaysConnected)" in watch)
        assertTrue(watch, "if (now - lastLookAt > LeaderGone.ASLEEP_MS) hearingSince = now" in watch)
        assertTrue(watch, watch.indexOf("lead(me)") in 0 until watch.indexOf("inControl(me, wentQuiet = gone)"))
        // At every look, after the follower's own.
        assertTrue("watchHost()\n                watchLeader()" in session)
        // A relay that answers again after none did starts the wait again.
        val relays = session.substringAfter("pool.connected.collect { n ->").substringBefore("\n            }\n")
        assertTrue(relays, "if (n > 0 && before == 0) hearingSince = System.currentTimeMillis()" in relays)
        // The lead moves in these places only: the owner's word, the owner's own moves, and for a phone that knows no
        // leader yet (never the owner's), whoever plays for the room until the owner's word comes.
        val calls = Regex("""\blead\(([\w.]+)\)""").findAll(session).map { it.groupValues[1] }.toSet()
        assertEquals(setOf("me", "member.key", "message.lead", "from"), calls)
        assertEquals(session.split("lead(from)").size, session.split("if (leaderKey == null) lead(from)").size)
    }

    @Test
    fun `a phone that plays for the room without control is told at once who has it`() {
        val event = body(session, "private fun onEvent(event: NostrEvent)")
        assertTrue(event, "if (owner && message is TogetherMessage.State && from != leaderKey) answerStray(now)" in event)
        val stray = body(session, "private fun answerStray(now: Long)")
        assertTrue(stray, "if (now - strayAnsweredAt < TogetherMessage.HEARTBEAT_MS) return" in stray)
        assertTrue(stray, "sayHere()" in stray)
    }

    @Test
    fun `a host's room that comes back keeps control where it was`() {
        val memory = RoomMemory(File(folder.root, "room.txt"))
        val code = RoomCode.parse("K7QXM2PA9DTZ")!!
        memory.save(code, owner = true, identity = host, now = 0L)
        assertNull(memory.load(now = 1L)!!.lead)
        memory.lead(code, dvd)
        assertEquals(dvd, RoomMemory(File(folder.root, "room.txt")).load(now = 2L)!!.lead)
        // Kept through a touch, and for another room's word nothing changes.
        memory.touch(code, now = 3L)
        memory.lead(RoomCode.parse("ABCDEFGHJKLM")!!, niko)
        assertEquals(dvd, memory.load(now = 4L)!!.lead)
        // A file from before has no lead line, and comes back as ever; one that is not a key is no lead.
        File(folder.root, "old.txt").writeText("code=K7QXM2PA9DTZ\nowner=true\nidentity=$host\naliveAt=5\n")
        assertNull(RoomMemory(File(folder.root, "old.txt")).load(now = 6L)!!.lead)
        File(folder.root, "odd.txt").writeText("code=K7QXM2PA9DTZ\nowner=true\nidentity=$host\naliveAt=5\nlead=xyz\n")
        assertNull(RoomMemory(File(folder.root, "odd.txt")).load(now = 6L)!!.lead)
        val source = File("src/main/java/com/dd3boh/outertune/together/RoomMemory.kt").readText()
        assertTrue(source.substringBefore("fun lead(").trimEnd().endsWith("@Synchronized"))
        // The service gives it back to the session, the host's control included, and keeps it as it changes.
        assertTrue("lead = saved.lead," in body(service, "private fun rejoinSavedRoom()"))
        val start = body(service, "fun startTogether(")
        assertTrue(start, "hostHeardAt = hostHeardAt ?: 0L, lead = lead)" in start)
        assertTrue(start, "roomMemory.save(code, owner, session.identityHex, roomTouchedAt, lead)" in start)
        assertTrue("scope.launch(Dispatchers.IO) { roomMemory.lead(code, key) }" in body(service, "fun roomLead("))
        assertTrue("private var leaderKey: String? = if (owner) lead ?: me else null" in session)
        assertTrue("private val news = ControlNews(before = if (owner) leaderKey else lead)" in session)
    }

    @Test
    fun `giving control names who gets it, in both languages`() {
        val people = body(sheet, "private fun People(")
        assertTrue(people, "stringResource(R.string.together_give_control_to, member.name)" in people)
        val en = File("src/main/res/values/strings-ot.xml").readText()
        val fr = File("src/main/res/values-fr/strings-ot.xml").readText()
        for (name in listOf("together_give_control_to", "together_control_yours", "together_control_theirs", "together_control_back", "together_removed_control")) {
            assertTrue(name, "name=\"$name\"" in en)
            assertTrue(name, "name=\"$name\"" in fr)
        }
    }
}
