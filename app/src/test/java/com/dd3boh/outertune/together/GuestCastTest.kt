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
import org.junit.Test
import java.io.File

/**
 * A guest that casts stops following the room while the cast lasts, one line in the sheet says so, and once the cast
 * ends it catches up by itself (his pick of 20 Nov 2026, "the small step" for 0.13). The room's corrections drove the
 * phone's own player, which a cast empties: each of the host's songs went to the TV from its start, and every one of
 * them replaced the guest's queue there (review of 0.13, 18 Nov 2026).
 */
class GuestCastTest {
    private val src = File("src/main/java/com/dd3boh/outertune")
    private val session = File(src, "together/TogetherSession.kt").readText()
    private val sheet = File(src, "ui/dialog/TogetherDialog.kt").readText()

    /** A guest's look as the session makes it, castLook and then follow: a correction is a look that moves the player. */
    private class Guest(var ownWay: Boolean = false, var leading: Boolean = false) {
        val cast = GuestCast()
        var corrections = 0
        var caughtUp = 0

        fun look(casting: Boolean) {
            when (cast.look(casting, following = !leading, ownWay = ownWay)) {
                GuestCast.Step.OWN_WAY -> ownWay = true
                GuestCast.Step.CATCH_UP -> {
                    caughtUp++
                    catchUp(casting)
                }
                GuestCast.Step.NONE -> Unit
            }
            if (!leading && !casting && !ownWay) corrections++
        }

        fun catchUp(casting: Boolean) {
            if (cast.catchUpNow(casting)) ownWay = false
        }
    }

    @Test
    fun `a guest that starts casting while it follows goes its own way, with the line`() {
        val guest = Guest()
        repeat(8) { guest.look(casting = false) }
        assertEquals(8, guest.corrections)
        guest.look(casting = true)
        assertTrue(guest.ownWay)
        assertTrue("the line: it catches up when the cast ends", guest.cast.catchUpAfter)
        // An hour of casting at four looks a second: not one correction.
        repeat(4 * 3600) { guest.look(casting = true) }
        assertEquals(8, guest.corrections)
        assertTrue(guest.cast.catchUpAfter)
    }

    @Test
    fun `when the cast ends it catches up by itself, once`() {
        val guest = Guest()
        guest.look(casting = false)
        repeat(100) { guest.look(casting = true) }
        guest.look(casting = false)
        assertEquals(1, guest.caughtUp)
        assertFalse(guest.ownWay)
        assertFalse("the line goes", guest.cast.catchUpAfter)
        repeat(100) { guest.look(casting = false) }
        assertEquals(1, guest.caughtUp)
        assertEquals("it follows again from the look the cast ends at", 1 + 1 + 100, guest.corrections)
        // In the session, the step is Catch up's own.
        val step = session.substringAfter("GuestCast.Step.CATCH_UP ->").substringBefore("GuestCast.Step.NONE")
        assertTrue("catchUp()" in step)
    }

    @Test
    fun `a guest on its own way before the cast stays on it after`() {
        val guest = Guest(ownWay = true)
        repeat(100) { guest.look(casting = true) }
        assertFalse("no line: it did not stop following for the cast", guest.cast.catchUpAfter)
        repeat(100) { guest.look(casting = false) }
        assertEquals(0, guest.caughtUp)
        assertTrue(guest.ownWay)
        assertEquals(0, guest.corrections)
    }

    @Test
    fun `Catch up asked for during a cast waits for its end`() {
        // The leader playing a song this guest had proposed before its cast catches it up: not onto the receiver.
        val guest = Guest(ownWay = true)
        guest.look(casting = true)
        guest.catchUp(casting = true)
        assertTrue(guest.ownWay)
        assertTrue("and the line says when", guest.cast.catchUpAfter)
        repeat(10) { guest.look(casting = true) }
        assertEquals(0, guest.corrections)
        guest.look(casting = false)
        assertEquals(1, guest.caughtUp)
        assertFalse(guest.ownWay)
        // With no cast it catches up at once, as ever.
        val other = GuestCast()
        assertTrue(other.catchUpNow(casting = false))
        assertFalse(other.catchUpAfter)
        val catchUp = session.substringAfter("fun catchUp() {").substringBefore("\n    }\n")
        assertTrue(catchUp.indexOf("guestCast.catchUpNow(casting = !leading && service.castOn)") in 0 until catchUp.indexOf("ownWay = false"))
    }

