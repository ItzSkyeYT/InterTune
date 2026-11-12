/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The plain crossfade the listener sets (Planner.crossfade), on synthetic songs analysed as the
 * phone would, as PlannerTest does: the length chosen, every time, equal power through it, over
 * by the outgoing song's true end, and nothing of Auto's in it.
 */
class CrossfadeTest {

    private val rate = 44_100

    private fun tailOf(samples: ShortArray) = RegionAnalysis.analyse(samples, rate, 2, RegionKind.TAIL)
    private fun headOf(samples: ShortArray) = RegionAnalysis.analyse(samples, rate, 2, RegionKind.HEAD)

    private fun input(
        tail: RegionAnalysis,
        head: RegionAnalysis?,
        readSeconds: Double = 6.0,
        aMs: Long = 180_000,
        bMs: Long = 180_000,
        aId: String? = "a",
        bId: String? = "b",
        aAlbum: String? = "album a",
        bAlbum: String? = "album b",
    ) = PlanInput(
        tail = tail, head = head, readMark = (readSeconds * rate).toLong(),
        aDurationMs = aMs, bDurationMs = bMs, aId = aId, bId = bId, aAlbum = aAlbum, bAlbum = bAlbum,
    )

    private val noiseTail by lazy { tailOf(Synth.noise(40.0, seed = 21)) }
    private val noiseHead by lazy { headOf(Synth.noise(30.0, seed = 22)) }
    private val tail128 by lazy { tailOf(Synth.song(SynthSpec(bpm = 128.0, bars = 40, seed = 1)).let { it.slice(it.seconds - 40.0) }) }
    private val head124 by lazy { headOf(Synth.song(SynthSpec(bpm = 124.0, bars = 24, seed = 2)).slice(0.0, 30.0)) }

    @Test
    fun everyLengthOfferedIsTheLengthPlayed() {
        for (seconds in listOf(2, 4, 6, 8, 10, 12)) {
            val plan = Planner.crossfade(input(noiseTail, noiseHead), seconds.toDouble())
            println(plan.describe())
            assertEquals(Style.BLEND, plan.style)
            assertEquals(seconds.toDouble(), plan.seconds, 0.001)
            assertTrue(plan.reason, plan.reason.startsWith("crossfade $seconds s"))
        }
    }

    @Test
    fun itIsEqualPowerFromStartToEnd() {
        val plan = Planner.crossfade(input(noiseTail, noiseHead), 6.0)
        val length = plan.overlapEnd - plan.overlapStart
        for (i in 0..600) {
            val frame = plan.overlapStart + length * i / 600
            val a = plan.gainA.at(frame)
            val b = plan.gainB.at(frame)
            assertEquals("at ${i / 6.0}%", 1.0, a * a + b * b, 1e-9)
        }
        assertEquals(1.0, plan.gainA.at(plan.overlapStart), 1e-9)
        assertEquals(0.0, plan.gainB.at(plan.overlapStart), 1e-9)
        assertEquals(0.0, plan.gainA.at(plan.overlapEnd), 1e-9)
        assertEquals(1.0, plan.gainB.at(plan.overlapEnd), 1e-9)
        // Half and half, in power, at its middle.
        assertEquals(Math.sqrt(0.5), plan.gainA.at((plan.overlapStart + plan.overlapEnd) / 2), 1e-9)
    }

    @Test
    fun itEndsAtTheTrueEndWithTheNextSongsFirstSoundAtItsStart() {
        // Twenty seconds of silence after the music: never played, the crossfade over by where it ends.
        val tail = tailOf(Synth.noise(20.0, seed = 31) + ShortArray(20 * rate * 2))
        // Three seconds of silence before the next song's music: it plays before the crossfade.
        val head = headOf(ShortArray(3 * rate * 2) + Synth.noise(27.0, seed = 41))
        val plan = Planner.crossfade(input(tail, head), 6.0)
        println(plan.describe())
        assertEquals(6.0, plan.seconds, 0.001)
        assertEquals(tail.envelope.trueEnd.toDouble(), plan.overlapEnd, 1.0)
        assertTrue("the silence after it is never played", plan.aGone <= tail.envelope.trueEnd + 1)
        assertEquals(plan.overlapStart, (plan.bStart + head.envelope.firstSound).toDouble(), 1.0)
    }

