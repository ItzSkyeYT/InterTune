/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.Copy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The editor's whole render of a change with an echo runs 12.5 s on a Pixel 5, and an edit made
 * while it ran waited for what was left of it: 2.9 to 11 s where every other edit was heard in half
 * a second (seen 10 Nov 2026). A newer edit stops that render within a few milliseconds and is the
 * one heard; a render that was stopped, or that ended as a newer edit came, is never what plays.
 *
 * Each edit is rendered whole here (streamEdits off), the path the echo's follow-up render takes.
 */
class EnginePreviewGivesWayTest {

    private val rate = 48_000

    /** A song of [seconds] of a mild saw: its content does not matter, what a render costs is its frames. */
    private fun song(seconds: Int, tilt: Int) = Copy(ShortArray(2 * rate * seconds) { (((it / 2) % 120) * 80 - 4_800 + tilt).toShort() }, rate, 2)

    private val a by lazy { song(150, 0) }
    private val b by lazy { song(90, 7) }

    /** A 32-bar fade on 2 s bars: a window of 36 bars, 72 s of music, which takes the PC a second or more. */
    private fun long(): TransitionSpec = Presets.apply(
        TransitionSpec(
            a = Anchor("a", 60_000.0, 120.0), b = Anchor("b", 0.0, 120.0), lanes = LaneSet.BANDS, bars = 32, beatMatch = false,
            preset = Preset.CUSTOM, moves = emptyMap(),
        ),
        Preset.FADE,
    )

    private fun waitFor(limitMs: Long, until: () -> Boolean): Long {
        val t0 = System.nanoTime()
        while ((System.nanoTime() - t0) / 1_000_000 < limitMs) {
            if (until()) break
            Thread.sleep(2)
        }
        return (System.nanoTime() - t0) / 1_000_000
    }

    @Test
    fun `an edit made while a whole render runs is heard long before that render would have been done`() {
        val slow = long()
        val quick = slow.copy(plain = true)
        val sound = PreviewSound(a, b, null)
        // What the long render costs on this machine, warmed once.
        sound.heardOf(PreviewSetup(slow, -2.0, 34.0, null))
        val t0 = System.nanoTime()
        sound.heardOf(PreviewSetup(slow, -2.0, 34.0, null))
        val wholeMs = (System.nanoTime() - t0) / 1_000_000
        println("a whole render of the long change takes $wholeMs ms here")
        assumeTrue("a machine that renders the long change in under 0.7 s cannot show it", wholeMs > 700)

        val preview = EnginePreview(a, b, null)
        preview.streamEdits = false
        try {
            preview.update(slow, -2.0, 34.0)
            // Its whole render is under way.
            Thread.sleep(200)
            assertEquals(null, preview.heardSetup)
            preview.update(quick, -2.0, 4.0)
            val waited = waitFor(5_000) { preview.heardSetup?.spec == quick }
            println("the newer edit was heard $waited ms after it was asked for")
            assertEquals(quick, preview.heardSetup?.spec)
            assertTrue("heard after $waited ms, a whole render takes $wholeMs", waited < wholeMs / 3)
            // And the render that gave way is never what plays, however long it would have taken.
            Thread.sleep(wholeMs + 300)
            assertEquals(quick, preview.heardSetup?.spec)
        } finally {
            preview.release()
        }
    }

    @Test
    fun `the render loop hands its whole render a look at the newest edit`() {
        val dir = File("src/main/java/com/dd3boh/outertune/ui/screens/mix")
        val preview = File(dir, "EnginePreview.kt").readText()
        val loop = preview.substringAfter("private fun renderLoop()").substringBefore("private enum class Streamed")
        assertTrue("stopped by a newer edit", "val cancelled = { wanted.get() !== next || released }" in loop)
        assertTrue("given to the render", "sound.heardOf(next, cancelled)" in loop)
        assertTrue("a stopped render is no failure", "if (it is CancellationException)" in loop)
        assertTrue("one that ended as a newer edit came is not heard", "if (wanted.get() !== next || released) {" in loop.substringAfter(".onSuccess"))
    }
}