    @Test
    fun `back from a cast paused while the room plays, it is paused here and does not play by itself`() {
        // A receiver lost or idle comes back paused on purpose (CastSession, "Paused on this phone"): the catch-up keeps
        // the pause as a pause of the listener's own is kept, and play takes it to where the room is.
        assertEquals(true, PauseHere.after(pausedHere = false, playWhenReady = false, roomPlaying = true))
        assertNull("playing on the phone: straight back with the room", PauseHere.after(pausedHere = false, playWhenReady = true, roomPlaying = true))
        assertNull("the room paused too: paused with it", PauseHere.after(pausedHere = false, playWhenReady = false, roomPlaying = false))
        val step = session.substringAfter("GuestCast.Step.CATCH_UP ->").substringBefore("GuestCast.Step.NONE")
        val paused = step.indexOf("PauseHere.after(pausedHere = false, playWhenReady = service.exoPlayer.playWhenReady, roomPlaying = room.playing)")
        assertTrue(step.indexOf("catchUp()") in 0 until paused)
        assertTrue(step.indexOf("pausedHere = true") > paused)
    }

    @Test
    fun `the one who leads is left as it was`() {
        val cast = GuestCast()
        assertEquals(GuestCast.Step.NONE, cast.look(casting = true, following = false, ownWay = false))
        assertFalse(cast.catchUpAfter)
        assertEquals(GuestCast.Step.NONE, cast.look(casting = false, following = false, ownWay = false))
        // A guest given the lead during its cast has nothing to catch up with after it.
        val guest = Guest()
        guest.look(casting = true)
        guest.leading = true
        guest.ownWay = false
        guest.look(casting = true)
        assertFalse(guest.cast.catchUpAfter)
        guest.look(casting = false)
        assertEquals(0, guest.caughtUp)
        // What a leader sends still comes from the phone's own player, cast or not, and its listener and beat are as
        // they were: the host's side of a cast comes later.
        for (name in listOf("private fun sendState()", "private fun sendQueue()", "private fun ownQueue()")) {
            val body = session.substringAfter(name).substringBefore("\n    }\n")
            assertTrue(name, "val player = service.exoPlayer" in body)
            assertFalse(name, "castOn" in body || "casting" in body)
        }
        val events = session.substringAfter("override fun onEvents(").substringBefore("\n        }\n")
        assertTrue("if (!leading) return" in events)
        assertFalse("castOn" in events)
        val beat = session.substringAfter("// The leader's beat:").substringBefore("// A follower's pings:")
        assertTrue("if (leading) {" in beat)
        assertFalse("castOn" in beat || "casting" in beat)
    }

    @Test
    fun `the session looks at the cast first and moves no player while it lasts`() {
        // At every look, before following.
        assertTrue("castLook()\n                if (!leading) follow()" in session)
        assertTrue("val now = service.castOn" in session.substringAfter("private fun castLook()"))
        // follow() returns while casting before anything that moves a player, and after the check for a leader gone quiet.
        val follow = session.substringAfter("private fun follow()").substringBefore("\n    private fun ")
        val stop = follow.indexOf("if (casting) return")
        assertTrue(stop > follow.indexOf("HOST_TIMEOUT_MS"))
        for (move in listOf("toLeadersSong(", "seek(player", "matchQueue()", "speed(player", "player.playWhenReady = ", "player.prepare()")) {
            val at = follow.indexOf(move)
            assertTrue("$move comes after the cast's return", at == -1 || at > stop)
        }
        // The cast's own changes to the phone's player, as it takes the music away and gives it back, are never the
        // listener's: no pause here, no own way of the listener's (which would not catch up after the cast).
        val played = session.substringAfter("override fun onPlayWhenReadyChanged(").substringBefore("override fun onMediaItemTransition(")
        assertTrue("if (!leading && !service.castOn) listenerPlayed(playWhenReady)" in played)
        val moved = session.substringAfter("override fun onMediaItemTransition(").substringBefore("override fun onPositionDiscontinuity(")
        assertTrue(moved.indexOf("if (service.castOn) {") in 0 until moved.indexOf("listenerChose("))
        val sought = session.substringAfter("override fun onPositionDiscontinuity(").substringBefore("override fun onEvents(")
        assertTrue("&& !service.castOn) listenerChose(\"a seek\")" in sought)
    }

