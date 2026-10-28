/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.Curve
import com.dd3boh.outertune.mix.Headroom
import com.dd3boh.outertune.mix.MixPlan
import com.dd3boh.outertune.mix.Style
import com.dd3boh.outertune.mix.TimeMap
import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.SongPair
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.edit.toJson
import com.dd3boh.outertune.mix.edit.transitionSpecOf
import com.dd3boh.outertune.mix.engine.SpecPlan
import com.dd3boh.outertune.mix.pill.Pills
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The editor's state machine on the first acceptance test's pair: Wake Me Up (A, 111 BPM here)
 * into After Hours (B, 109 BPM), placeholder ids, no audio. Presets, Custom, undo, the length
 * keeping the change's end, anchors on beats, Save, and Auto made from a plan.
 */
class MixEditorTest {

    private val gridA = SongGrid(t0Ms = 281_340.0, periodMs = 60_000.0 / 111)
    private val gridB = SongGrid(t0Ms = 1_210.0, periodMs = 60_000.0 / 109)

    private fun facts(aWindowStartMs: Double = 236_000.0, bWindowEndMs: Double = 60_000.0, bGrid: SongGrid? = gridB, aGrid: SongGrid? = gridA) = PairFacts(
        a = SongFacts("wake-me-up", "Wake Me Up", 308_000.0, aWindowStartMs, 308_000.0, aGrid, focusMs = 290_000.0),
        b = SongFacts("after-hours", "After Hours", 361_000.0, 0.0, bWindowEndMs, bGrid, firstSoundMs = 1_210.0, focusMs = 1_210.0),
    )

    private fun editor(f: PairFacts = facts(), saved: TransitionSpec? = null): MixEditor = MixEditor(f, AutoSpec.fallback(f), saved)

    private val aLows = LaneId(Side.A, Lane.LOWS)
    private val bLows = LaneId(Side.B, Lane.LOWS)
    private val aMids = LaneId(Side.A, Lane.MIDS)
    private val bHighs = LaneId(Side.B, Lane.HIGHS)

    @Test
    fun `it opens on Auto, lit, with nothing to save or undo`() {
        val e = editor()
        assertEquals(Preset.AUTO, e.lit())
        assertTrue(e.spec.isValid)
        assertEquals(8, e.spec.bars)
        assertFalse(e.dirty)
        assertFalse(e.canUndo)
        // Bar 1 sits on a downbeat of each song, and the change ends inside A.
        assertTrue(abs(e.spec.a.atMs - gridA.beatMs(gridA.downbeatNear(e.spec.a.atMs))) < 1e-6)
        assertEquals(1_210.0, e.spec.b.atMs, 1e-6)
        assertTrue(e.spec.aMsAt(8.0) <= 308_000.0)
    }

    @Test
    fun `Wake Me Up plays 1_8 percent slower, beat matched at 8 bars`() {
        val e = editor()
        assertTrue(e.spec.beatMatched)
        assertEquals(0.982, e.spec.tempoRatio(), 0.001)
    }

    @Test
    fun `every bands preset at every length is a valid spec that lights its own chip`() {
        val e = editor()
        for (bars in TransitionSpec.LENGTHS_BARS) {
            assertTrue(e.setLength(bars) || e.spec.bars == bars)
            for (p in listOf(Preset.FADE, Preset.RISE, Preset.BLEND, Preset.ECHO_OUT, Preset.CUT, Preset.RIFF_RISE)) {
                e.tapPreset(p)
                assertTrue("$p at $bars: ${e.spec.problems()}", e.spec.isValid)
                assertEquals("$p at $bars", p, e.lit())
            }
        }
    }

    @Test
    fun `a parts preset is refused on bands`() {
        val e = editor()
        assertEquals(PresetTap.NEEDS_PARTS, e.tapPreset(Preset.VOCALS_OVER))
        assertEquals(Preset.AUTO, e.lit())
        assertFalse(e.canUndo)
    }

    @Test
    fun `any edit lights Custom, and undo and redo walk it back and forth`() {
        val e = editor()
        e.tapPreset(Preset.BLEND)
        val before = e.spec
        e.live(e.handleMoved(before, aMids, 2.0))
        assertTrue(e.commit(before))
        assertEquals(Preset.CUSTOM, e.lit())
        assertTrue(e.dirty)
        assertTrue(e.undo())
        assertEquals(Preset.BLEND, e.lit())
        assertTrue(e.redo())
        assertEquals(Preset.CUSTOM, e.lit())
        assertTrue(e.undo())
        assertTrue(e.undo())
        assertEquals(Preset.AUTO, e.lit())
        assertFalse(e.undo())
    }

