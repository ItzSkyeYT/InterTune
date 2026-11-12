/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.IntroLoop
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.ChangeFacts
import com.dd3boh.outertune.mix.engine.Engine
import com.dd3boh.outertune.mix.engine.SpecPlan
import com.dd3boh.outertune.playback.automix.AutomixSchedule.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When AutoMix reads the next song, holds the end and gives up, and the checks on what it gets back. */
class AutomixScheduleTest {

    @Test
    fun `the opening is read two minutes out, the end held between forty and twenty five seconds, and later is too late`() {
        assertEquals(Step.WAIT, AutomixSchedule.due(120_001))
        assertEquals(Step.READ, AutomixSchedule.due(120_000))
        assertEquals(Step.READ, AutomixSchedule.due(40_001))
        assertEquals(Step.ARM, AutomixSchedule.due(40_000))
        assertEquals(Step.ARM, AutomixSchedule.due(25_000))
        assertEquals(Step.TOO_LATE, AutomixSchedule.due(24_999))
        // A song shorter than two minutes has its next song read at once.
        assertEquals(Step.READ, AutomixSchedule.due(90_000))
    }

    @Test
    fun `a saved change that starts further from the end has its end held earlier, as far as the ring allows`() {
        // Wake Me Up into After Hours: 8 bars at 109 BPM and 4 beats of ramp, ending 9 s before the end.
        val changeLeft = 9_000L + 17_615L + 2_162L
        val from = AutomixSchedule.armFromFor(changeLeft)
        assertEquals(changeLeft + AutomixSchedule.SAVED_LEAD_MS, from)
        assertEquals(Step.ARM, AutomixSchedule.due(from, from))
        assertEquals(Step.READ, AutomixSchedule.due(from + 1, from))
        assertEquals(500L, AutomixSchedule.nextLookMs(from - 100, 1f, from))
        // A change close to the end is held as Auto's are, and one too far from it no earlier than the ring can hold.
        assertEquals(AutomixSchedule.ARM_FROM_LEFT_MS, AutomixSchedule.armFromFor(10_000))
        assertEquals(AutomixSchedule.ARM_FROM_LEFT_MAX_MS, AutomixSchedule.armFromFor(100_000))
        assertTrue(AutomixSchedule.ARM_FROM_LEFT_MAX_MS < AutomixSchedule.CAPACITY_SECONDS * 1000L)
    }

    @Test
    fun `a change far from the end has its end held early enough, the next song's skip included`() {
        // The reels' box: A cut on a line 70 s before its end, 8 bars of 128 BPM before it and 8
        // beats of ramp, so its first change 88.75 s before the end; B comes in 30 s into it.
        val changeLeft = 70_000L + 15_000L + 3_750L
        assertEquals(changeLeft + AutomixSchedule.SAVED_LEAD_MS, AutomixSchedule.armFromFor(changeLeft))
        // Skipping B's first 30 s at eight times real time adds 3.75 s, and the ring still holds it all.
        assertEquals(changeLeft + AutomixSchedule.SAVED_LEAD_MS + 3_750L, AutomixSchedule.armFromFor(changeLeft, entrySeconds = 30.0))
        assertTrue(AutomixSchedule.armFromFor(changeLeft, entrySeconds = 30.0) <= AutomixSchedule.ARM_FROM_LEFT_MAX_MS)
        // An entry a minute into B for the same box asks for more than the ring holds.
        assertEquals(AutomixSchedule.ARM_FROM_LEFT_MAX_MS, AutomixSchedule.armFromFor(changeLeft, entrySeconds = 60.0))
        // The ring is as big as the change needs: Auto's usual 48 s, up to 110 s for the furthest.
        assertEquals(48 * 44_100, AutomixSchedule.ringFrames(44_100, AutomixSchedule.ARM_FROM_LEFT_MS))
        assertEquals(110 * 48_000, AutomixSchedule.ringFrames(48_000, AutomixSchedule.ARM_FROM_LEFT_MAX_MS))
        // 16-bit stereo: 21.1 MB at 48 kHz for the furthest, 8.5 MB for Auto's usual window at 44.1 kHz.
        assertEquals(21_120_000L, AutomixSchedule.ringFrames(48_000, AutomixSchedule.ARM_FROM_LEFT_MAX_MS) * 2L * 2L)
        assertEquals(8_467_200L, AutomixSchedule.ringFrames(44_100, AutomixSchedule.ARM_FROM_LEFT_MS) * 2L * 2L)
    }

