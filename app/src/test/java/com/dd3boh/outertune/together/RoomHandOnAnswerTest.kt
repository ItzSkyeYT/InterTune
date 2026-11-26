/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Handing a room on, as the review of 0.13 found it (26 Nov 2026): a guest that had just left could be handed the room,
 * and the host's word was said once, so a phone that missed it never hosted, and the others waited on it for good. Now a
 * guest says goodbye as it leaves, and the host says its word until the phone it names says it hosts the room, passing
 * over one that does not; the others take the new host once it says so. With the review's smaller finds around it. Read
 * from the source where a service and its relays cannot be had in a unit test (RoomHandOnTest has the rules themselves).
 */
class RoomHandOnAnswerTest {

    private val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
    private val sheet = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()
    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()

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
    fun `a guest that leaves says goodbye, and the others take it off the list at once`() {
        // A word of its own, which a build before this one drops unread, as any word it does not know.
        val bye = TogetherMessage.decode("""{"t":"bye"}""")
        assertNotNull(bye)
        assertEquals("""{"t":"bye"}""", TogetherMessage.encode(bye!!))
        assertEquals(bye, TogetherMessage.decodeFrom(TogetherMessage.encode(bye, sender = "ab".repeat(32)), "ab".repeat(32)))
        // Said by a guest's Leave, and as it goes to another room, before it stops: straight to the relays, as the close.
        val leave = body(session, "fun leave()")
        assertTrue(leave, leave.indexOf("sayBye()") in 0 until leave.indexOf("service.stopTogether()"))
        val close = body(session, "fun closeNow()")
        assertTrue(close, close.indexOf("sayBye()") in 0 until close.indexOf("\n        stop()"))
        val say = body(session, "private fun sayBye()")
        assertTrue(say, "pool.publish(" in say && "TogetherMessage.encode(TogetherMessage.Bye, sender = me)" in say)
        assertFalse(say, "send(" in say)
        // Heard, it is off the list before anything counts it as there, never handed the room, and never read again.
        val event = body(session, "private fun onEvent(event: NostrEvent)")
        val byeAt = event.indexOf("if (message == TogetherMessage.Bye) {\n            left(from)")
        assertTrue(event, byeAt in 0 until event.indexOf("heard[from] = now"))
        val left = body(session, "private fun left(key: String)")
        for (line in listOf("sentOut += key", "heard.remove(key)", "said.remove(key)", "handOn.keep(heard.keys)", "publish()")) {
            assertTrue(line, line in left)
        }
    }

    @Test
    fun `the host says its word until the phone it names hosts the room`() {
        // Every Here of a host handing the room on is the word handing it on, and one goes every couple of seconds.
        val here = session.substringAfter("private fun sayHere() = send(").substringBefore("\n    )\n")
        assertTrue(here, "handOn.handing?.let(::handOnWord) ?: TogetherMessage.Here(" in here)
        val hand = body(session, "private fun handTo(to: String)")
        assertTrue(hand, "while (handOn.handing === handing) {\n                sayHere()\n                delay(TogetherMessage.HEARTBEAT_MS)" in hand)
        // It is out of the room only once that phone says it hosts it.
        val leave = body(session, "fun handOnAndLeave(")
        assertFalse(leave, "stopTogether" in leave)
        assertTrue("service.stopTogether()" in body(session, "private fun handedAway(to: String)"))
        // Meanwhile it is on its way out: a service that stops never brings it back as the room's host, and another room
        // in its place does not close this one for those still waiting for the new host's word.
        assertTrue(hand, "service.roomLeaving(code)" in hand)
        assertTrue("if (together.value?.code == code) roomMemory.forget()" in body(service, "fun roomLeaving(code: RoomCode)"))
        val close = body(session, "fun closeNow()")
        assertTrue(close, close.indexOf("if (handOn.handing == null) {") in 0 until close.indexOf("TogetherMessage.End"))
    }