    @Test
    fun twoSteadyBeatsKeepTheWholeLengthAndNothingOfAutosIsInIt() {
        // Auto blends these two over a beat at most, so two pulses never slide apart; the
        // listener's crossfade is the length chosen all the same, straight, with no filter.
        assertTrue(Planner.planStyle(input(tail128, head124), Style.BLEND)!!.seconds <= 60.0 / 124 + 0.01)
        val plan = Planner.crossfade(input(tail128, head124), 8.0)
        println(plan.describe())
        assertEquals(Style.BLEND, plan.style)
        assertEquals(8.0, plan.seconds, 0.001)
        assertTrue(plan.aMap.isIdentity)
        assertEquals(1.0, plan.speed, 0.0)
        assertTrue(plan.filtersA.isEmpty() && plan.filtersB.isEmpty())
        assertEquals(0, plan.bars)
        assertEquals(0, plan.beats)
        assertEquals(0L, plan.lockTo)
    }

    @Test
    fun aFadedEndingDoesNotSetItsLength() {
        // Auto's blend follows an ending's own fade, four seconds at the least; the crossfade keeps
        // the length chosen.
        val noise = Synth.noise(40.0, seed = 5)
        val fadeFrom = 28 * rate
        for (i in fadeFrom until noise.size / 2) {
            val g = Math.pow(10.0, -60.0 * (i - fadeFrom) / (12.0 * rate) / 20)
            noise[2 * i] = (noise[2 * i] * g).toInt().toShort()
            noise[2 * i + 1] = (noise[2 * i + 1] * g).toInt().toShort()
        }
        val tail = tailOf(noise)
        val auto = Planner.plan(input(tail, headOf(Synth.pad(30.0))))
        val plan = Planner.crossfade(input(tail, headOf(Synth.pad(30.0))), 2.0)
        println("Auto ${auto.describe()}; ${plan.describe()}")
        assertEquals(Style.BLEND, auto.style)
        assertTrue(auto.seconds >= Planner.BLEND_MIN_S - 0.001)
        assertEquals(2.0, plan.seconds, 0.001)
    }

    @Test
    fun itIsShorterOnlyWhereTooLittleIsLeftAndNeverUnderTwoSeconds() {
        // A tail of 40 s of music: the change may start no earlier than a second after what was
        // already played, and the outgoing song's level only dips 0.4 s after that.
        val trueEnd = noiseTail.envelope.trueEnd.toDouble() / rate
        val late = Planner.crossfade(input(noiseTail, noiseHead, readSeconds = trueEnd - 5.0), 6.0)
        println(late.describe())
        assertEquals(Style.BLEND, late.style)
        assertEquals(3.6, late.seconds, 0.01)
        assertTrue(late.reason, late.reason.startsWith("crossfade 6 s, 3.6 s of it"))
        assertTrue(late.firstChange >= ((trueEnd - 5.0 + 1.0) * rate).toLong() - 1)
        val tooLate = Planner.crossfade(input(noiseTail, noiseHead, readSeconds = trueEnd - 3.3), 6.0)
        println(tooLate.describe())
        assertEquals(Style.GAPLESS, tooLate.style)
        assertEquals(tooLate.tailFrames, tooLate.bStart)
        // Two seconds chosen and two seconds left is still a crossfade.
        val shortest = Planner.crossfade(input(noiseTail, noiseHead, readSeconds = trueEnd - 3.45), 2.0)
        assertEquals(Style.BLEND, shortest.style)
        assertEquals(2.0, shortest.seconds, 0.001)
    }

    @Test
    fun everyGuardGivesGapless() {
        val cases = mapOf(
            "no opening" to input(noiseTail, null),
            "short A" to input(noiseTail, noiseHead, aMs = 54_000),
            "short B" to input(noiseTail, noiseHead, bMs = 59_999),
            "same song" to input(noiseTail, noiseHead, aId = "x", bId = "x"),
            "same album" to input(noiseTail, noiseHead, aAlbum = "one", bAlbum = "one"),
            "other rate" to input(noiseTail, RegionAnalysis.analyse(Synth.noise(30.0, sampleRate = 48_000), 48_000, 2, RegionKind.HEAD)),
        )
        for ((label, case) in cases) {
            val plan = Planner.crossfade(case, 6.0)
            println("$label: ${plan.describe()}")
            assertEquals(label, Style.GAPLESS, plan.style)
            assertEquals(label, plan.tailFrames, plan.bStart)
        }
    }

    @Test
    fun nothingChangesBeforeThePlanCanBeInPlace() {
        for (read in doubleArrayOf(2.0, 8.0, 16.0, 24.0, 30.0)) for (seconds in listOf(2, 6, 12)) {
            val case = input(noiseTail, headOf(ShortArray(rate * 2) + Synth.noise(29.0, seed = 9)), readSeconds = read)
            val plan = Planner.crossfade(case, seconds.toDouble())
            if (plan.isGapless) continue
            assertTrue("${plan.describe()} changes at ${plan.firstChange}, before ${case.earliestChange}", plan.firstChange >= case.earliestChange)
            assertTrue(plan.describe(), plan.bStart <= plan.overlapStart)
        }
    }
}