    @Test
    fun `a cast is on from the pick of a receiver until the music is back on the phone`() {
        val service = File(src, "playback/MusicService.kt").readText()
        assertTrue("val castOn: Boolean get() = castSession?.state?.value.let { it != null && it != CastSession.State.Off }" in service)
        val cast = File(src, "playback/CastSession.kt").readText()
        // On before the music goes to the receiver, so the emptied player is the cast's doing; from one receiver straight
        // to another, Off and Connecting come in the same step, so no look sees the cast end in between.
        val castTo = cast.substringAfter("fun castTo(").substringBefore("\n    private var lyricsJob")
        val connecting = castTo.indexOf("_state.value = State.Connecting(device)")
        assertTrue(castTo.indexOf("end(playHere = ") in 0 until connecting)
        assertTrue(connecting in 0 until castTo.indexOf("service.handOverTo("))
        // Off only once the music is back on the phone, so filling the player again is the cast's doing too.
        val end = cast.substringAfter("private fun end(playHere: Boolean?)").substringBefore("\n    /**")
        assertTrue(end.indexOf("service.takeBack(") in 0 until end.indexOf("_state.value = State.Off"))
    }

    @Test
    fun `the sheet shows a guest that casts on its own, with the line and no buttons`() {
        val page = sheet.substringAfter("if (!status.leading && !hostAway) {").substringBefore("\n        }\n")
        val cast = page.indexOf("status.ownWay && status.casting -> CastOwnWay(catchUpAfter = status.catchUpAfterCast)")
        assertTrue("after the wait for the host", cast > page.indexOf("status.waitingForHost -> Waiting()"))
        assertTrue("before the usual own way", cast in 0 until page.indexOf("status.ownWay -> OwnWay("))
        val castOwnWay = sheet.substringAfter("private fun CastOwnWay(").substringBefore("\n}\n")
        assertTrue("R.string.together_own_way" in castOwnWay)
        assertTrue(castOwnWay.indexOf("if (catchUpAfter) {") in 0 until castOwnWay.indexOf("R.string.together_catch_up_after_cast"))
        // Catch up and Propose work on the phone's own player, which the cast has emptied.
        assertFalse("onCatchUp" in castOwnWay || "onPropose" in castOwnWay || "Button(" in castOwnWay)
        // The session tells the sheet both.
        val look = session.substringAfter("private fun castLook()").substringBefore("\n    }\n")
        assertTrue("_status.value = status.copy(casting = casting, catchUpAfterCast = guestCast.catchUpAfter)" in look)
    }

    @Test
    fun `the line is one short sentence, open to translators, in English and French`() {
        for (folder in listOf("values", "values-fr")) {
            val file = File("src/main/res/$folder/strings-ot.xml").readText()
            val text = Regex("""<string name="together_catch_up_after_cast">([^<]*)</string>""").find(file)?.groupValues?.get(1)
            assertNotNull("$folder: the line, open to translators", text)
            assertTrue("$folder: $text", text!!.endsWith(".") && text.count { it == '.' } == 1 && text.length <= 60)
            assertFalse("$folder: $text", text.contains('—') || text.contains('–') || text.contains(';'))
        }
        val english = File("src/main/res/values/strings-ot.xml").readText()
        assertTrue("<string name=\"together_catch_up_after_cast\">You\\'ll catch up when the cast ends.</string>" in english)
    }
}
