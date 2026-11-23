/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import com.dd3boh.outertune.engine.EngineListens
import com.dd3boh.outertune.engine.ListenRow
import com.dd3boh.outertune.engine.Signals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His word of 23 Nov 2026, in a room with a tester: "also for songs added to queue by users make it not count towards
 * your engine". In a listening room a song someone else put in the queue (a guest's when you host, the host's or another
 * guest's when you follow, a proposal someone made) is heard, goes into the history, Stats and the Recap, and teaches
 * nothing; a song you put in yourself teaches as ever.
 */
class WhoAddedTest {

    private val host = "a".repeat(64)
    private val guest = "b".repeat(64)
    private val other = "c".repeat(64)

    private fun song(id: String, by: String? = null) = TogetherMessage.Song(id, "Title $id", "Artist", "", by)

    // The rule: who put it in, this phone, and its part in the room.

    @Test
    fun `in control, a guest's song teaches nothing and this phone's own do`() {
        val added = WhoAdded(host)
        // the host's own radio, never through the room
        assertTrue(added.learns("radio1"))
        // a guest's Play next, put in by the host's phone for them (putSuggested)
        added.put("guest1", guest)
        assertFalse(added.learns("guest1"))
        // the host's queue goes out: its own songs are named by nobody, the guest's by the guest
        assertNull(added.sent("radio1"))
        assertEquals(guest, added.sent("guest1"))
        assertTrue(added.learns("radio1"))
    }

    @Test
    fun `following, the room's songs teach nothing and this phone's own Play next does`() {
        val added = WhoAdded(guest)
        // this guest's own Play next, sent to the room (suggestAll)
        added.mine(listOf("mine1"))
        // the room's queue comes into this phone's queue (matchQueue): the host's songs named by nobody, the guest's by it
        added.came(listOf(song("host1"), song("mine1", by = guest), song("other1", by = other)), sender = host)
        assertFalse("the host's song", added.learns("host1"))
        assertFalse("another guest's song", added.learns("other1"))
        assertTrue("its own song", added.learns("mine1"))
        // its own pick while on its own way never came through the room
        assertTrue(added.learns("ownWay1"))
    }

    @Test
    fun `an older host names nobody: its songs are not this phone's, and this phone's own Play next stays its own`() {
        val added = WhoAdded(guest)
        added.mine(listOf("mine1"))
        // a host on a build before this one sends no "by" for any song
        added.came(listOf(song("host1"), song("mine1")), sender = host)
        assertFalse(added.learns("host1"))
        assertTrue("what it put in itself, it knows", added.learns("mine1"))
    }

    @Test
    fun `the leader's song before its queue is the leader's, unless a word already said whose`() {
        val added = WhoAdded(guest)
        added.playing("host1", host)
        assertFalse(added.learns("host1"))
        // a song the queue named as another guest's stays theirs, and this phone's own stays its own
        added.came(listOf(song("other1", by = other)), sender = host)
        added.playing("other1", host)
        assertEquals(other, added.sent("other1"))
        added.mine(listOf("mine1"))
        added.playing("mine1", host)
        assertTrue(added.learns("mine1"))
    }

    @Test
    fun `a proposal someone made teaches the one in control nothing, and its own songs in it stay its own`() {
        val added = WhoAdded(host)
        assertNull("the host's own song, sent in its queue", added.sent("radio1"))
        // the guest proposes its song and the queue after it: one of the host's songs among them, named as the host's
        added.came(listOf(song("guest1"), song("radio1", by = host)), sender = guest)
        assertFalse(added.learns("guest1"))
        assertTrue(added.learns("radio1"))
    }

    @Test
    fun `a song this phone put in stays its own when someone else adds it too`() {
        val added = WhoAdded(host)
        assertNull(added.sent("radio1"))
        added.put("radio1", guest)
        assertTrue(added.learns("radio1"))
        // and a guest's song this phone then adds itself is its own too
        added.put("guest1", guest)
        added.mine(listOf("guest1"))
        assertTrue(added.learns("guest1"))
    }

