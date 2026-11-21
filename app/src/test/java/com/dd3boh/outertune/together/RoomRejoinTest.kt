/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.together

import com.dd3boh.outertune.ui.dialog.RoomOnScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A room lives in the music service, and the service stops by itself once the app is left with nothing playing. A host
 * with the song paused who went to another app to share the code came back to a start page: the room was gone, and
 * the sheet, still holding the stopped service for a moment, let a tap on Start make a room on it that nothing would
 * ever stop, which told guests it was the host (15 Nov 2026). Now the room is kept and joined again by the next
 * service, in the same role, the guests say the host is away meanwhile, and no room is made on a stopped service.
 * This reads the source, because a service and its relays cannot be had in a unit test.
 */
class RoomRejoinTest {

    private val service = File("src/main/java/com/dd3boh/outertune/playback/MusicService.kt").readText()
    private val session = File("src/main/java/com/dd3boh/outertune/together/TogetherSession.kt").readText()
    private val sheet = File("src/main/java/com/dd3boh/outertune/ui/dialog/TogetherDialog.kt").readText()

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
    fun `a room is kept as it starts and forgotten when it is over, not when the service stops`() {
        assertTrue("roomMemory.save(code, owner, session.identityHex," in body(service, "fun startTogether("))
        assertTrue("roomMemory.forget()" in body(service, "fun stopTogether()"))
        val paused = body(service, "private fun pauseTogether()")
        assertFalse("forget" in paused)
        assertTrue("together.value?.stop()" in paused)
        // the service's own end pauses the room, it does not end it
        val destroy = body(service, "override fun onDestroy()")
        assertTrue("pauseTogether()" in destroy)
        assertFalse(destroy.substringBefore("destroyed = true").contains("stopTogether()"))
        // a room closed or left is over: both go through stopTogether
        assertTrue("service.stopTogether()" in body(session, "fun leave()"))
        assertTrue("service.stopTogether()" in body(session, "private fun closed("))
    }

    @Test
    fun `the next service joins it again, in its role and with its key`() {
        assertTrue("rejoinSavedRoom()" in service.substringAfter("Could not listen for the sleep timer's buttons"))
        val rejoin = body(service, "private fun rejoinSavedRoom()")
        assertTrue("if (!togetherAvailable) return" in rejoin)
        assertTrue("roomMemory.load(System.currentTimeMillis())" in rejoin)
        assertTrue("startTogether(\n                    code, saved.owner, relays, name,\n                    identity = saved.identity.hexToBytes()," in rejoin)
        // nothing is asked of anyone, and a room already there is left alone
        assertTrue("if (together.value != null || destroyed) return@launch" in rejoin)
    }

    @Test
    fun `a room is alive as long as it is on, so six hours are counted from the last time it was`() {
        // a guest's room only as long as its host is heard: GuestDeadRoomTest
        assertTrue("?.let(service::roomAlive)" in session.substringAfter("fun start()").substringBefore("fun stop()"))
        assertTrue("roomMemory.touch(room, at)" in body(service, "fun roomAlive("))
        assertTrue("60_000L" in body(service, "fun roomAlive("))
    }

    @Test
    fun `no room is made on a service that has stopped, and one left on it stops itself`() {
        val start = body(service, "fun startTogether(")
        assertTrue(start.indexOf("if (destroyed)") in 0 until start.indexOf("TogetherSession("))
        assertTrue("return null" in start)
        val here = session.substringAfter("fun start()").substringBefore("fun stop()")
        assertTrue(Regex("""if \(service\.isDestroyed\) \{[\s\S]*?stop\(\)\s+break""").containsMatchIn(here))
        // and the stop leaves a released player alone
        val stop = body(session, "fun stop()")
        assertTrue(stop.indexOf("if (!service.isDestroyed)") in 0 until stop.indexOf("service.exoPlayer.removeListener(listener)"))
        assertTrue(stop.indexOf("service.exoPlayer.repeatMode = repeatBefore") in 0 until stop.indexOf("pool.stop()"))
    }

    @Test
    fun `another room in its place ends the first, unless the room only moves to a new code`() {
        assertTrue("if (moving) it.stop() else it.closeNow()" in body(service, "fun startTogether("))
        assertTrue("moving = true" in body(service, "fun moveTogether("))
        val close = body(session, "fun closeNow()")
        assertTrue(close.indexOf("TogetherMessage.End") in 0 until close.indexOf("stop()"))
        assertTrue("if (owner)" in close)
        assertFalse("send(" in close)      // not through the coroutine the stop would cancel
    }

