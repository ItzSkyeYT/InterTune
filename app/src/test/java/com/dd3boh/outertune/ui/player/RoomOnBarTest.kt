/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.together.TogetherSession
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A guest's seek bar in a room (#testers, 22 Nov 2026). Ti-Die: "wait when i change the timeline does it do it for
 * everyone", then "itd be cool either to have the timeline be disabled or have a playhead showing the group's time
 * area". Mel's pick: locked, the host can let everyone (RoomSettings). Held, the bar keeps its place, size and look and
 * moves nothing, and a touch says who decides where the song is. Let free, a guest's seek takes it its own way as
 * before, and the times' row under the bar now says "On your own" with Catch up. Nothing under the bar moves or grows.
 */
class RoomOnBarTest {
    private val src = File("src/main/java/com/dd3boh/outertune")
    private val player = File(src, "ui/player/Player.kt").readText()
    private val bar = File(src, "ui/player/RoomOnBar.kt").readText()
    private val seekBar = player.substringAfter("private fun SeekBar(").substringBefore("\n}\n")

    /** A guest that has heard the leader, following in step. */
    private val guest = TogetherSession.Status(waitingForHost = false)

    @Test
    fun `the bar says a guest is on its own, with Catch up, when the sheet would offer it`() {
        assertTrue(ownWayOnBar(guest.copy(ownWay = true)))
        // Following, nothing to say; the one in control has nobody to follow.
        assertFalse(ownWayOnBar(guest))
        assertFalse(ownWayOnBar(guest.copy(ownWay = true, leading = true)))
        // Casting, it catches up by itself when the cast ends; with no host heard there is nowhere to catch up to.
        assertFalse(ownWayOnBar(guest.copy(ownWay = true, casting = true)))
        assertFalse(ownWayOnBar(guest.copy(ownWay = true, waitingForHost = true)))
        assertFalse(ownWayOnBar(guest.copy(ownWay = true, hostAway = true)))
        // Paused here is not its own way: play takes it back to the room, and the sheet says so.
        assertFalse(ownWayOnBar(guest.copy(pausedHere = true)))
    }

    @Test
    fun `held, the bar moves nothing by finger, tap or key, and says why when the finger lifts`() {
        assertTrue("val held by rememberSeekHeld(room)" in seekBar)
        // The drag over the bar, its times and the room under them, and the slider's own drag and tap.
        assertTrue("onSlide = { if (!held) onSlide(it) }," in seekBar)
        assertTrue("onValueChange = { if (!held) onSlide(it.toLong()) }," in seekBar)
        assertTrue("onSlideDone = { if (held) sayHeld() else onSlideDone() }," in seekBar)
        assertTrue("onValueChangeFinished = { if (held) sayHeld() else onSlideDone() }," in seekBar)
        assertTrue("if (held && (event.key == Key.DirectionLeft || event.key == Key.DirectionRight)) {" in seekBar)
        assertTrue("Toaster.show(context, seekHeldLine(context, it.status.value))" in seekBar)
        // Its place, size and look are the bar's as ever: not a disabled slider, greyed out.
        assertFalse("enabled =" in seekBar)
        assertTrue("thumbSize = DpSize(4.dp, 36.dp)" in seekBar)
        assertTrue("modifier = Modifier.height(14.dp)," in seekBar)
        // The held flag is read only as a finger or key acts: the bar composes for it no more.
        assertFalse(Regex("""if \(held\)\s*\{\s*\n\s*(Slider|Text|Box)""").containsMatchIn(seekBar))
    }

    @Test
    fun `a guest following the room has the room's shuffle and repeat, in the player and the queue alike`() {
        val queue = File(src, "ui/player/Queue.kt").readText()
        for ((name, text) in listOf("Player.kt" to player, "Queue.kt" to queue)) {
            assertTrue(name, "val orderHeld by rememberOrderHeld(playerConnection)" in text)
            assertTrue(name, "if (orderHeld) return@ShuffleButton sayOrderHeld(context, playerConnection)\n" in text)
            assertTrue(name, "if (orderHeld) return@RepeatButton sayOrderHeld(context, playerConnection)\n" in text)
            // First, before anything is turned or felt.
            val shuffle = text.substringAfter("if (orderHeld) return@ShuffleButton").substringBefore("haptic.felt(")
            assertTrue(name, "playerConnection.triggerShuffle()" in shuffle)
            val repeat = text.substringAfter("if (orderHeld) return@RepeatButton").substringBefore("haptic.felt(")
            assertTrue(name, "playerConnection.player.toggleRepeatMode()" in repeat)
        }
        assertTrue("return rememberRoomFlag(room, ::followsRoom)" in bar)
        assertTrue("Toaster.show(context, orderHeldLine(context, status))" in bar)
    }

    @Test
    fun `the line sits between the times, and the sheet hands the room in`() {
        val times = seekBar.substringAfter("makeTimeString(shown)")
        assertTrue(times.indexOf("OwnWayOnBar(room, color, Modifier.weight(1f))") in 0 until times.indexOf("makeTimeString(duration)"))
        assertTrue("below = controlSizes.gapAboveTransport,\n                        room = room,\n" in player)
    }

    @Test
    fun `nothing under the bar grows or moves for the line`() {
        val line = bar.substringAfter("internal fun OwnWayOnBar(").substringBefore("\n}\n")
        // The words in the times' own style, as tall as they are; Catch up with no height of its own in the row.
        assertTrue("stringResource(R.string.together_on_your_own),\n                    style = MaterialTheme.typography.labelMedium," in line)
        assertTrue("layout(placeable.width, 0) { placeable.place(0, -placeable.height / 2) }" in line)
        assertTrue("Box(modifier, contentAlignment = Alignment.Center)" in line)
        assertTrue("room?.catchUp()" in line)
        // The room's status changes four times a second while a guest follows: the bar takes only its yes or no.
        assertFalse("status.collectAsState()" in bar)
        assertTrue("room?.status?.map(of)?.distinctUntilChanged()" in bar)
    }
}