    @Test
    fun `control handed over and back: the host's songs come back named as the host's, and teach it again`() {
        // The guest in control sends the host's songs, which it took from the host's queue, named as the host's.
        val guestPhone = WhoAdded(guest)
        guestPhone.came(listOf(song("radio1"), song("guest1", by = guest)), sender = host)
        assertEquals(host, guestPhone.sent("radio1"))
        assertNull(guestPhone.sent("guest1"))
        assertNull("the guest's own pick while in control", guestPhone.sent("guestPick"))
        // The host follows that queue: its own songs are its own, the guest's are the guest's.
        val hostPhone = WhoAdded(host)
        hostPhone.came(
            listOf(song("radio1", by = guestPhone.sent("radio1")), song("guest1", by = guestPhone.sent("guest1")), song("guestPick", by = guestPhone.sent("guestPick"))),
            sender = guest,
        )
        assertTrue(hostPhone.learns("radio1"))
        assertFalse(hostPhone.learns("guest1"))
        assertFalse(hostPhone.learns("guestPick"))
    }

    @Test
    fun `a room moved to a new code keeps whose each song was, under the new keys`() {
        val before = WhoAdded(host)
        assertNull(before.sent("radio1"))
        before.put("guest1", guest)
        val newKey = "d".repeat(64)
        val after = WhoAdded(newKey).also { it.takeOver(before.carried()) }
        assertTrue(after.learns("radio1"))
        assertFalse(after.learns("guest1"))
        assertNull(after.sent("radio1"))
        assertEquals(WhoAdded.SOMEONE, after.sent("guest1"))
    }

    @Test
    fun `only so many songs are remembered, the oldest word first`() {
        val added = WhoAdded(host, kept = 3)
        listOf("1", "2", "3", "4").forEach { added.put(it, guest) }
        assertTrue("forgotten: never through the room as far as it knows", added.learns("1"))
        assertFalse(added.learns("4"))
    }

    // The word that carries it.

    @Test
    fun `the room's queue names whoever added a song only when it is not the sender's own`() {
        val queue = TogetherMessage.Queue(listOf(song("x"), song("y", by = guest)), repeat = 0, shuffle = false)
        val text = TogetherMessage.encode(queue)
        val songs = Json.parseToJsonElement(text).jsonObject["songs"]!!.jsonArray.map { it.jsonObject }
        assertFalse("the sender's own song names nobody", "by" in songs[0])
        assertTrue("by" in songs[1])
        assertEquals(queue, TogetherMessage.decode(text))
        // and a proposal, as the queue of the guest that proposes
        val propose = TogetherMessage.Propose(listOf(song("p", by = host)), positionMs = 1_000, by = "Skye")
        assertEquals(propose, TogetherMessage.decode(TogetherMessage.encode(propose)))
    }

    @Test
    fun `a word from an older build names nobody, and a name that is no key is read as nobody`() {
        val old = """{"t":"queue","repeat":0,"shuffle":false,"songs":[{"id":"x","title":"t","artist":"a","thumb":""}]}"""
        assertNull((TogetherMessage.decode(old) as TogetherMessage.Queue).songs.single().by)
        for (bad in listOf("\"Skye\"", "\"${"A".repeat(64)}\"", "123", "{}", "null")) {
            val text = """{"t":"queue","repeat":0,"shuffle":false,"songs":[{"id":"x","title":"t","artist":"a","thumb":"","by":$bad}]}"""
            val queue = TogetherMessage.decode(text) as? TogetherMessage.Queue
            assertEquals("the queue itself is still taken: $bad", "x", queue?.songs?.single()?.id)
            assertNull(bad, queue?.songs?.single()?.by)
        }
        assertEquals(WhoAdded.SOMEONE, WhoAdded.named(WhoAdded.SOMEONE))
        assertEquals(guest, WhoAdded.named(guest))
    }

    // What the flag does: the engine leaves the listen out, as after Forget.

