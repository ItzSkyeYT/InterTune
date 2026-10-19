/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The planner's ladder and its rules, on synthetic songs analysed as the phone would: the
 * outgoing song's last 40 s as the held tail, the incoming one's first 30 s as its opening.
 */
class PlannerTest {

    private val rate = 44_100

    private fun tailOf(samples: ShortArray) = RegionAnalysis.analyse(samples, rate, 2, RegionKind.TAIL)
    private fun headOf(samples: ShortArray) = RegionAnalysis.analyse(samples, rate, 2, RegionKind.HEAD)

    private fun lastSeconds(song: SynthSong, seconds: Double = 40.0) = song.slice(song.seconds - seconds)
    private fun firstSeconds(song: SynthSong, seconds: Double = 30.0) = song.slice(0.0, seconds)

    private fun input(
        tail: RegionAnalysis,
        head: RegionAnalysis?,
        readSeconds: Double = 6.0,
        lastStyles: List<Style> = emptyList(),
        aMs: Long = 180_000,
        bMs: Long = 180_000,
        aId: String? = "a",
        bId: String? = "b",
        aAlbum: String? = "album a",
        bAlbum: String? = "album b",
    ) = PlanInput(
        tail = tail, head = head, readMark = (readSeconds * rate).toLong(), lastStyles = lastStyles,
        aDurationMs = aMs, bDurationMs = bMs, aId = aId, bId = bId, aAlbum = aAlbum, bAlbum = bAlbum,
    )

    private val tail128 by lazy { tailOf(lastSeconds(Synth.song(SynthSpec(bpm = 128.0, bars = 40, seed = 1)))) }
    private val head124 by lazy { headOf(firstSeconds(Synth.song(SynthSpec(bpm = 124.0, bars = 24, seed = 2)))) }

    @Test
    fun everyGuardGivesGapless() {
        val ok = input(tail128, head124)
        assertTrue(Planner.plan(ok).style != Style.GAPLESS)
        val cases = mapOf(
            "no opening" to input(tail128, null),
            "short A" to input(tail128, head124, aMs = 54_000),
            "short B" to input(tail128, head124, bMs = 59_999),
            "same song" to input(tail128, head124, aId = "x", bId = "x"),
            "same album" to input(tail128, head124, aAlbum = "one", bAlbum = "one"),
            "other rate" to input(tail128, RegionAnalysis.analyse(Synth.song(SynthSpec(bpm = 124.0, bars = 20, sampleRate = 48_000)).slice(0.0, 30.0), 48_000, 2, RegionKind.HEAD)),
        )
        for ((label, case) in cases) {
            val plan = Planner.plan(case)
            println("$label: ${plan.describe()}")
            assertEquals(label, Style.GAPLESS, plan.style)
            assertEquals(label, plan.tailFrames, plan.bStart)
        }
    }

    @Test
    fun aBlankAlbumIsNotAnAlbum() {
        assertTrue(Planner.plan(input(tail128, head124, aAlbum = "", bAlbum = "")).style != Style.GAPLESS)
    }

    @Test
    fun from128Into124IsABeatMixOfEightBars() {
        val plan = Planner.plan(input(tail128, head124))
        println(plan.describe())
        assertEquals(Style.BEAT, plan.style)
        assertEquals(8, plan.bars)
        assertEquals(124.0 / 128.0, plan.speed, 0.0003)
        // Eight bars at the incoming tempo.
        assertEquals(8 * 240.0 / 124.0, plan.seconds, 0.05)
    }

    @Test
    fun from128Into90IsACut() {
        val head90 = headOf(firstSeconds(Synth.song(SynthSpec(bpm = 90.0, bars = 14, groove = Groove.BACKBEAT, seed = 3))))
        val plan = Planner.plan(input(tail128, head90))
        println(plan.describe())
        assertEquals(Style.CUT, plan.style)
        assertEquals(1.0, plan.speed, 0.0)
        // One beat of the outgoing song.
        assertEquals(60.0 / 128.0, plan.seconds, 0.01)
    }

    @Test
    fun from128Into64IsABeatMixAtTheSameSpeed() {
        val head64 = headOf(firstSeconds(Synth.song(SynthSpec(bpm = 64.0, bars = 8, groove = Groove.BACKBEAT, seed = 4))))
        val plan = Planner.plan(input(tail128, head64))
        println(plan.describe() + " / head " + head64.describe())
        assertEquals(Style.BEAT, plan.style)
        assertEquals(1.0, plan.speed, 0.0005)
    }