    @Test
    fun `a host that comes back is the same owner to its guests`() {
        assertTrue("identity: ByteArray = Secp256k1.newSecretKey()," in session)
        assertTrue("private val secret = identity" in session)
        assertTrue("val identityHex: String get() = secret.toHex()" in session)
        assertTrue("identity: ByteArray? = null," in service)
    }

    @Test
    fun `a guest says the host is away, and is back to normal as it is heard`() {
        assertTrue("val hostAway: Boolean = false" in session)
        // looked at on every tick, as the follower's own look is
        val tick = session.substringAfter("if (!leading) follow()").substringBefore("withTimeoutOrNull(every) { wake.receive() }")
        assertTrue("watchHost()" in tick)
        val watch = body(session, "private fun watchHost()")
        assertTrue("if (owner) return" in watch)
        assertTrue("HostAbsence.away(now, maxOf(ownerHeardAt, startedAt))" in watch)
        assertTrue("(leaderKey == null || leaderKey == ownerKey)" in watch)
        assertTrue("closed(R.string.together_host_gone)" in watch)
        // every word from the owner counts, and a guest with no word yet counts from when it came; the room's end, for
        // a room that comes back, from the host's last word before it (GuestDeadRoomTest)
        assertTrue("if (from == ownerKey) ownerHeardAt = now" in session)
        assertTrue("HostAbsence.gone(now, HostAbsence.lastSign(ownerHeardAt, hostSince))" in watch)
    }

    @Test
    fun `the sheet goes on showing the room while it comes back, so it does not slide in again`() {
        // Ti-Die, #testers 20 Nov 2026: back from sharing the code, "the open animation plays each time".
        val shown = RoomOnScreen<String>()
        assertEquals("room", shown.of("room", comingBack = false))
        // The old service stopped, the new one not joined yet: the room it showed, not a start page.
        assertEquals("room", shown.of(null, comingBack = true))
        assertEquals("room again", shown.of("room again", comingBack = false))
        // Left or closed: the start page.
        assertNull(shown.of(null, comingBack = false))
        // And nothing comes back that had already gone.
        assertNull(shown.of(null, comingBack = true))

        val rejoin = body(service, "private fun rejoinSavedRoom()")
        assertTrue("togetherComing.value = true\n        scope.launch {\n            try {" in rejoin)
        assertTrue("} finally {\n                togetherComing.value = false\n            }" in rejoin)
        val top = body(sheet, "fun TogetherSheet(")
        // A new service's flows read fresh from its first frame, never its StateFlow's value in composition (lint).
        assertTrue("val live = key(service) { service.together.collectAsState().value }" in top)
        assertTrue("val coming = key(service) { service.togetherComing.collectAsState().value }" in top)
        assertTrue("val session = onScreen.of(live, comingBack = !playerConnection.isAlive || coming)" in top)
        assertFalse("togetherComing.value" in top)
        assertTrue("val ready = playerConnection.isAlive || session != null" in top)
    }

    @Test
    fun `the sheet shows the line in place of who has control and the waiting, and waits for a live service`() {
        val room = body(sheet, "private fun RoomPage(")
        assertTrue("val hostAway = !room.owner && status.hostAway" in room)
        assertTrue("R.string.together_host_away" in room)
        assertTrue("if (!hostAway && !status.leading && leaderName != null)" in room)
        assertTrue("if (!status.leading && !hostAway)" in room)
        val top = body(sheet, "fun TogetherSheet(")
        assertTrue("val ready = playerConnection.isAlive" in top)
        assertTrue("if (!ready) Box(" in top)
        assertTrue("if (!playerConnection.isAlive) return" in body(sheet, "fun open("))
        assertTrue("TextButton(enabled = playerConnection.isAlive" in body(sheet, "fun TogetherInviteDialog("))
    }

    @Test
    fun `the words are in English and French, and short`() {
        for (dir in listOf("values", "values-fr")) {
            val strings = File("src/main/res/$dir/strings-ot.xml").readText()
            assertTrue("$dir has no together_host_away", """name="together_host_away"""" in strings)
            assertTrue("$dir has no together_host_gone", """name="together_host_gone"""" in strings)
        }
        val english = File("src/main/res/values/strings-ot.xml").readText()
        assertTrue(""">The host is away<""" in english)
    }
}