    @Test
    fun `it looks again when the next step is due, often in the window, and not at all after it`() {
        assertEquals(60_000L, AutomixSchedule.nextLookMs(240_000, 1f))
        assertEquals(30_000L, AutomixSchedule.nextLookMs(150_000, 1f))
        assertEquals(15_000L, AutomixSchedule.nextLookMs(150_000, 2f))
        assertEquals(20_000L, AutomixSchedule.nextLookMs(60_000, 1f))
        assertEquals(500L, AutomixSchedule.nextLookMs(40_100, 1f))
        assertEquals(500L, AutomixSchedule.nextLookMs(30_000, 1f))
        assertNull(AutomixSchedule.nextLookMs(20_000, 1f))
    }

    @Test
    fun `holding the end waits for the song to be loaded to its end and the next one for five seconds`() {
        // 40 s left of a 200 s song, loaded to its end, and 6 s of the next one.
        assertTrue(AutomixSchedule.loaded(200_000, 160_000, 200_000, 46_000))
        assertFalse("the next song only 3 s in", AutomixSchedule.loaded(200_000, 160_000, 200_000, 43_000))
        assertFalse("this song not loaded to its end", AutomixSchedule.loaded(200_000, 160_000, 190_000, 46_000))
        assertFalse("no length", AutomixSchedule.loaded(0, 0, 0, 0))
    }

    @Test
    fun `a held end is the end of the song only when it is as long as what was left`() {
        assertTrue(AutomixSchedule.tailComplete(38_600, 38_400))
        assertTrue(AutomixSchedule.tailComplete(37_000, 38_400))
        assertFalse("a stream configured again in the middle of the song", AutomixSchedule.tailComplete(6_000, 38_400))
        assertFalse(AutomixSchedule.tailComplete(41_000, 38_400))
    }

    @Test
    fun `a plan may change nothing before what will be out by the time it is in, with a second to spare`() {
        assertEquals(44_100L * 10 + 44_100 * 5, AutomixSchedule.earliestChange(44_100L * 10, 44_100, 4_000))
        assertEquals(4_000L, AutomixSchedule.workAllowanceMs(null))
        assertEquals(2_000L, AutomixSchedule.workAllowanceMs(300))
        assertEquals(3_000L, AutomixSchedule.workAllowanceMs(2_000))
        assertEquals(8_000L, AutomixSchedule.workAllowanceMs(60_000))
    }

    @Test
    fun `the held end has room for the whole window and what follows it`() {
        assertEquals(110 * 44_100, AutomixSchedule.capacityFrames(44_100))
        assertTrue(AutomixSchedule.capacityFrames(48_000) >= (AutomixSchedule.ARM_FROM_LEFT_MS / 1000 + 5) * 48_000)
    }

