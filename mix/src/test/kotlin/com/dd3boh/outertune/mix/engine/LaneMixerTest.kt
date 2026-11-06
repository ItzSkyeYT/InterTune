/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.engine

import com.dd3boh.outertune.mix.ChangeSum
import com.dd3boh.outertune.mix.FrontEnd
import com.dd3boh.outertune.mix.Headroom
import com.dd3boh.outertune.mix.Peaks
import com.dd3boh.outertune.mix.Spectrum
import com.dd3boh.outertune.mix.Synth
import com.dd3boh.outertune.mix.SynthSpec
import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.Key
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneCurve
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.toPcm16
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A change rendered from a spec on bands: equal lanes give the music itself, lanes at full give
 * the song's own samples, a blend keeps its power, loud songs never pass full scale, an echo rings
 * out in time, and A lands on B's beat.
 */
class LaneMixerTest {

    private val rate = 44_100

    private fun spec(
        moves: Map<LaneId, Move>, bars: Int = 8, beatMatch: Boolean = false, aBpm: Double = 120.0, bBpm: Double = 120.0,
        preset: Preset = Preset.CUSTOM,
    ) = TransitionSpec(
        a = Anchor("song-a", 20_000.0, aBpm), b = Anchor("song-b", 1_000.0, bBpm), lanes = LaneSet.BANDS,
        bars = bars, beatMatch = beatMatch, preset = preset, moves = moves,
    )

    private fun every(side: Side, move: Move) = LaneSet.BANDS.lanes.associate { LaneId(side, it) to move }

    private fun facts(a: ShortArray, b: ShortArray, aBar1: Double = 20.0, bBar1: Double = 1.0, earliest: Long = 0) =
        ChangeFacts(rate, 2, aBar1 * rate, (a.size / 2).toLong(), bBar1 * rate, (b.size / 2).toLong(), earliest)

    private fun planned(spec: TransitionSpec, facts: ChangeFacts): LanePlan = when (val r = SpecPlan.plan(spec, facts)) {
        is SpecPlan.Result.Planned -> r.plan
        is SpecPlan.Result.Refused -> throw AssertionError("refused: ${r.why}")
    }

    @Test
    fun `equal lanes give the music itself, times their gain`() {
        val a = Synth.song(SynthSpec(bpm = 120.0, bars = 24, seed = 21)).samples
        val b = Synth.song(SynthSpec(bpm = 120.0, bars = 24, seed = 22)).samples
        for (how in listOf(How.FADE, How.CUT, How.ECHO, How.MUFFLE)) {
            val moves = every(Side.A, Move(2.0, 4.0, how)) + every(Side.B, Move(2.0, 4.0, if (how.forEntry) how else How.FADE))
            val plan = planned(spec(moves), facts(a, b))
            val dc = ShortArray(a.size) { 10_000 }
            for (side in Side.entries) {
                val music = if (side == Side.A) a else b
                val origin = if (side == Side.A) plan.mixFrom else plan.bStart
                val start = if (side == Side.A) 0L else plan.bStart
                val withMusic = SideRenderer(plan, side, PcmSource(music, 2, startFrame = start), origin)
                // A constant goes all into the lows, so the same lanes on it give the gain itself.
                val withDc = SideRenderer(plan, side, PcmSource(dc, 2, startFrame = start), origin)
                val y = DoubleArray(2)
                val g = DoubleArray(2)
                val x = DoubleArray(2)
                var worst = 0.0
                val source = PcmSource(music, 2, startFrame = start)
                for (o in origin until plan.mixTo) {
                    withMusic.next(o, y)
                    withDc.next(o, g)
                    source.frame(o, x)
                    // Away from the very start of the constant, where it is no constant yet.
                    if (o < origin + rate / 2) continue
                    if (how == How.MUFFLE || how == How.ECHO) continue
                    for (c in 0 until 2) worst = max(worst, abs(y[c] - g[c] / 10_000.0 * x[c]))
                }
                assertTrue("$how $side: $worst", worst < 1e-6)
            }
        }
    }

