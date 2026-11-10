/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.Synth
import com.dd3boh.outertune.mix.SynthSpec
import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.Specs
import com.dd3boh.outertune.mix.edit.TransitionSpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The editor's whole render of a change with an echo takes 12.5 s on a phone and an edit made while
 * it ran waited for it (seen on the Pixel 5, 10 Nov 2026: 2.9 to 11 s, where every other edit was
 * heard in half a second). A render stops when it is asked to, in either of its two passes, and one
 * that is never asked is the render it always was.
 */
class RenderCancelTest {

    private val rate = 44_100
    private val songA by lazy { Synth.song(SynthSpec(bpm = 120.0, bars = 40, seed = 81)) }
    private val songB by lazy { Synth.song(SynthSpec(bpm = 120.0, bars = 30, seed = 82)) }
    private val a by lazy { Copy(songA.samples, rate, 2) }
    private val b by lazy { Copy(songB.samples, rate, 2) }

    private fun echoOut(bars: Int = 8): TransitionSpec {
        val at = songA.downbeats[songA.downbeats.size - (bars + 6)] * 1000
        val base = Specs.fadeOnBands().copy(
            a = Anchor("a", at, 120.0), b = Anchor("b", songB.downbeats[0] * 1000, 120.0), beatMatch = false, bars = bars,
        )
        return Presets.apply(base, Preset.ECHO_OUT)
    }

    /** How many times a whole render looks to see whether it should stop. */
    private fun looks(spec: TransitionSpec): Int {
        var n = 0
        Preview.render(spec, a, b, cancelled = { n++; false }).getOrThrow()
        return n
    }

    @Test
    fun `a render never asked to stop is the render it was`() {
        val s = echoOut()
        val plain = Preview.render(s, a, b).getOrThrow()
        val asked = Preview.render(s, a, b, cancelled = { false }).getOrThrow()
        assertEquals(plain.fromFrame, asked.fromFrame)
        assertEquals(plain.toFrame, asked.toFrame)
        assertArrayEquals(plain.pcm, asked.pcm)
    }

    @Test
    fun `a render asked to stop at its first look does no work and says it was cancelled`() {
        var looked = 0
        val r = runCatching { Preview.render(echoOut(), a, b, cancelled = { looked++; true }) }
        assertTrue("${r.exceptionOrNull()}", r.exceptionOrNull() is RenderCancelled)
        assertEquals(1, looked)
        // A cancellation, so the callers that tell a failure from a stop can: java.util.concurrent's.
        assertTrue(r.exceptionOrNull() is java.util.concurrent.CancellationException)
    }

    @Test
    fun `a render is looked at often, and stops within one look of being asked, in either pass`() {
        val s = echoOut()
        val total = looks(s)
        // Two passes over a window of many seconds, a look every 2048 frames: hundreds of looks.
        assertTrue("looked $total times", total > 200)
        for (stopAt in listOf(1, total / 4, total / 2, total / 2 + 1, total - 2)) {
            var n = 0
            val r = runCatching { Preview.render(s, a, b, cancelled = { ++n > stopAt }) }
            assertTrue("asked at look $stopAt of $total: ${r.exceptionOrNull()}", r.exceptionOrNull() is RenderCancelled)
            // The look that said yes was the last: nothing was worked after it.
            assertEquals(stopAt + 1, n)
        }
    }
}
