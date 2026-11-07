/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His word of 6 Nov 2026, "the playback bar is kinda annoying to drag": on the Pixel 5 a drag that
 * went a little down before going across was taken by the player sheet's swipe down, and the song
 * did not move. A drag that starts on the bar is the bar's.
 */
class SeekBarDragTest {
    private val dir = File("src/main/java/com/dd3boh/outertune/ui/player")

    @Test
    fun `the seek bar drives its own drag, claimed long before the sheet's slop, and a tap stays the slider's`() {
        val player = File(dir, "Player.kt").readText()
        val seekBar = player.substringAfter("private fun SeekBar(").substringBefore("\n}\n")
        assertTrue(".seekDrag(duration, trackInset = 2.dp, rtl = LocalLayoutDirection.current == LayoutDirection.Rtl, onSlide = onSlide, onSlideDone = onSlideDone)" in seekBar)
        // The thumb the inset is half of.
        assertTrue("thumbSize = DpSize(4.dp, 36.dp)" in seekBar)
        val drag = File(dir, "SeekDrag.kt").readText()
        assertTrue("private val CLAIM = 3.dp" in drag)
        assertTrue("if (claimed) onSlideDone()" in drag)
    }
}