    @Test
    fun `lanes at full give the song's own samples, to the bit`() {
        val a = Synth.song(SynthSpec(bpm = 120.0, bars = 24, seed = 23)).samples
        val b = Synth.song(SynthSpec(bpm = 120.0, bars = 24, seed = 24)).samples
        val spec = Presets.apply(spec(emptyMap<LaneId, Move>() + every(Side.A, Move(0.0, 0.0, How.CUT)) + every(Side.B, Move(0.0, 0.0, How.CUT))), Preset.FADE)
        val plan = planned(spec, facts(a, b))
        val render = LaneMixer(plan).render(a, b, 0.8, 0.9)
        val out = render.outgoing
        // A's first lane moves at the bass swap, a beat before bar 5; up to a grid step before it,
        // and wherever the shared headroom does not dip, A is its own samples.
        val firstMove = plan.out(3.75).toLong() - 2 * LaneVoice.GRID
        var checked = 0
        for (o in plan.mixFrom until firstMove) {
            if (render.headroom.at(o) != 1.0) continue
            checked++
            for (c in 0 until 2) assertEquals("A at $o", toPcm16(a[(o * 2).toInt() + c] * 0.8), out.at(o, c))
        }
        assertTrue(checked > rate / 4)
        val inc = render.incoming
        checked = 0
        for (o in plan.bPlain until plan.mixTo) {
            if (render.headroom.at(o) != 1.0) continue
            checked++
            val j = ((o - plan.bStart) * 2).toInt()
            for (c in 0 until 2) assertEquals("B at $o", toPcm16(b[j + c] * 0.9), inc.at(o, c))
        }
        assertTrue(checked > rate / 4)
        // And the whole change, as played, joins both songs as they are at its edges.
        val heard = render.mix(a, b, plan.mixFrom - rate, plan.mixTo + rate)
        for (i in 0 until rate) for (c in 0 until 2) {
            val o = plan.mixFrom - rate + i
            assertEquals(toPcm16(a[(o * 2).toInt() + c] * 0.8), heard[i * 2 + c])
        }
    }

    @Test
    fun `a blend of two noises keeps its power within 0,2 dB`() {
        val a = Synth.noise(60.0, rate, seed = 31, rmsDb = -24.0)
        val b = Synth.noise(60.0, rate, seed = 32, rmsDb = -24.0)
        val blend = Presets.apply(spec(every(Side.A, Move(0.0, 0.0, How.CUT)) + every(Side.B, Move(0.0, 0.0, How.CUT))), Preset.BLEND)
        val plan = planned(blend, facts(a, b))
        val render = LaneMixer(plan).render(a, b, 1.0, 1.0)
        assertTrue(render.headroom.values.isEmpty() || render.headroom.values.all { it == 1f })
        val from = plan.out(-1.0).toLong()
        val to = plan.out(9.0).toLong()
        val heard = render.mix(a, b, from, to)
        val window = rate / 2
        val reference = rms(a, (from * 2).toInt(), window * 2)
        var i = 0
        while ((i + window) * 2 <= heard.size) {
            val db = 20 * log10(rms(heard, i * 2, window * 2) / reference)
            assertTrue("%.2f s: %.3f dB".format(i.toDouble() / rate, db), abs(db) < 0.2)
            i += window
        }
    }

    private fun rms(s: ShortArray, from: Int, n: Int): Double {
        var sum = 0.0
        for (i in from until from + n) sum += s[i].toDouble() * s[i]
        return sqrt(sum / n)
    }

    @Test
    fun `two loud songs never go past full scale`() {
        val a = loud(Synth.song(SynthSpec(bpm = 120.0, bars = 24, seed = 41)).samples)
        val b = loud(Synth.song(SynthSpec(bpm = 120.0, bars = 24, seed = 42)).samples)
        for (preset in listOf(Preset.FADE, Preset.BLEND, Preset.RISE, Preset.ECHO_OUT, Preset.CUT)) {
            val spec = Presets.apply(spec(every(Side.A, Move(0.0, 0.0, How.CUT)) + every(Side.B, Move(0.0, 0.0, How.CUT))), preset)
            val plan = planned(spec, facts(a, b))
            val render = LaneMixer(plan).render(a, b, 1.0, 1.0)
            val heard = render.mix(a, b, plan.mixFrom, plan.mixTo)
            var over = 0
            var clipped = 0
            for (i in 0 until heard.size / 2) {
                val o = plan.mixFrom + i
                for (c in 0 until 2) {
                    val va = render.outgoing.at(o, c).toDouble()
                    val vb = render.incoming.at(o, c).toDouble()
                    if (va != 0.0 && vb != 0.0 && abs(va + vb) > Headroom.CEILING * 32768 + 2) over++
                    if (ChangeSum.wouldClip(va, vb)) clipped++
                }
            }
            println("$preset: %.1f dB of headroom at most, peak %.3f before and %.3f after".format(render.headroom.deepestDb, render.peakBefore, render.peakAfter))
            assertEquals("$preset: clipped", 0, clipped)
            assertEquals("$preset: over the ceiling", 0, over)
        }
    }

