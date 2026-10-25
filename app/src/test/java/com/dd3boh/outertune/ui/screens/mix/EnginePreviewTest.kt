/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.Copy
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The editor's preview off the audio thread: what it plays for a plain change of two songs at
 * different rates, and how it moves from one place to another while it plays, block by block.
 */
class EnginePreviewTest {

    private fun plain(aAtMs: Double) = TransitionSpec(
        a = Anchor("a", aAtMs, 120.0), b = Anchor("b", 0.0, 120.0), lanes = LaneSet.BANDS,
        bars = 8, preset = Preset.CUSTOM, plain = true, moves = emptyMap(),
    )

    @Test
    fun `a plain change plays the next song at its own speed when the two rates differ`() {
        // A: a 48 kHz stream's last 10 s, silent. B: a 44.1 kHz file whose every sample says which frame it is.
        val a = Copy(ShortArray(2 * 48_000 * 10), 48_000, 2, startMs = 100_000.0)
        val b = Copy(ShortArray(2 * 44_100 * 10) { ((it / 2) % 20_000).toShort() }, 44_100, 2)
        val sound = PreviewSound(a, b, null)
        // Bar 1 two seconds before A's end, two-second bars: B starts at bar 1 and is heard from there.
        val heard = sound.heardOf(PreviewSetup(plain(108_000.0), -1.0, 4.0, null))
        // Two seconds into B: its frame 88,200 at its own rate, 96,000 at A's.
        val i = heard.indexAt(2.0)
        val frame = heard.pcm[i * 2].toInt()
        assertEquals((88_200 % 20_000).toDouble(), frame.toDouble(), 2.0)
    }

    /** A window that holds [level] everywhere, over bars [from] to [to] at 48 kHz with two-second bars. */
    private fun steady(level: Short, from: Double = -2.0, to: Double = 10.0): PreviewHeard {
        val spec = plain(108_000.0)
        val step = 1000.0 / 48_000 / spec.b.barMs
        val n = ((to - from) / step).roundToInt()
        return PreviewHeard(PreviewSetup(spec, from, to, null), ShortArray(2 * n) { level }, n, { from + it * step }, { u -> ((u - from) / step).roundToInt().coerceIn(0, n) })
    }

    private fun playhead(level: Short = 10_000): PreviewPlayhead {
        val copy = Copy(ShortArray(2 * 48_000 * 40) { level }, 48_000, 2, startMs = 90_000.0)
        return PreviewPlayhead(48_000, 2, PreviewSound(copy, copy, null))
    }

    /** The largest step from one sample to the next, the first against [before]. */
    private fun largestStep(before: Short, block: ShortArray): Int {
        var last = before.toInt()
        var worst = 0
        for (i in block.indices step 2) {
            worst = max(worst, abs(block[i] - last))
            last = block[i].toInt()
        }
        return worst
    }

    @Test
    fun `a seek while it plays crosses from where it was, never drops to silence`() {
        val h = playhead()
        val heard = steady(10_000)
        val block = ShortArray(EnginePreview.BLOCK * 2)
        var written = 0L
        for (n in 0 until 4) {
            assertTrue(h.fill(block, heard, { null }, null, true, written) { _, _, _ -> })
            written += EnginePreview.BLOCK
        }
        val before = block[block.size - 2]
        assertEquals(10_000, before.toInt())
        // A tap on bar 5 of the ruler.
        var seek: Double? = 5.0
        assertTrue(h.fill(block, heard, { seek.also { seek = null } }, null, true, written) { _, _, _ -> })
        assertTrue("a step of ${largestStep(before, block)}", largestStep(before, block) < 200)
    }

    @Test
    fun `the click comes in and goes out crossed with the change, not cut`() {
        val h = playhead()
        val heard = steady(10_000)
        val block = ShortArray(EnginePreview.BLOCK * 2)
        var written = 0L
        for (n in 0 until 4) {
            h.fill(block, heard, { null }, null, true, written) { _, _, _ -> }
            written += EnginePreview.BLOCK
        }
        // Listen with a click: A alone from the canvas's start.
        var before = block[block.size - 2]
        var seek: Double? = -2.0
        h.fill(block, heard, { seek.also { seek = null } }, Side.A, true, written) { _, _, _ -> }
        written += EnginePreview.BLOCK
        assertTrue("click on: a step of ${abs(block[0] - before)}", abs(block[0] - before) < 1_000)
        for (n in 0 until 4) {
            h.fill(block, heard, { null }, Side.A, true, written) { _, _, _ -> }
            written += EnginePreview.BLOCK
        }
        // And off again, with no seek.
        before = block[block.size - 2]
        h.fill(block, heard, { null }, null, true, written) { _, _, _ -> }
        assertTrue("click off: a step of ${abs(block[0] - before)}", abs(block[0] - before) < 1_000)
    }

    @Test
    fun `with the click on, a change rendered afresh is never heard under it`() {
        // An edit while Fix the beat plays the song alone with its click: what goes on under the
        // newer render for a few ms is what was heard, the song and its click, not the change.
        fun play(edited: Boolean): ShortArray {
            val h = playhead()
            val first = steady(10_000)
            val block = ShortArray(EnginePreview.BLOCK * 2)
            var written = 0L
            var seek: Double? = -2.0
            for (n in 0 until 4) {
                h.fill(block, first, { seek.also { seek = null } }, Side.A, true, written) { _, _, _ -> }
                written += EnginePreview.BLOCK
            }
            h.fill(block, if (edited) steady(5_000) else first, { null }, Side.A, true, written) { _, _, _ -> }
            return block
        }
        assertArrayEquals(play(edited = false), play(edited = true))
    }
}
