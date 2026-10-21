/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.pill

import com.dd3boh.outertune.mix.auto.AutoInput
import com.dd3boh.outertune.mix.auto.AutoPlanner
import com.dd3boh.outertune.mix.auto.Ends
import com.dd3boh.outertune.mix.auto.SongEnd
import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.edit.TransitionSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pills' planner: what was heard wins, then Auto's own pick from the analysed ends, and a pair
 * nobody analysed says Auto. A saved change needs a look once its outgoing song's beats no longer
 * sit where it was made, as playback would refuse it.
 */
class AnalysedPlannerTest {

    private val tails = mapOf("a" to Ends.tail(122.0), "c" to Ends.tail(90.0))
    private val heads = mapOf("b" to Ends.head(121.0), "d" to Ends.head(140.0))

    private fun plan(pairs: List<SongPair>) = AnalysedPlanner.plan(pairs, { tails[it] }, { heads[it] })

    @Test
    fun `a pair whose two ends are known gets Auto's own pick, named`() {
        val pair = SongPair("a", "b")
        val choices = plan(listOf(pair, SongPair("b", "c"), SongPair("x", "d")))
        val expected = AnalysedPlanner.choiceOf(AutoPlanner.pick(AutoInput(tails.getValue("a"), heads.getValue("b"), "a", "b")))
        assertEquals(expected, choices[pair])
        assertTrue(choices[pair] is AutoChoice.Mixed)
        // Nothing is known of b's end or x's: those pairs say Auto.
        assertEquals(setOf(pair), choices.keys)
        val planner = AnalysedPlanner(PairPlanner.NOT_YET, choices)
        assertNull(planner.auto(SongPair("b", "c")))
        assertEquals(Pill.Move((expected as AutoChoice.Mixed).preset, expected.length), Pills.of(PillSong("a", null, 200_000), PillSong("b", null, 200_000), null, null, planner))
    }

    @Test
    fun `what was heard wins over the pick from the analysis`() {
        val pair = SongPair("a", "b")
        val heard = HeardChanges().apply { put(pair, AutoChoice.Mixed(Preset.ECHO_OUT, PillLength.bars(2))) }
        val planner = AnalysedPlanner(heard, plan(listOf(pair)))
        assertEquals(AutoChoice.Mixed(Preset.ECHO_OUT, PillLength.bars(2)), planner.auto(pair))
    }

    @Test
    fun `a cut has no length on its pill, a blend its whole seconds`() {
        val far = AutoPlanner.pick(AutoInput(tails.getValue("c"), heads.getValue("d"), "c", "d"))
        val choice = AnalysedPlanner.choiceOf(far)
        if (far.move == Preset.CUT && !far.isPlain) assertEquals(PillLength.NONE, (choice as AutoChoice.Mixed).length)
        if (far.bars == 0 && !far.isPlain) assertEquals(PillLength.seconds(far.seconds), (choice as AutoChoice.Mixed).length)
        // Two beats this far apart are never laid over each other.
        assertFalse(far.beatMatch)
    }

    private fun saved(end: SongEnd, atMs: Double, bpm: Double): TransitionSpec {
        val spec = TransitionSpec(
            a = Anchor("a", atMs, bpm), b = Anchor("b", 60.0, 121.0), lanes = LaneSet.BANDS, bars = 8,
            preset = Preset.FADE, moves = emptyMap(),
        )
        return Presets.apply(spec, Preset.FADE)
    }

    @Test
    fun `a saved change fits while a beat of its song sits within half a beat of its anchor at its tempo`() {
        val end = tails.getValue("a")
        val line = end.line!!
        val onBeat = line.msOf(line.lastK - 40)
        assertTrue(AnalysedPlanner.fits(saved(end, onBeat, 122.0), end))
        assertTrue(AnalysedPlanner.fits(saved(end, onBeat + 0.3 * line.beatMs, 122.0), end))
        assertFalse(AnalysedPlanner.fits(saved(end, onBeat, 126.0), end))
        // Half time or double time is the same beat.
        assertTrue(AnalysedPlanner.fits(saved(end, onBeat, 61.0), end))
        // Nothing analysed, or an anchor outside what was: nothing says otherwise.
        assertTrue(AnalysedPlanner.fits(saved(end, onBeat, 126.0), null))
        assertTrue(AnalysedPlanner.fits(saved(end, end.fromMs - 5_000.0, 126.0), end))
        // An end with no trusted beat: playback cannot find the anchor, so it needs a look.
        val beatless = Ends.tail(122.0, beatsTrusted = false, barsTrusted = false)
        assertFalse(AnalysedPlanner.fits(saved(beatless, onBeat, 122.0), beatless))
        val planner = AnalysedPlanner(PairPlanner.NOT_YET, emptyMap(), mapOf("a" to end))
        assertEquals(Pill.NeedsLook, Pills.of(PillSong("a", null, 200_000), PillSong("b", null, 200_000), saved(end, onBeat, 126.0), null, planner))
    }
}