    private fun loud(s: ShortArray): ShortArray {
        val peak = s.maxOf { abs(it.toInt()) }.toDouble()
        val gain = 32767 * 0.989 / peak
        return ShortArray(s.size) { toPcm16(s[it] * gain) }
    }

    @Test
    fun `an echo rings out after the lane stops, and is silent by its end`() {
        val a = Synth.song(SynthSpec(bpm = 120.0, bars = 24, seed = 51)).samples
        val b = Synth.song(SynthSpec(bpm = 120.0, bars = 24, seed = 52)).samples
        val spec = Presets.apply(spec(every(Side.A, Move(0.0, 0.0, How.CUT)) + every(Side.B, Move(0.0, 0.0, How.CUT))), Preset.ECHO_OUT)
        val plan = planned(spec, facts(a, b))
        val render = LaneMixer(plan).render(a, b, 1.0, 1.0)
        val dryGone = plan.out(6.0).toLong() + rate / 50
        assertTrue(plan.aGone > dryGone + plan.echoFrames)
        assertTrue(plan.aGone <= plan.out(8.0 + 2.0).toLong() + 1)
        // The mids still ring a repeat after their dry sound stopped, and nothing is left at the end.
        val ringing = rms(render.outgoing.samples, ((dryGone + plan.echoFrames / 2 - plan.mixFrom) * 2).toInt(), plan.echoFrames)
        assertTrue("ringing at %.1f dBFS".format(20 * log10(ringing / 32768)), 20 * log10(ringing / 32768) > -50)
        val last = render.outgoing.frames - 1
        assertEquals(0, render.outgoing.samples[last * 2].toInt())
    }

    @Test
    fun `a hand-drawn curve meets each song as it is at the change's edges`() {
        // A constant goes all into the lows, so the lows lane's gain is heard as the level itself.
        val dc = ShortArray(2 * 60 * rate) { 10_000 }
        val moves = every(Side.A, Move(2.0, 4.0, How.FADE)) + every(Side.B, Move(2.0, 4.0, How.FADE))
        val curves = mapOf(
            // A's lows under full from their first point, and still at 30% at their last.
            LaneId(Side.A, Lane.LOWS) to LaneCurve(listOf(Key(1.0, 0.5), Key(6.0, 0.3))),
            // B's lows brought up to 70% and left there.
            LaneId(Side.B, Lane.LOWS) to LaneCurve(listOf(Key(2.0, 0.0), Key(6.0, 0.7))),
        )
        val plan = planned(spec(moves).copy(curves = curves), facts(dc, dc))
        val render = LaneMixer(plan).render(dc, dc, 1.0, 1.0)
        val out = render.outgoing
        // A comes into the change at full, as the held end plays it until then, and moves no faster than a cut.
        assertEquals(10_000.0, out.at(plan.mixFrom, 0).toDouble(), 50.0)
        // A is silent by the last frame of its part, never stopped where it stood.
        assertEquals(0.0, out.at(out.endFrame - 1, 0).toDouble(), 50.0)
        // B is at full by the last frame of its part, where B as it plays takes over.
        assertEquals(10_000.0, render.incoming.at(plan.mixTo - 1, 0).toDouble(), 50.0)
    }