    @Test
    fun `a listen written not to learn from teaches the engine nothing`() {
        val heard = ListenRow(
            songId = "guest1", startedAt = 1_000_000, endedAt = 1_200_000, playedMs = 200_000, durationMs = 200_000,
            endReason = EndReason.ENDED, origin = PlayOrigin.QUEUE.code, autoplayDepth = 0, sessionId = 1_000_000, tzOffsetMin = 60,
        )
        assertTrue(Signals.value(heard, null) > 0.0)
        assertEquals(listOf(heard), EngineListens.picks(listOf(heard)))
        val theirs = heard.copy(learn = false)
        assertEquals(0.0, Signals.value(theirs, null), 0.0)
        assertTrue(EngineListens.picks(listOf(theirs)).isEmpty())
    }

    // The wiring: where the room puts songs in, it says whose; the listen and the queue's run ask.

    private val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

    private fun body(source: String, start: String) = source.substringAfter(start).substringBefore("\n    }\n")

    @Test
    fun `the room says whose each song is before it goes into this phone's queue`() {
        // a guest's songs, in control
        val put = body(session, "private fun putSuggested(")
        assertTrue(put.indexOf("songs.forEach { whoAdded.put(it.id, from) }") in 0 until put.indexOf("service.enqueue"))
        assertTrue("putSuggested(listOf(song), message.atEnd, message.by, from)" in session)
        // a proposal said yes to
        val accept = body(session, "fun acceptProposal(withQueue: Boolean = false)")
        assertTrue(accept.indexOf("whoAdded.came(proposed, proposal.from") in 0 until accept.indexOf("service.enqueueNext("))
        // following: the leader's queue and the leader's song
        val match = body(session, "private fun matchQueue()")
        assertTrue(match.indexOf("whoAdded.came(room.songs, leaderKey") in 0 until match.indexOf("service.playQueue("))
        val toLeader = body(session, "private fun toLeadersSong(")
        assertTrue(toLeader.indexOf("whoAdded.playing(state.songId, leaderKey") in 0 until toLeader.indexOf("player.seekTo(index"))
        assertTrue(toLeader.indexOf("queue?.let { whoAdded.came(it, leaderKey") in 0 until toLeader.indexOf("service.playQueue("))
        // this phone's own: its Play next and Add to queue, the song it proposes, and its queue as it sends it
        val suggest = body(session, "fun suggestAll(")
        assertTrue(suggest.indexOf("whoAdded.mine(songs.map { it.id })") in 0 until suggest.indexOf("send("))
        assertTrue("whoAdded.mine(listOf(songs.first().id))" in body(session, "fun propose()"))
        val own = session.substringAfter("private fun ownQueue()").substringBefore("private fun phoneSongAnswered(")
        assertTrue("by = whoAdded.sent(item.mediaId)," in own)
        assertTrue("songs += heard.song.copy(by = whoAdded.sent(heard.song.id))" in own)
    }

    @Test
    fun `the listen of someone else's song is written not to learn from, and this phone's own as ever`() {
        val note = body(service, "private fun noteTransition(")
        assertTrue(note.indexOf("val roomLearns = together.value?.learnsFrom(id)") in 0 until note.indexOf("val info = StartInfo("))
        assertTrue("learn = (q?.learn ?: true) && roomLearns != false," in note)
        // the listen row takes it from the play's start, as it takes the queue's own switch
        assertTrue("learn = info.learn, runId = info.runId" in body(service, "private fun openListen("))
        // the queue's run hears it and takes nothing from it
        assertTrue("learns = together.value?.learnsFrom(it.mediaId) != false," in service)
        // a like is kept, and answered by nothing
        val like = body(service, "fun toggleLike(")
        assertTrue(like.indexOf("if (together.value?.learnsFrom(song.id) == false) return@launch") in 0 until like.indexOf("heard.liked(song.id)"))
        // a swipe out says nothing against its artists
        assertTrue("heard.removedByHand(song.id, song.title, song.artists.map { it.name }, learns)" in body(service, "fun noteRemovedByHand("))
        // a skip of it rests nothing, as it teaches nothing
        assertTrue("learned = closed.play?.learn ?: true" in service)
        assertTrue("endReason == EndReason.SKIPPED && learned &&" in service)
        // a room moved to a new code keeps whose each song was
        assertTrue("carried = room.carriedSongs()" in body(service, "fun moveTogether("))
    }
}