    @Test
    fun `a change whose last exit is an echo copies enough of the next song for the echo`() {
        // 16 bars at 128 BPM from the next song's first second, A's mids echoing out at bar 16.
        val fade = Presets.moves(Preset.FADE, LaneSet.BANDS, 16.0, 0.25)!!
        val spec = TransitionSpec(
            a = Anchor("a", 20_000.0, 128.0), b = Anchor("b", 1_000.0, 128.0), lanes = LaneSet.BANDS,
            bars = 16, beatMatch = true, preset = Preset.CUSTOM, moves = fade + (LaneId(Side.A, Lane.MIDS) to Move(14.0, 2.0, How.ECHO)),
        )
        assertTrue(spec.problems().toString(), spec.isValid)
        val seconds = AutomixSchedule.copySecondsFor(spec)
        // The change, two bars of echo after it, the pad and the margin.
        assertEquals((1_000.0 + 16 * 1_875.0 + 2 * 1_875.0) / 1000.0 + 0.4 + 2.0, seconds, 1e-9)
        val rate = 48_000
        val facts = ChangeFacts(rate, 2, 20.0 * rate, 60L * rate, 1.0 * rate, (seconds * rate).toLong())
        val planned = SpecPlan.plan(spec, facts)
        assertTrue((planned as? SpecPlan.Result.Refused)?.why ?: "", planned is SpecPlan.Result.Planned)
        // Nothing saved, or a plain change: what Auto can reach.
        assertEquals(AutomixSchedule.HEAD_SECONDS + AutomixSchedule.COPY_MARGIN_S, AutomixSchedule.copySecondsFor(null), 1e-9)
        assertEquals(AutomixSchedule.HEAD_SECONDS + AutomixSchedule.COPY_MARGIN_S, AutomixSchedule.copySecondsFor(spec.copy(plain = true)), 1e-9)
    }
    @Test
    fun `a saved change's lead is worked out in one place, from its first frame, its span and its entry`() {
        val fade = Presets.moves(Preset.FADE, LaneSet.BANDS, 8.0, 0.25)!!
        val spec = TransitionSpec(
            a = Anchor("a", 200_000.0, 128.0), b = Anchor("b", 31_000.0, 128.0), lanes = LaneSet.BANDS,
            bars = 8, beatMatch = true, preset = Preset.CUSTOM, moves = fade, bStartMs = 30_000.0,
        )
        assertTrue(spec.problems().toString(), spec.isValid)
        val pace = AutomixPace()
        val duration = 301_700.0
        val lead = AutomixSchedule.savedLead(spec, duration, pace)!!
        val bands = Presets.onBands(spec)
        val first = SpecPlan.firstChangeMs(bands)
        assertEquals((duration - first).toLong(), lead.changeLeftMs)
        assertEquals(30.0, lead.entrySeconds, 0.0)
        // To its last bar and the pad: no echo, A adjusted.
        assertEquals((bands.aMsAt(8.0) - first + Engine.HEADROOM_PAD_S * 1000).toLong(), lead.spanMs)
        assertEquals(pace.armFromMs(lead.changeLeftMs, lead.spanMs, lead.entrySeconds), lead.armFromMs)
        assertEquals(lead.armFromMs <= AutomixSchedule.ARM_FROM_LEFT_MAX_MS, lead.fits)
        // The next song adjusted: the way back to its own tempo and its own next bar line count too.
        val bLead = AutomixSchedule.savedLead(spec.copy(matchSide = Side.B), duration, pace)!!
        val bBands = Presets.onBands(spec.copy(matchSide = Side.B))
        val bFirst = SpecPlan.firstChangeMs(bBands)
        assertEquals((bBands.aMsAt(8.0 + SpecPlan.GLIDE_BARS + 1.0) - bFirst + Engine.HEADROOM_PAD_S * 1000).toLong(), bLead.spanMs)
        // A plain change has no lead.
        assertNull(AutomixSchedule.savedLead(spec.copy(plain = true), duration, pace))
    }
    @Test
    fun `a loop in front of the next song that runs past the change counts in its span, whichever song is adjusted`() {
        // 8 bars, A at 128 and B at 124 from its bar 1 at 4 s, B's first 4 bars looped four times: 16 bars.
        val fade = Presets.moves(Preset.FADE, LaneSet.BANDS, 8.0, 0.25)!!
        val spec = TransitionSpec(
            a = Anchor("a", 200_000.0, 128.0), b = Anchor("b", 4_000.0, 124.0), lanes = LaneSet.BANDS,
            bars = 8, beatMatch = true, preset = Preset.CUSTOM, moves = fade, introLoop = IntroLoop(4, 4_000.0, 4),
        )
        assertTrue(spec.problems().toString(), spec.isValid)
        val pace = AutomixPace()
        for (side in listOf(Side.A, Side.B)) {
            val s = Presets.onBands(spec.copy(matchSide = side))
            val first = SpecPlan.firstChangeMs(s)
            val span = AutomixSchedule.changeSpanMs(s, first)
            // To the loop's end at least, past the change's 8 bars.
            assertTrue("$side: $span", first + span >= s.aMsAt(16.0) + Engine.HEADROOM_PAD_S * 1000)
            assertEquals(span, AutomixSchedule.savedLead(s, 301_700.0, pace)!!.spanMs)
        }
        // With the next song adjusted, on to where its own samples take over after the loop.
        val b = Presets.onBands(spec.copy(matchSide = Side.B))
        val first = SpecPlan.firstChangeMs(b)
        assertEquals((SpecPlan.bAdjustedEndMs(b)!! - first + Engine.HEADROOM_PAD_S * 1000).toLong(), AutomixSchedule.changeSpanMs(b, first))
        // And the copy holds the loop's bars wherever they are in B.
        val late = spec.copy(matchSide = Side.B, introLoop = IntroLoop(2, 40_000.0, 1))
        assertTrue(AutomixSchedule.copySecondsFor(late) * 1000 >= 40_000.0 + 2 * late.b.barMs)
        val rate = 48_000
        val seconds = AutomixSchedule.copySecondsFor(late)
        val facts = ChangeFacts(rate, 2, 200.0 * rate, 300L * rate, 4.0 * rate, (seconds * rate).toLong())
        val planned = SpecPlan.plan(Presets.onBands(late), facts)
        assertTrue((planned as? SpecPlan.Result.Refused)?.why ?: "", planned is SpecPlan.Result.Planned)
    }