    @Test
    fun `a gesture that ends where it began is no undo step`() {
        val e = editor()
        val before = e.spec
        e.live(e.handleMoved(before, aMids, 1.0))
        e.live(e.handleMoved(before, aMids, before.moves.getValue(aMids).u))
        assertFalse(e.commit(before))
        assertFalse(e.canUndo)
    }

    @Test
    fun `a preset over custom moves says so, and Custom brings them back`() {
        val e = editor()
        e.apply(e.handleMoved(e.spec, aMids, 2.0))
        val custom = e.spec
        assertEquals(PresetTap.REPLACED_CUSTOM, e.tapPreset(Preset.CUT))
        assertEquals(Preset.CUT, e.lit())
        assertEquals(PresetTap.DONE, e.tapPreset(Preset.CUSTOM))
        assertEquals(custom.moves, e.spec.moves)
        assertEquals(Preset.CUSTOM, e.lit())
    }

    @Test
    fun `tapping the lit preset does nothing`() {
        val e = editor()
        e.tapPreset(Preset.RISE)
        assertEquals(PresetTap.NOTHING, e.tapPreset(Preset.RISE))
    }

    @Test
    fun `a new length keeps where the change ends in A, and writes the preset again`() {
        val e = editor()
        e.tapPreset(Preset.FADE)
        val endBefore = e.spec.aMsAt(e.spec.lengthBars)
        assertTrue(e.setLength(16))
        assertEquals(16, e.spec.bars)
        assertEquals(endBefore, e.spec.aMsAt(16.0), 1.0)
        assertEquals(Preset.FADE, e.lit())
        assertEquals(EditorPresets.moves(Preset.FADE, 16.0, LaneSet.BANDS), e.spec.moves)
        // 16 bars allow only 4%: still within it.
        assertTrue(e.spec.beatMatched)
        assertTrue(e.setLength(2))
        assertEquals(endBefore, e.spec.aMsAt(2.0), 1.0)
    }

    @Test
    fun `a custom change keeps its moves at the same share of a new length`() {
        val e = editor()
        e.tapPreset(Preset.BLEND)
        e.apply(e.handleMoved(e.spec, aMids, 2.0))
        assertEquals(Preset.CUSTOM, e.lit())
        assertTrue(e.setLength(16))
        assertEquals(4.0, e.spec.moves.getValue(aMids).u, 1e-9)
        assertEquals(Preset.CUSTOM, e.lit())
    }

    @Test
    fun `a length beyond what can be read is refused, with why`() {
        // Only 30 s of B's opening could be read: 16 bars and the 2 after need 39.6 s.
        val e = editor(facts(bWindowEndMs = 30_000.0))
        val sixteen = e.lengthOptions().first { it.bars == 16 }
        assertNotNull(sixteen.refused)
        assertTrue(sixteen.refused is Limit.BReach)
        assertFalse(e.setLength(16))
        assertNull(e.lengthOptions().first { it.bars == 8 }.refused)
    }

    @Test
    fun `the lengths offered are bars, and seconds too with beat matching off`() {
        val e = editor()
        assertEquals(listOf(2, 4, 8, 16), e.lengthOptions().map { it.bars })
        e.setBeatMatch(false)
        assertEquals(listOf(4.0, 6.0, 8.0, 12.0), e.lengthOptions().filter { it.bars == 0 }.map { it.seconds })
        assertTrue(e.setLength(0, 6.0))
        assertEquals(6_000.0, e.spec.lengthMs, 1e-6)
        assertFalse(e.spec.beatMatchAllowed())
    }

    @Test
    fun `A's anchor moves by whole bars, or beats when held still, and snaps to its grid`() {
        val e = editor()
        val from = e.spec
        val bar = from.a.barMs
        // A finger a bar and a third to the right brings the bar before to bar 1.
        val (moved, limit) = e.anchorMoved(from, Side.A, 1.3 * e.perA(from), byBeats = false)
        assertNull(limit)
        assertEquals(from.a.atMs - bar, moved.a.atMs, 1e-6)
        assertTrue(gridA.isDownbeat(gridA.beatNear(moved.a.atMs)))
        val (beat, _) = e.anchorMoved(from, Side.A, 1.3 * from.a.barMs / 4, byBeats = true)
        assertEquals(from.a.atMs - bar / 4, beat.a.atMs, 1e-6)
    }

