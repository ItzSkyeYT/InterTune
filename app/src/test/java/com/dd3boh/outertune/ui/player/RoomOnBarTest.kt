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
 * before. Since 26 Nov 2026 (Spotify's Jam) nobody goes their own way in a room: the times' row has no Catch up.
 */
class RoomOnBarTest {
    private val src = File("src/main/java/com/dd3boh/outertune")
    private val player = File(src, "ui/player/Player.kt").readText()
    private val bar = File(src, "ui/player/RoomOnBar.kt").readText()
    private val seekBar = player.substringAfter("private fun SeekBar(").substringBefore("\n}\n")

    /** His word of 26 Nov 2026 (Spotify's Jam): nobody goes their own way in a room, so the bar has no Catch up. */
    @Test
    fun `the bar has no own way line nor Catch up any more`() {
        assertFalse("OwnWayOnBar" in player)
        assertFalse("OwnWayOnBar" in bar)
        assertFalse("catchUp()" in bar)
        val times = seekBar.substringAfter("makeTimeString(shown)")
        assertTrue(times.indexOf("Spacer(Modifier.weight(1f))") in 0 until times.indexOf("makeTimeString(duration)"))
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
        // Its place and size are the bar's as ever, drawn as a disabled control is (RoomListen, 26 Nov 2026), and still
        // touched, to say why.
        assertFalse("enabled =" in seekBar)
        assertTrue(".alpha(if (held) HELD_BAR_ALPHA else 1f)" in seekBar)
        assertTrue("thumbSize = DpSize(4.dp, 36.dp)" in seekBar)
        assertTrue("modifier = Modifier.height(14.dp)," in seekBar)
        // The held flag is read only as a finger or key acts: the bar composes for it no more.
        assertFalse(Regex("""if \(held\)\s*\{\s*\n\s*(Slider|Text|Box)""").containsMatchIn(seekBar))
    }

    @Test
    fun `a guest who listens has shuffle and repeat shown disabled, in the player and the queue alike`() {
        val queue = File(src, "ui/player/Queue.kt").readText()
        for ((name, text) in listOf("Player.kt" to player, "Queue.kt" to queue)) {
            assertTrue(name, "val orderHeld by rememberOrderHeld(playerConnection)" in text)
            assertTrue(name, Regex("""enabled = (barWorks && )?!orderHeld,""").findAll(text).count() == 2)
            val shuffle = text.substringAfter("if (orderHeld) return@ShuffleButton").substringBefore("haptic.felt(")
            assertTrue(name, "playerConnection.triggerShuffle()" in shuffle)
            // Through the connection: a guest the host lets control the music changes them for everyone.
            val repeat = text.substringAfter("if (orderHeld) return@RepeatButton").substringBefore("haptic.felt(")
            assertTrue(name, "playerConnection.toggleRepeat()" in repeat)
        }
        assertTrue("return rememberRoomFlag(room, ::listensOnly)" in bar)
    }

    @Test
    fun `the sheet hands the room in, and the bar takes only a yes or no from its status`() {
        assertTrue("below = controlSizes.gapAboveTransport,\n                        room = room,\n" in player)
        assertFalse("status.collectAsState()" in bar)
        assertTrue("room?.status?.map(of)?.distinctUntilChanged()" in bar)
    }
}