    @Test
    fun `a change that adjusts the next song copies it as far as its way back to its own tempo`() {
        // 16 bars, A at 88 and B at 90 BPM from its first second, A fading out: B is slowed to A's
        // tempo, so its way back takes it 3 of its bars past the change, one more than the echo's two.
        val fade = Presets.moves(Preset.FADE, LaneSet.BANDS, 16.0, 0.25)!!
        val spec = TransitionSpec(
            a = Anchor("a", 20_000.0, 88.0), b = Anchor("b", 1_000.0, 90.0), lanes = LaneSet.BANDS,
            bars = 16, beatMatch = true, preset = Preset.CUSTOM, moves = fade, matchSide = Side.B,
        )
        assertTrue(spec.problems().toString(), spec.isValid)
        assertTrue(spec.bAdjusted)
        val wayBack = SpecPlan.bReadToMs(Presets.onBands(spec))!!
        val seconds = AutomixSchedule.copySecondsFor(spec)
        // What was copied before: the change, the echo's two bars, the pad and the margin, 0.3 s short.
        val before = (spec.b.atMs + spec.lengthMs + Engine.ECHO_MAX_BARS_AFTER * spec.b.barMs) / 1000.0 + Engine.HEADROOM_PAD_S + AutomixSchedule.COPY_MARGIN_S
        assertTrue("$before for $wayBack", before * 1000 < wayBack + Engine.HEADROOM_PAD_S * 1000)
        assertEquals(wayBack / 1000.0 + Engine.HEADROOM_PAD_S + AutomixSchedule.COPY_MARGIN_S, seconds, 1e-9)
        val rate = 48_000
        val facts = ChangeFacts(rate, 2, 20.0 * rate, 60L * rate, 1.0 * rate, (seconds * rate).toLong())
        val planned = SpecPlan.plan(spec, facts)
        assertTrue((planned as? SpecPlan.Result.Refused)?.why ?: "", planned is SpecPlan.Result.Planned)
    }
}
