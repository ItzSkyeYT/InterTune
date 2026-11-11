/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The player's seek bar was hard to drag (6 and 7 Nov 2026). On the Pixel 5 a drag that went a
 * little down before going across was taken by the player sheet's swipe down, and the song did not
 * move; then the bar took a finger only on its own 48 dp, while the times under it and the room
 * down to the buttons, about 28 dp more, moved nothing. The bar, its times and that room now take a
 * drag: in any direction on the bar, across below it, while a swipe down from the times still
 * closes the player.
 */
class SeekBarDragTest {
    private val dir = File("src/main/java/com/dd3boh/outertune/ui/player")

    @Test
    fun `the bar with its times and the room under them drives the drag, and the room is no longer a spacer outside it`() {
        val player = File(dir, "Player.kt").readText()
        val seekBar = player.substringAfter("private fun SeekBar(").substringBefore("\n}\n")
        val strip = seekBar.substringAfter("Column(").substringBefore("Slider(")
        assertTrue(".seekDrag(" in strip)
        assertTrue("trackInset = gutter + 2.dp" in strip)
        assertTrue("barBottom = { barBottom.toFloat() }" in strip)
        assertTrue(".onSizeChanged { barBottom = it.height }" in seekBar)
        assertTrue("Spacer(Modifier.height(below))" in seekBar)
        // The thumb the inset is half of.
        assertTrue("thumbSize = DpSize(4.dp, 36.dp)" in seekBar)
        assertTrue("below = controlSizes.gapAboveTransport," in player)
        assertFalse("Spacer(Modifier.height(controlSizes.gapAboveTransport))" in player)
        val drag = File(dir, "SeekDrag.kt").readText()
        assertTrue("private val CLAIM = 3.dp" in drag)
        assertTrue("if (claimed) onSlideDone()" in drag)
        assertTrue("if (!claimed && change.isConsumed) break" in drag)
    }

    @Test
    fun `on the bar any direction claims the drag, below it only a move that goes across`() {
        val claim = 8f
        val bar = 132f
        // On the bar: a thumb that goes down first is still the bar's.
        assertTrue(claimsSeek(Offset(300f, 60f), Offset(300f, 70f), claim, bar))
        assertTrue(claimsSeek(Offset(300f, 60f), Offset(310f, 62f), claim, bar))
        // Under the claim distance nothing is claimed anywhere: a tap stays a tap.
        assertFalse(claimsSeek(Offset(300f, 60f), Offset(303f, 62f), claim, bar))
        assertFalse(claimsSeek(Offset(300f, 150f), Offset(305f, 152f), claim, bar))
        // On the times: across claims, down does not, so the player's swipe down still works there.
        assertTrue(claimsSeek(Offset(300f, 150f), Offset(312f, 156f), claim, bar))
        assertFalse(claimsSeek(Offset(300f, 150f), Offset(303f, 165f), claim, bar))
        assertTrue(claimsSeek(Offset(300f, 150f), Offset(290f, 160f), claim, bar))
    }
}