    @Test
    fun `A cannot be dragged past what may be heard of it`() {
        val e = editor()
        val from = e.spec
        // Far to the right: an A from before its last 1:12.
        val (early, limit) = e.anchorMoved(from, Side.A, 120_000.0, byBeats = false)
        assertTrue(limit is Limit.AReach)
        assertTrue(early.aMsAt(e.fitStart(early)) >= 308_000.0 - 72_000.0 - 0.5)
        // Far to the left: A would end before its last move.
        val (late, end) = e.anchorMoved(from, Side.A, -60_000.0, byBeats = false)
        assertTrue(end is Limit.AEnds)
        assertTrue(late.aMsAt(late.lengthBars) <= 308_000.0 + 0.5)
    }

    @Test
    fun `B always starts at its first second`() {
        val e = editor()
        val (same, limit) = e.anchorMoved(e.spec, Side.B, 5_000.0, byBeats = false)
        assertEquals(Limit.BFirstSecond, limit)
        assertEquals(e.spec, same)
    }

    @Test
    fun `handles snap to beats, with bar lines sticky`() {
        val e = editor()
        assertEquals(3.0, e.snap(2.93), 1e-9)
        assertEquals(2.75, e.snap(2.8), 1e-9)
        assertEquals(2.5, e.snap(2.6), 1e-9)
        val moved = e.handleMoved(e.spec, aMids, 2.6)
        assertEquals(2.5, moved.moves.getValue(aMids).u, 1e-9)
        // Never past the change's end.
        assertTrue(e.handleMoved(e.spec, aMids, 30.0).moves.getValue(aMids).u <= 8.0)
    }

    @Test
    fun `a handle let go within a beat of the other song's snaps into a swap`() {
        val e = editor()
        e.tapPreset(Preset.BLEND)
        e.apply(e.handleMoved(e.spec, bLows, 2.0))
        val before = e.spec
        e.live(e.handleMoved(before, aLows, 2.25))
        assertTrue(e.handleReleased(aLows))
        assertEquals(2.0, e.spec.moves.getValue(aLows).u, 1e-9)
        assertTrue(e.commit(before))
    }

    @Test
    fun `a how sets a cut's ramp to nothing and gives a ramp to what had none`() {
        val e = editor()
        e.setHow(listOf(aMids), How.CUT)
        assertEquals(0.0, e.spec.moves.getValue(aMids).len, 0.0)
        e.setHow(listOf(aMids), How.MUFFLE)
        assertEquals(1.0, e.spec.moves.getValue(aMids).len, 0.0)
        // A how a side cannot have is skipped.
        e.setHow(listOf(bHighs), How.ECHO)
        assertEquals(How.THIN, e.spec.moves.getValue(bHighs).how)
    }

    @Test
    fun `undo keeps fifty steps`() {
        val e = editor()
        repeat(60) { i -> e.apply(e.handleMoved(e.spec, aMids, if (i % 2 == 0) 1.0 else 2.0)) }
        var n = 0
        while (e.undo()) n++
        assertEquals(MixEditor.MAX_UNDO, n)
    }

    @Test
    fun `Save forgets the pair for Auto unchanged and keeps anything else`() {
        val e = editor()
        assertTrue(e.saveAction(1L) is SaveAction.Forget)
        e.apply(e.handleMoved(e.spec, aMids, 2.0))
        val keep = e.saveAction(1_792_600_000_000L)
        assertTrue(keep is SaveAction.Keep)
        keep as SaveAction.Keep
        assertEquals(Preset.CUSTOM, keep.spec.preset)
        assertEquals(1_792_600_000_000L, keep.spec.savedAt)
        assertTrue(keep.spec.isValid)
        // What is kept reads back the same.
        assertEquals(keep.spec, transitionSpecOf(keep.spec.toJson()))
    }