    @Test
    fun `A is stretched onto B's beat, its tempo moved before bar 1`() {
        val songA = Synth.song(SynthSpec(bpm = 111.0, bars = 40, seed = 61))
        val songB = Synth.song(SynthSpec(bpm = 109.0, bars = 24, seed = 62))
        val a = songA.samples
        val b = songB.samples
        // A plays the whole change and B is silent through it, so A's kicks can be found.
        val moves = every(Side.A, Move(0.0, 0.0, How.KEEP)) + every(Side.B, Move(8.0, 0.0, How.CUT))
        val aBar1 = songA.downbeats[songA.downbeats.size - 12]
        val bBar1 = songB.downbeats[0]
        val spec = TransitionSpec(
            a = Anchor("song-a", aBar1 * 1000, 111.0), b = Anchor("song-b", bBar1 * 1000, 109.0), lanes = LaneSet.BANDS,
            bars = 8, beatMatch = true, preset = Preset.CUSTOM, moves = moves,
        )
        val plan = planned(spec, ChangeFacts(rate, 2, aBar1 * rate, (a.size / 2).toLong(), bBar1 * rate, (b.size / 2).toLong()))
        assertEquals(109.0 / 111.0, plan.speed, 1e-12)
        val render = LaneMixer(plan).render(a, b, 1.0, 1.0)
        val from = plan.out(0.0).toLong()
        val to = plan.out(8.0).toLong()
        val n = (to - from).toInt()
        val aHeard = ShortArray(n * 2) { render.outgoing.at(from + it / 2, it % 2) }
        // B as it would be heard over the same frames, to find its kicks the same way.
        val bHeard = ShortArray(n * 2) { i -> b[((from - plan.bStart) * 2 + i).toInt()] }
        val aKicks = kicks(aHeard)
        val bKicks = kicks(bHeard)
        val gaps = ArrayList<Double>()
        for (kb in bKicks) {
            if (kb < 0.05 || kb > n.toDouble() / rate - 0.05) continue
            val ka = aKicks.minByOrNull { abs(it - kb) } ?: fail("no kicks in A") as Double
            gaps += abs(ka - kb) * 1000
        }
        assertTrue("${gaps.size} kicks", gaps.size >= 28)
        println("A on B's beat: %.2f ms apart on average, %.2f at most".format(gaps.average(), gaps.max()))
        assertTrue(gaps.max() <= 10.0)
        assertTrue(gaps.average() <= 5.0)
    }

    private fun kicks(samples: ShortArray): List<Double> {
        val spectrum = Spectrum.of(FrontEnd.of(samples, rate, 2))
        return Peaks.of(spectrum.lowOnset, 1.5f, 8).map { spectrum.frameToSample(it.frame) / rate }
    }

    @Test
    fun `a change the music at hand cannot hold is refused, with why`() {
        val a = Synth.noise(40.0, rate)
        val b = Synth.noise(10.0, rate)
        val fade = Presets.apply(spec(every(Side.A, Move(0.0, 0.0, How.CUT)) + every(Side.B, Move(0.0, 0.0, How.CUT))), Preset.FADE)
        fun why(spec: TransitionSpec, facts: ChangeFacts) = (SpecPlan.plan(spec, facts) as? SpecPlan.Result.Refused)?.why
        assertTrue(why(fade, facts(a, b))!!.startsWith("B's copy holds 10.0 s"))
        val longB = Synth.noise(40.0, rate)
        assertEquals(null, why(fade, facts(a, longB)))
        assertTrue(why(fade, facts(a, longB, earliest = 20L * rate))!!.contains("before it can be ready"))
        assertEquals("a plain change", why(fade.copy(plain = true), facts(a, longB)))
        assertTrue(why(fade.copy(lanes = LaneSet.PARTS), facts(a, longB))!!.startsWith("made on parts"))
        // The intro twice plays now, as a loop of B's first two bars once in front of it.
        assertEquals(null, why(fade.copy(introTwice = 2), facts(a, longB)))
        assertTrue(why(fade.copy(bStartMs = 4_000.0), facts(a, longB))!!.startsWith("a next song starting after"))
        // Beat matched from bar 1 at 20 s: the ramp before it needs 2 s of A that are there.
        val matched = fade.copy(a = fade.a.copy(bpm = 123.0), beatMatch = true)
        assertEquals(null, why(matched, facts(a, longB)))
        assertTrue(why(matched, facts(a, longB, aBar1 = 1.0))!!.startsWith("A's tempo would have to start moving"))
    }

    @Test
    fun `an echo repeats on the beat of the song it leaves`() {
        val a = Synth.song(SynthSpec(bpm = 90.0, bars = 24, seed = 31)).samples
        val b = Synth.song(SynthSpec(bpm = 128.0, bars = 24, seed = 32)).samples
        val echoOut = every(Side.A, Move(4.0, 2.0, How.ECHO)) + every(Side.B, Move(0.0, 4.0, How.FADE))
        // A keeps its own speed at 90 BPM: three quarters of its beat, 0.5 s, not of B's 128 (0.35 s).
        val own = planned(spec(echoOut, aBpm = 90.0, bBpm = 128.0), facts(a, b))
        assertEquals((0.75 * 60.0 / 90.0 * rate).roundToInt(), own.echoFrames)
        // Matched to B, A plays at B's tempo through the change, and so does its echo.
        val matched = planned(spec(echoOut, aBpm = 126.0, bBpm = 128.0, beatMatch = true), facts(a, b))
        assertEquals((0.75 * 60.0 / 128.0 * rate).roundToInt(), matched.echoFrames)
    }
}