    @Test
    fun `the others take the new host once it says it hosts the room, and the old one's word until then`() {
        val settle = body(session, "private fun settleHost()")
        assertTrue(settle, "handOn.hostAfter(was, me) { key -> said[key]?.second == true }" in settle)
        assertTrue(settle, "if (host != was) handedOn(was, host)" in settle)
        // Whichever word comes first: the host's naming it, or its own Here saying it hosts.
        val event = body(session, "private fun onEvent(event: NostrEvent)")
        assertTrue(event, "if (message.host != null) handOnHeard(from, message.host, message.leadSince)\n            else if (message.owner && from != ownerKey) settleHost()" in event)
        assertTrue("settleHost()" in body(session, "private fun handOnHeard(from: String, to: String, since: Long?)"))
    }

    @Test
    fun `an heir that had paused or gone its own way hosts the room's music, not its own`() {
        val become = body(session, "private fun becomeHost()")
        assertTrue(become, become.indexOf("backWithRoom()") in 0 until become.indexOf("if (!leading) lead(me)"))
        val back = body(session, "private fun backWithRoom()")
        assertTrue(back, "if (leading || (!ownWay && !pausedHere)) return" in back)
        assertTrue(back, "player.seekTo(index, (leaderAt(room, System.currentTimeMillis()) + delayMs).coerceAtLeast(0))" in back)
        assertTrue(back, "player.playWhenReady = room.playing" in back)
        assertTrue(back, "mine(SONG_SWITCH_MS)" in back)
    }

    @Test
    fun `Play it plays the guest's song when the player in control holds nothing`() {
        val accept = body(session, "fun acceptProposal(withQueue: Boolean = false)")
        assertTrue(accept, "if (playedBefore) service.enqueueNext(songs.map { it.toMediaItem() })" in accept)
        assertTrue(accept, "else service.playQueue(ListQueue(title = songs.first().title, items = songs), origin = PlayOrigin.EXTERNAL)" in accept)
    }

    @Test
    fun `a guest back in step through a pause is no longer too slow`() {
        val follow = body(session, "private fun follow()")
        val paused = follow.substringAfter("if (!state.playing) {").substringBefore("\n            return\n")
        assertTrue(paused, "dataWait.reset()" in paused)
        assertTrue(paused, "tooSlow = false" in paused)
    }

    @Test
    fun `a guest whose copy of the leader's file ended first is not sent back past its end`() {
        // Where its copy ended, as it moved on by itself.
        val listener = session.substringAfter("override fun onPositionDiscontinuity(").substringBefore("override fun onEvents(")
        assertTrue(listener, "if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {" in listener)
        assertTrue(listener, "endedSong = oldPosition.mediaItem?.mediaId" in listener && "endedAtMs = oldPosition.positionMs" in listener)
        // The leader still in that song at or near that end: this phone stays on the next song and waits for its word.
        val to = body(session, "private fun toLeadersSong(")
        val guard = to.indexOf("leaderAt(state, now) + delayMs >= endedAtMs - Follow.END_GAP_MS")
        assertTrue(to, guard in 0 until to.indexOf("indexOf(player, state.songId)"))
        assertTrue(to, "state.songId == autoAdvancedFrom && state.songId == endedSong && endedAtMs > 0" in to)
    }

    @Test
    fun `the Leave says who gets the room as it is now, and hands it to that one`() {
        // Kept in the status at every look, so a phone that went quiet is no longer offered while the dialog is open.
        assertTrue("if (choice != _status.value.leaveChoice) _status.value = _status.value.copy(leaveChoice = choice)" in body(session, "private fun watchMembers()"))
        assertTrue("leaveChoice = leaveChoice()," in body(session, "private fun publish()"))
        assertTrue("choice = status.leaveChoice," in sheet)
        assertTrue("room.handOnAndLeave(status.leaveChoice.heirKey)" in sheet)
        // And while the room is handed on, the Leave says so and asks nothing more.
        assertTrue("if (handingTo != null) Text(stringResource(R.string.together_handing_on, handingTo))" in sheet)
        assertTrue("enabled = handingTo == null," in sheet)
    }
}