    @Test
    fun `Auto's pick from the phone lights Auto, not the move it was written by`() {
        val f = facts()
        // The phone's pick names its move as where its moves come from (AutoPick.spec: from = the move).
        val picked = AutoSpec.fallback(f).copy(from = Preset.FADE)
        val e = MixEditor(f, picked, null)
        assertEquals(Preset.AUTO, e.lit())
        assertFalse(e.dirty)
        assertFalse(e.backToAuto())
        assertTrue(e.saveAction(1L) is SaveAction.Forget)
        // Fade tapped on purpose is the listener's own, the same moves or not, and kept on Save.
        e.tapPreset(Preset.FADE)
        assertEquals(Preset.FADE, e.lit())
        assertTrue(e.saveAction(2L) is SaveAction.Keep)
        // Auto again forgets the pair.
        e.tapPreset(Preset.AUTO)
        assertEquals(Preset.AUTO, e.lit())
        assertTrue(e.saveAction(3L) is SaveAction.Forget)
        // A saved custom change, then Back to Auto: Save forgets.
        val custom = MixEditor(f, picked, null).also { it.apply(it.handleMoved(it.spec, aMids, 2.0)) }
        val saved = (custom.saveAction(4L) as SaveAction.Keep).spec
        val back = MixEditor(f, picked, saved)
        assertEquals(Preset.CUSTOM, back.lit())
        assertTrue(back.backToAuto())
        assertEquals(Preset.AUTO, back.lit())
        assertTrue(back.saveAction(5L) is SaveAction.Forget)
    }

    @Test
    fun `a plain change made from a pill opens at Auto's place, so a preset tapped there can play`() {
        val f = facts()
        val auto = AutoSpec.fallback(f)
        // Plain change for these two, from a pill with nothing saved: a stand-in at each song's first second.
        val fromPill = Pills.plainSpec(SongPair("wake-me-up", "after-hours"), null)
        assertTrue(fromPill.plain)
        val opened = MixEditor.openable(fromPill, auto, f)!!
        assertTrue(opened.plain)
        assertEquals(auto.a.atMs, opened.a.atMs, 1e-6)
        assertEquals(auto.b.atMs, opened.b.atMs, 1e-6)
        val e = MixEditor(f, auto, opened)
        assertNull(e.lit())
        assertFalse(e.dirty)
        e.tapPreset(Preset.FADE)
        assertEquals(Preset.FADE, e.lit())
        assertNull(e.reachOf(e.spec))
        assertTrue(AutoSpec.fits(e.spec, f))
        // A plain change saved in the editor keeps its own place, and anything else opens as it is.
        val mine = auto.copy(plain = true, a = auto.a.copy(atMs = auto.a.atMs - 4 * auto.b.barMs))
        assertTrue(AutoSpec.fits(mine, f))
        assertEquals(mine, MixEditor.openable(mine, auto, f))
        assertEquals(auto, MixEditor.openable(auto, auto, f))
        assertNull(MixEditor.openable(null, auto, f))
    }

    @Test
    fun `a change starts no further from A's end than playback can hold it, and says why`() {
        val base = facts()
        val f = PairFacts(base.a, base.b, playReachMs = PairFacts.PLAY_REACH_MS)
        val e = MixEditor(f, AutoSpec.fallback(f), null)
        // Auto's own change saves nothing to hold, so it is never refused for it.
        assertNull(e.playLimitOf(e.spec))
        // Dragged 20 s earlier, bar 1 stops where the change's first frame is 31 s before A's end.
        val (moved, limit) = e.anchorMoved(e.spec, Side.A, 20_000.0, byBeats = false)
        assertEquals(Limit.APlays(PairFacts.PLAY_REACH_MS), limit)
        val left = f.a.durationMs - SpecPlan.firstChangeMs(Presets.onBands(moved))
        assertTrue("$left", left <= PairFacts.PLAY_REACH_MS + 0.5)
        assertTrue("$left", left > PairFacts.PLAY_REACH_MS - f.a.grid!!.barMs)
        assertNull(e.reachOf(moved))
        // 16 bars at 109 BPM take 35 s: more than playback holds for now.
        assertEquals(Limit.APlays(PairFacts.PLAY_REACH_MS), e.lengthOptions().first { it.bars == 16 }.refused)
        assertFalse(e.setLength(16))
        assertNull(e.lengthOptions().first { it.bars == 8 }.refused)
        // Where nothing holds it back, the design's reach is the only limit.
        val free = MixEditor(base, AutoSpec.fallback(base), null)
        assertNull(free.lengthOptions().first { it.bars == 16 }.refused)
        assertEquals(Limit.AReach(PairFacts.REACH_A_MS), free.anchorMoved(free.spec, Side.A, 80_000.0, byBeats = false).second)
    }

