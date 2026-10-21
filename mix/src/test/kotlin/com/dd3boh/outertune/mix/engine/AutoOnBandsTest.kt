/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.Synth
import com.dd3boh.outertune.mix.SynthSpec
import com.dd3boh.outertune.mix.auto.AutoKind
import com.dd3boh.outertune.mix.auto.AutoPlain
import com.dd3boh.outertune.mix.auto.ProofCheck
import com.dd3boh.outertune.mix.edit.Preset
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auto on bands from the music at hand: the pick is rendered by the lane mixer and judged before
 * it is kept, the render handed back is the one that passed and the one the editor's preview
 * gives for the same spec, and a change that cannot be ready in time is never handed over.
 */
class AutoOnBandsTest {

    private val rate = 44_100
    private val songA by lazy { Synth.song(SynthSpec(bpm = 120.0, bars = 48, seed = 91)) }
    private val songB by lazy { Synth.song(SynthSpec(bpm = 121.0, bars = 32, seed = 92)) }

    /** A's last 40 s as the held end has them, B's first 32 s as its copy. */
    private fun pair(a: com.dd3boh.outertune.mix.SynthSong = songA, b: com.dd3boh.outertune.mix.SynthSong = songB): Pair<AutoOnBands.Song, AutoOnBands.Song> {
        val fromS = a.seconds - 40.0
        val tail = a.slice(fromS)
        val head = b.slice(0.0, 32.0)
        val aSong = AutoOnBands.Song(
            "a", Copy(tail, rate, 2, fromS * 1000), RegionAnalysis.analyse(tail, rate, 2, RegionKind.TAIL), fromS * 1000, a.seconds * 1000,
        )
        val bSong = AutoOnBands.Song(
            "b", Copy(head, rate, 2), RegionAnalysis.analyse(b.slice(0.0, 30.0), rate, 2, RegionKind.HEAD), 0.0, b.seconds * 1000,
        )
        return aSong to bSong
    }

    @Test
    fun `the change kept is rendered, proven clean, and is what the preview plays`() {
        val (a, b) = pair()
        val chosen = AutoOnBands.choose(a, b)
        println("${chosen.pick.describe()}; ${chosen.proofs} proofs in %.0f ms".format(chosen.millis))
        assertTrue(chosen.pick.describe(), !chosen.pick.isPlain)
        val render = chosen.render
        assertNotNull(render)
        assertEquals(chosen.spec, render!!.plan.spec)
        assertTrue(chosen.proofs >= 1)
        // The very proof Auto judged it on passes.
        val proof = AutoOnBands.proofOf(render, a.copy, b.copy, chosen.pick.kind == AutoKind.BEAT_LOCKED)
        val verdict = ProofCheck.check(proof)
        assertTrue(verdict.why, verdict.passes)
        // Two steady beats at 120 and 121 BPM lock: the outgoing song is brought to the next one's tempo.
        assertEquals(AutoKind.BEAT_LOCKED, chosen.pick.kind)
        assertTrue(chosen.spec.beatMatched)
        // The editor's preview of that spec from the same copies plays the same samples the stage would.
        val window = Preview.render(chosen.spec, a.copy, b.copy, a.analysis).getOrThrow()
        val staged = render.mix(a.copy.pcm, b.copy.pcm, window.fromFrame, window.toFrame)
        assertArrayEquals(staged, window.pcm)
    }

    @Test
    fun `nothing that would come before it can be ready is ever kept`() {
        val (a, b) = pair()
        // As good as no time left: every candidate starts before it could be in place.
        val late = (a.copy.frames - rate).toLong()
        val chosen = AutoOnBands.choose(a, b, earliest = late)
        assertTrue(chosen.pick.describe(), chosen.pick.isPlain)
        assertNull(chosen.render)
        assertTrue(chosen.pick.plain == AutoPlain.NO_FIT || chosen.pick.plain == AutoPlain.NO_CLEAN_MIX)
    }

    @Test
    fun `tempos far apart are handed over, never laid over each other`() {
        val far = Synth.song(SynthSpec(bpm = 92.0, bars = 24, seed = 93))
        val (a, b) = pair(b = far)
        val chosen = AutoOnBands.choose(a, b)
        println(chosen.pick.describe())
        assertTrue(!chosen.spec.beatMatched)
        if (!chosen.pick.isPlain) {
            assertTrue(chosen.pick.kind == AutoKind.HAND_OVER || chosen.pick.kind == AutoKind.BLEND)
            assertTrue(chosen.pick.move in setOf(Preset.ECHO_OUT, Preset.CUT, Preset.BLEND, Preset.RISE))
            assertNotNull(chosen.render)
        }
    }
}