    @Test
    fun withoutTrustedBeatsBothEndsLoudGiveSmoothAndOtherwiseBlend() {
        val noiseTail = tailOf(Synth.noise(40.0, seed = 21))
        val noiseHead = headOf(Synth.noise(30.0, seed = 22))
        assertEquals(Style.SMOOTH, Planner.plan(input(noiseTail, noiseHead)).style)
        assertEquals(Style.SMOOTH, Planner.plan(input(noiseTail, noiseHead, lastStyles = listOf(Style.BLEND, Style.BEAT, Style.SMOOTH))).style)
        assertEquals(Style.BLEND, Planner.plan(input(noiseTail, noiseHead, lastStyles = listOf(Style.SMOOTH))).style)
        assertEquals(Style.BLEND, Planner.plan(input(noiseTail, noiseHead, lastStyles = listOf(Style.BLEND, Style.SMOOTH))).style)
        // An ending that drops 12 dB for its last 15 s is not loud to the end.
        val quietEnd = Synth.noise(25.0, seed = 23) + Synth.noise(15.0, seed = 24, rmsDb = -30.0)
        val plan = Planner.plan(input(tailOf(quietEnd), noiseHead))
        println(plan.describe())
        assertEquals(Style.BLEND, plan.style)
        assertEquals(Planner.BLEND_S, plan.seconds, 0.01)
    }

    @Test
    fun aFadedEndingSetsTheBlendsLength() {
        // Noise, so no beat is trusted, pulled down by 60 dB over its last 12 s like a fader.
        val noise = Synth.noise(40.0, seed = 5)
        val fadeFrom = 28 * rate
        for (i in fadeFrom until noise.size / 2) {
            val g = Math.pow(10.0, -60.0 * (i - fadeFrom) / (12.0 * rate) / 20)
            noise[2 * i] = (noise[2 * i] * g).toInt().toShort()
            noise[2 * i + 1] = (noise[2 * i + 1] * g).toInt().toShort()
        }
        val tail = tailOf(noise)
        val plan = Planner.plan(input(tail, headOf(Synth.pad(30.0))))
        println(plan.describe() + ", fade %.1f s".format(tail.envelope.fadeFrames!!.toDouble() / rate))
        assertEquals(Style.BLEND, plan.style)
        assertEquals((tail.envelope.fadeFrames!!.toDouble() / rate).coerceIn(4.0, 10.0), plan.seconds, 0.02)
    }

    @Test
    fun aTailWithTwentySecondsOfSilenceEndsTheOverlapAtTheTrueEnd() {
        val music = Synth.noise(20.0, seed = 31)
        val tail = tailOf(music + ShortArray(20 * rate * 2))
        val plan = Planner.plan(input(tail, headOf(Synth.noise(30.0, seed = 32))))
        println(plan.describe())
        assertTrue(plan.style != Style.GAPLESS)
        assertEquals(tail.envelope.trueEnd.toDouble(), plan.overlapEnd, 1.0)
        assertTrue("the silence after it is never played", plan.aGone <= tail.envelope.trueEnd + 1)
    }

    @Test
    fun aLeadingSilenceOnTheNextSongPutsItsFirstSoundAtTheOverlapStart() {
        val head = headOf(ShortArray(3 * rate * 2) + Synth.noise(27.0, seed = 41))
        assertEquals(3.0, head.envelope.firstSound.toDouble() / rate, 0.02)
        val plan = Planner.plan(input(tailOf(Synth.noise(40.0, seed = 42)), head))
        println(plan.describe())
        assertEquals(plan.overlapStart, (plan.bStart + head.envelope.firstSound).toDouble(), 1.0)
    }