    @Test
    fun `a saved change opens as it was, and Save is greyed until it changes`() {
        val first = editor()
        first.tapPreset(Preset.ECHO_OUT)
        val saved = (first.saveAction(5L) as SaveAction.Keep).spec
        val e = editor(saved = saved)
        assertEquals(Preset.ECHO_OUT, e.lit())
        assertFalse(e.dirty)
        e.backToAuto()
        assertEquals(Preset.AUTO, e.lit())
        assertTrue(e.dirty)
        assertTrue(e.saveAction(6L) is SaveAction.Forget)
    }

    @Test
    fun `a plain change lights no chip, and Back to Auto brings Auto back`() {
        val e = editor()
        assertTrue(e.plainChange())
        assertNull(e.lit())
        assertEquals(TransitionSpec.PLAIN, e.spec.label)
        assertTrue(e.saveAction(1L) is SaveAction.Keep)
        assertTrue(e.backToAuto())
        assertEquals(Preset.AUTO, e.lit())
    }

    @Test
    fun `halving a tempo keeps the anchor on a downbeat, and the fix is kept on Save`() {
        val e = editor()
        val half = BeatFix(bpm = 55.5)
        assertTrue(e.setFix(Side.A, half))
        assertEquals(55.5, e.spec.a.bpm, 1e-6)
        val grid = e.gridA()!!
        assertTrue(grid.isDownbeat(grid.beatNear(e.spec.a.atMs)))
        // Folded by two, 55.5 is still 111 against 109.
        assertTrue(e.spec.beatMatchAllowed())
        assertEquals(mapOf("wake-me-up" to e.spec.a.fix), e.fixesChanged())
        assertTrue(e.dirty)
        val action = e.saveAction(1L)
        assertEquals(half.bpm, action.fixes.getValue("wake-me-up").bpm)
        assertTrue(e.undo())
        assertTrue(e.fixesChanged().isEmpty())
    }

    @Test
    fun `moving the 1 a beat later moves the anchor to the new downbeat nearest`() {
        val e = editor()
        val at = e.spec.a.atMs
        e.setFix(Side.A, BeatFix(downbeatShift = 1))
        assertEquals(gridA.periodMs, abs(e.spec.a.atMs - at), 1e-6)
    }

    @Test
    fun `muting a lane makes the change custom`() {
        val e = editor()
        e.toggleMute(aLows)
        assertEquals(Preset.CUSTOM, e.lit())
        e.toggleMute(aLows)
        assertEquals(Preset.AUTO, e.lit())
    }

    @Test
    fun `Fade on bands reads as moments in order`() {
        val e = editor()
        e.tapPreset(Preset.FADE)
        val m = e.moments()
        assertEquals(Moment.Kind.ENTRY, m[0].kind)
        assertEquals(0.0, m[0].at, 1e-9)
        val swap = m.single { it.kind == Moment.Kind.SWAP }
        assertEquals(Lane.LOWS, swap.lane)
        assertEquals(4.0, swap.at, 1e-9)
        assertEquals(Moment.Kind.SWAP, e.momentAt(4.5)?.kind)
        assertEquals(null, e.momentAt(-1.0))
    }

    @Test
    fun `the bracket moves every exit together, inside the canvas`() {
        val e = editor()
        e.tapPreset(Preset.FADE)
        val moved = e.bracketMoved(e.spec, Side.A, -1.0)
        for (lane in LaneSet.BANDS.lanes) {
            val id = LaneId(Side.A, lane)
            assertEquals(e.spec.moves.getValue(id).u - 1.0, moved.moves.getValue(id).u, 1e-9)
        }
        val far = e.bracketMoved(e.spec, Side.A, 100.0)
        assertTrue(far.moves.values.all { it.u <= 8.0 })
    }

    @Test
    fun `the ramp steps through a beat, two beats, one, two and four bars and the whole change`() {
        val e = editor()
        assertEquals(listOf(0.25, 0.5, 1.0, 2.0, 4.0, 8.0), e.lengthSteps())
        e.stepLength(listOf(aMids), longer = true)
        assertEquals(8.0, e.spec.moves.getValue(aMids).len, 1e-9)
        e.stepLength(listOf(aMids), longer = false)
        assertEquals(4.0, e.spec.moves.getValue(aMids).len, 1e-9)
    }