    @Test
    fun theBudgetHoldsAndNothingStartsTooEarly() {
        val tails = listOf(
            tail128,
            tailOf(lastSeconds(Synth.song(SynthSpec(bpm = 100.0, bars = 24, outroBars = 2, groove = Groove.BACKBEAT, seed = 6)))),
            tailOf(lastSeconds(Synth.song(SynthSpec(bpm = 140.0, bars = 40, fadeBars = 4, seed = 7)))),
            tailOf(Synth.noise(40.0)),
        )
        val heads = listOf(
            head124,
            headOf(firstSeconds(Synth.song(SynthSpec(bpm = 131.0, bars = 20, seed = 8)))),
            headOf(firstSeconds(Synth.song(SynthSpec(bpm = 100.0, bars = 16, groove = Groove.HALF_TIME, leadSilence = 1.0, seed = 9)))),
            headOf(Synth.pad(30.0)),
        )
        val styles = mutableSetOf<Style>()
        for (tail in tails) for (head in heads) for (read in doubleArrayOf(2.0, 8.0, 16.0, 24.0)) {
            val case = input(tail, head, readSeconds = read)
            val plan = Planner.plan(case)
            styles += plan.style
            if (plan.isGapless) continue
            assertTrue("${plan.describe()} changes at ${plan.firstChange}, before ${case.earliestChange}", plan.firstChange >= case.earliestChange)
            val overlapStartA = plan.aMap.tailAt(plan.overlapStart)
            val used = (tail.envelope.trueEnd - overlapStartA) / rate
            assertTrue(plan.describe() + " uses %.1f s".format(used), used <= Planner.BUDGET_S + 0.01)
            // Output frames run longer than the tail when the outgoing song is slowed; what must hold
            // is that it is gone by the end of what was held of it.
            val goneAt = plan.aMap.tailAt(plan.aGone.toDouble())
            assertTrue("${plan.describe()} gone at tail frame $goneAt of ${tail.frames}", goneAt <= tail.frames + 1)
            // The incoming song starts by the overlap, or within its one beat for a cut.
            assertTrue("${plan.describe()} starts B late", plan.bStart <= plan.overlapEnd)
        }
        println("styles seen: $styles")
        assertTrue("styles seen: $styles", styles.containsAll(listOf(Style.BEAT, Style.BLEND, Style.SMOOTH)))
    }

    @Test
    fun theSameInputGivesTheSamePlan() {
        val one = Planner.plan(input(tail128, head124))
        val two = Planner.plan(input(tail128, head124))
        assertEquals(one.describe(), two.describe())
        assertEquals(one.bStart, two.bStart)
        assertEquals(one.aGone, two.aGone)
        assertEquals(one.overlapStart, two.overlapStart, 0.0)
        assertTrue(one.headroom.values.contentEquals(two.headroom.values))
    }

    @Test
    fun theBeatMixLinesTheBarsUp() {
        val plan = Planner.plan(input(tail128, head124))
        // The incoming song's first downbeat is heard on the overlap's first frame, and the
        // outgoing song's downbeat there too.
        val downB = head124.beatFrame(head124.firstDownbeat!!)
        assertEquals(plan.overlapStart, plan.bStart + downB, 1.0)
        val tailFrame = plan.aMap.tailAt(plan.overlapStart)
        val beat = tail128.beatNear(tailFrame)
        assertEquals(tailFrame, tail128.beatFrame(beat), 1.0)
        assertTrue("on a downbeat", tail128.downbeats!!.isDownbeat(beat))
        // And through the overlap, beat k of the incoming song falls on beat k of the outgoing one.
        val period = head124.periodFrames!!
        for (k in 0 until plan.bars * 4) {
            val out = plan.overlapStart + k * period
            val a = plan.aMap.tailAt(out)
            val nearest = tail128.beatFrame(tail128.beatNear(a))
            assertTrue("beat $k: %.2f ms apart".format((a - nearest) / rate * 1000), abs(a - nearest) / rate < 0.002)
        }
    }

    @Test
    fun theTimeMapRunsOnWithoutAJumpAndInvertsExactly() {
        val map = TimeMap(rampStart = 10_000.0, rampLength = 80_000.0, speed = 0.96)
        var last = map.tailAt(0.0)
        for (o in 1..300_000 step 37) {
            val t = map.tailAt(o.toDouble())
            assertTrue(t > last)
            last = t
            assertEquals(o.toDouble(), map.outAt(t), 1e-6)
        }
        assertEquals(10_000.0 + 80_000.0 * 1.96 / 2, map.tailAt(90_000.0), 1e-6)
        assertEquals(0.96, map.speedAt(200_000.0), 0.0)
    }
}