    @Test
    fun `without B's bars the change is timed in seconds`() {
        val e = editor(facts(bGrid = null))
        assertEquals(0, e.spec.bars)
        assertEquals(6.0, e.spec.seconds, 1e-9)
        assertTrue(e.secondsMode)
        assertEquals(Preset.AUTO, e.lit())
        assertTrue(e.spec.isValid)
        // Handles snap to half seconds.
        assertEquals(0.25, e.snap(0.3), 1e-9)
    }

    @Test
    fun `without beats of its own, A keeps its speed and cannot be beat matched`() {
        // B at 118 BPM is within 4% of the stand-in 120 an anchor carries without a grid.
        val e = editor(facts(aGrid = null, bGrid = SongGrid(t0Ms = 1_210.0, periodMs = 60_000.0 / 118)))
        assertEquals(8, e.spec.bars)
        assertFalse(e.spec.beatMatched)
        assertFalse(e.beatMatchPossible)
        assertFalse(e.setBeatMatch(true))
        assertFalse(e.spec.beatMatched)
    }

    @Test
    fun `Auto from a beat plan starts bar 1 where the overlap starts in both songs`() {
        val f = facts()
        val rate = 48_000
        val windowStart = 236_000.0
        val aOverlap = gridA.beatMs(gridA.downbeatNear(290_000.0))
        val overlapStart = (aOverlap - windowStart) / 1000.0 * rate
        val bStart = overlapStart - (gridB.t0Ms / 1000.0 * rate)
        val plan = MixPlan(
            style = Style.BEAT, reason = "test", sampleRate = rate, channels = 2, tailFrames = (72.0 * rate).toLong(),
            bStart = bStart.toLong(), aMap = TimeMap.IDENTITY, aFirstProcessed = 0, aGone = 0, overlapStart = overlapStart,
            overlapEnd = overlapStart, gainA = Curve.constant(1.0), gainB = Curve.constant(0.0), filtersA = emptyList(), filtersB = emptyList(),
            headroom = Headroom.NONE, incomingLoad = DoubleArray(0), attenuationA = 1.0, attenuationB = 1.0, shapedUntil = 0,
            lockFrom = 0, lockTo = 0, speed = 0.982, bars = 8,
        )
        val auto = AutoSpec.of(plan, f, windowStart)
        assertEquals(Preset.AUTO, auto.preset)
        assertEquals(8, auto.bars)
        assertEquals(aOverlap, auto.a.atMs, 1.0)
        assertEquals(gridB.t0Ms, auto.b.atMs, 1.0)
        assertEquals(EditorPresets.moves(Preset.FADE, 8.0, LaneSet.BANDS), auto.moves)
        val e = MixEditor(f, auto, null)
        assertEquals(Preset.AUTO, e.lit())
    }

    @Test
    fun `Auto from a gapless plan is a plain change, and its chip is lit`() {
        val f = facts()
        val plan = MixPlan.gapless(48_000, 2, 72L * 48_000, 1.0, 1.0, "two songs of one album")
        val auto = AutoSpec.of(plan, f, 236_000.0)
        assertTrue(auto.plain)
        val e = MixEditor(f, auto, null)
        assertEquals(Preset.AUTO, e.lit())
        e.tapPreset(Preset.FADE)
        assertFalse(e.spec.plain)
        assertEquals(Preset.FADE, e.lit())
    }

    @Test
    fun `the undo steps outlive the process`() {
        val e = editor()
        e.tapPreset(Preset.RISE)
        e.tapPreset(Preset.CUT)
        val f = facts()
        val restored = MixEditor(f, AutoSpec.fallback(f), null, restoredUndo = e.undoSteps, restoredSpec = e.spec)
        assertEquals(Preset.CUT, restored.lit())
        assertTrue(restored.undo())
        assertEquals(Preset.RISE, restored.lit())
    }

    @Test
    fun `two basslines are counted where both songs' lows play`() {
        val e = editor()
        e.tapPreset(Preset.BLEND)
        // The lows swap over one beat: no time with both at more than 0.3.
        assertTrue(e.twoBasslines() <= 0.25)
        e.apply(e.handleMoved(e.spec, bLows, 1.0))
        assertTrue(e.twoBasslines() >= 2.0)
    }
}
