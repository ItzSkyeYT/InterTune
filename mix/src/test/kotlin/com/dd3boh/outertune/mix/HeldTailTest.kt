/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * The held tail driven through a model of the sink, renderer and AudioTrack: gapless when there is
 * no plan, B on its planned frame when there is one, honest positions, no spinning, and every way
 * of breaking off falling back to the plain change.
 */
class HeldTailTest {

    private val rate = 44_100

    private val songA by lazy { Synth.song(SynthSpec(bpm = 128.0, bars = 40, seed = 1)) }
    private val songB by lazy { Synth.song(SynthSpec(bpm = 124.0, bars = 40, seed = 2)) }
    private val headB by lazy { RegionAnalysis.analyse(songB.slice(0.0, 30.0), rate, 2, RegionKind.HEAD) }

    /** One run through the model: what was heard, where the positions went, and the plan if one was taken. */
    inner class Run(
        val seed: Long = 1,
        val plan: Boolean = true,
        val attA: Double = 0.8,
        val attB: Double = 0.9,
        val capacitySeconds: Double = 48.0,
        val offerDelayTicks: Int = 0,
        val songs: List<ModelSong> = listOf(ModelSong("A", songA.samples), ModelSong("B", songB.samples)),
        val armSecondsLeft: Double = 40.0,
        val forced: Style? = null,
        val formatsMatch: Boolean = true,
        val action: (Run) -> Unit = {},
    ) {
        val held = HeldTail(2, rate, (capacitySeconds * rate).toInt()).also {
            it.setAttenuation(attA)
            it.setNextAttenuation(attB)
        }
        val model = SinkModel(held, songs, Random(seed), formatsMatch = formatsMatch)
        var armFrame = -1
        var taken: MixPlan? = null
        var rendered: RenderedOutgoing? = null
        val positions = ArrayList<kotlin.Pair<Int, Long>>()
        private var planAtTick = -1L
        private var ticks = 0L

        fun go(maxSeconds: Double = 200.0): Run {
            while (!model.ended && model.clockMs < maxSeconds * 1000) {
                control()
                action(this)
                model.step()
                ticks++
                positions += model.position()
            }
            return this
        }

        private fun control() {
            val (song, frame) = model.position()
            if (armFrame < 0 && song == 0 && frame >= songs[0].frames - armSecondsLeft * rate) {
                if (held.arm()) armFrame = model.consumedPosition()
            }
            // The worker plans the moment the end is held, and hands the plan over when it is done.
            if (plan && taken == null && held.state == HeldTail.State.DRAIN) {
                val tail = held.heldTail()!!.copyOf((held.written * 2).toInt())
                val tailAnalysis = RegionAnalysis.analyse(tail, rate, 2, RegionKind.TAIL)
                val input = PlanInput(
                    tail = tailAnalysis, head = headB, readMark = held.read, attenuationA = attA, attenuationB = attB,
                    aDurationMs = 200_000, bDurationMs = 200_000, forced = forced,
                )
                val p = Planner.plan(input)
                val r = MixRender.renderOutgoing(p, tail, tailAnalysis.guide, tailAnalysis.decimation, if (p.style == Style.BEAT) tailAnalysis.beatFrames() else DoubleArray(0))
                taken = p
                rendered = r
                planAtTick = ticks + offerDelayTicks
            }
            if (planAtTick >= 0 && ticks >= planAtTick) {
                held.offer(taken!!, rendered!!)
                planAtTick = -1
            }
        }

        /** What should have been heard, worked out offline from the same plan. */
        fun expected(): ShortArray {
            val a = songs[0].samples
            val b = songs[1].samples
            val out = ShortList()
            val p = taken
            val r = rendered
            fun plain(samples: ShortArray, from: Int, to: Int, att: Double) {
                val chunk = ShortArray((to - from) * 2) { attenuate(samples[from * 2 + it], att) }
                out.add(chunk, 0, chunk.size)
            }
            if (p == null || r == null || p.isGapless || held.note.startsWith("refused")) {
                plain(a, 0, songs[0].frames, attA)
                plain(b, 0, songs[1].frames, attB)
                return out.toArray()
            }
            plain(a, 0, armFrame + p.aFirstProcessed.toInt(), attA)
            val alone = ShortArray(((p.bStart - p.aFirstProcessed) * 2).toInt()) { r.at(p.aFirstProcessed + it / 2, it % 2) }
            out.add(alone, 0, alone.size)
            val mixed = (p.shapedUntil - p.bStart).toInt()
            val shaped = DoubleArray(mixed * 2)
            IncomingShaper(p, r.headroom).shape(b, 0, mixed, 0, shaped)
            val mix = ShortArray(mixed * 2)
            Mixer().mix(r, p.bStart, shaped, 0, mixed, mix, 0)
            out.add(mix, 0, mix.size)
            plain(b, mixed, songs[1].frames, attB)
            return out.toArray()
        }
    }

    @Test
    fun withNoPlanTheChangeIsGaplessBitForBit() {
        for (seed in 1L..3L) {
            val run = Run(seed = seed, plan = false).go()
            assertTrue("armed", run.armFrame > 0)
            assertEquals(0, run.model.stalls)
            assertArrayEquals(run.expected(), run.model.heard.toArray())
            assertEquals("no underruns", 0, run.model.underruns)
        }
    }

    @Test
    fun withAPlanTheIncomingSongStartsOnItsPlannedFrame() {
        for (seed in 1L..3L) {
            val run = Run(seed = seed).go()
            val p = run.taken!!
            println("seed $seed: ${p.describe()}; ${run.held.note}; held ${run.held.clipped} clipped")
            assertEquals(Style.BEAT, p.style)
            assertTrue(run.held.note, run.held.note.startsWith("planned"))
            assertEquals(0, run.model.stalls)
            assertEquals(0, run.model.underruns)
            // B's position starts on exactly the planned output frame.
            val bCheckpoint = run.model.checkpoints.first { it.song == 1 }
            assertEquals(run.armFrame + p.bStart, bCheckpoint.trackFrame)
            // And everything heard is what the plan says, to the bit, whatever the buffer sizes.
            assertArrayEquals(run.expected(), run.model.heard.toArray())
        }
    }

    @Test
    fun positionsNeverJumpOrRunBackwards() {
        for (plan in listOf(true, false)) {
            val run = Run(seed = 4, plan = plan).go()
            var last = run.positions.first()
            var switches = 0
            val tick = rate / 100
            for (p in run.positions.drop(1)) {
                if (p.first != last.first) {
                    switches++
                    // The incoming song's clock starts at its own start.
                    assertTrue("B starts at ${p.second}", p.second in 0..2L * tick)
                } else {
                    val step = p.second - last.second
                    assertTrue("a step of $step frames", step in 0..tick.toLong())
                }
                last = p
            }
            assertEquals(1, switches)
        }
    }

    @Test
    fun aDrainHeldForHalfAMinuteNeitherSpinsNorStarves() {
        // Armed with 45 s left and planned against a short head, the drain stays open for most
        // of the tail while the next song's first buffer waits.
        var drainFrom = -1L
        var drainTo = -1L
        val run = Run(seed = 5, armSecondsLeft = 45.0, capacitySeconds = 48.0, action = { r ->
            if (drainFrom < 0 && r.held.state == HeldTail.State.DRAIN) drainFrom = r.model.clockMs
            if (drainFrom >= 0 && drainTo < 0 && r.held.state != HeldTail.State.DRAIN) drainTo = r.model.clockMs
        }).go()
        val p = run.taken!!
        val drainSeconds = (drainTo - drainFrom) / 1000.0
        println("${p.describe()}, B at %.1f s into the tail, the drain open for %.1f s".format(p.bStart.toDouble() / rate, drainSeconds))
        assertTrue(p.bStart.toDouble() / rate > 25.0)
        assertTrue(drainSeconds > 20.0)
        assertEquals(0, run.model.stalls)
        assertEquals(0, run.model.underruns)
        assertArrayEquals(run.expected(), run.model.heard.toArray())
    }

    @Test
    fun aSeekDuringAnyPhaseDropsEverythingAndPlaysOn() {
        for (phase in HeldTail.State.entries.filter { it != HeldTail.State.PASS }) {
            var done = false
            val run = Run(seed = 6, action = { r ->
                if (!done && r.held.state == phase && (phase != HeldTail.State.MIX || r.held.incomingFrames > rate)) {
                    done = true
                    r.model.seek(1, 20 * rate)
                    // The controller hands over the song now playing's attenuation.
                    r.held.setAttenuation(0.9)
                }
            }).go()
            println("seek during $phase: ${run.held.note}")
            assertTrue("reached $phase", done)
            assertEquals(HeldTail.State.PASS, run.held.state)
            assertEquals(0, run.model.stalls)
            // After the seek, B plays plain from where it was sought to.
            val heard = run.model.heard.toArray()
            val tailOfB = ShortArray(rate * 2) { attenuate(songB.samples[(songB.frames - rate) * 2 + it], 0.9) }
            assertArrayEquals(tailOfB, heard.copyOfRange(heard.size - rate * 2, heard.size))
        }
    }

    @Test
    fun theEndOfTheQueuePlaysTheWholeTailBeforeEnding() {
        val run = Run(seed = 7, songs = listOf(ModelSong("A", songA.samples))).go()
        assertTrue(run.armFrame > 0)
        assertEquals(0, run.model.stalls)
        val expected = ShortArray(songA.samples.size) { attenuate(songA.samples[it], 0.8) }
        assertArrayEquals(expected, run.model.heard.toArray())
    }

    @Test
    fun aNextSongInAnotherFormatGetsTheWholeTailThenPlaysPlain() {
        val run = Run(seed = 14, formatsMatch = false).go()
        println(run.held.note)
        assertTrue(run.held.note.startsWith("refused"))
        assertEquals(0, run.model.stalls)
        assertArrayEquals(run.expected(), run.model.heard.toArray())
    }

    @Test
    fun aLatePlanIsRefusedAndTheChangeStaysGapless() {
        val run = Run(seed = 8, offerDelayTicks = 2_500).go()
        println(run.held.note)
        assertTrue(run.held.note.startsWith("refused"))
        assertArrayEquals(run.expected(), run.model.heard.toArray())
    }

    @Test
    fun anAbortBeforeTheStartIsGaplessAndAfterItTheOutgoingSongIsGoneWithinABeat() {
        // Before anything planned is heard.
        var early = false
        val before = Run(seed = 9, action = { r ->
            if (!early && r.taken != null && r.held.read < r.taken!!.aFirstProcessed - rate) {
                early = true
                r.held.abort("test")
            }
        }).go()
        println(before.held.note)
        assertTrue(early)
        val gapless = ShortList().apply {
            val a = ShortArray(songA.samples.size) { attenuate(songA.samples[it], 0.8) }
            val b = ShortArray(songB.samples.size) { attenuate(songB.samples[it], 0.9) }
            add(a, 0, a.size)
            add(b, 0, b.size)
        }.toArray()
        assertArrayEquals(gapless, before.model.heard.toArray())

        // In the blend: from the abort on, the outgoing song is gone within one beat, and the
        // incoming one is heard plain.
        var at = -1L
        val beat = rate / 2
        val inBlend = Run(seed = 10, action = { r ->
            if (at < 0 && r.held.state == HeldTail.State.MIX && r.held.incomingFrames > 3L * rate) {
                at = r.held.incomingFrames
                r.held.abort("test", beat.toLong())
            }
        }).go()
        println(inBlend.held.note)
        assertTrue(at > 0)
        val heard = inBlend.model.heard.toArray()
        val bFrom = inBlend.model.checkpoints.first { it.song == 1 }.trackFrame
        // A little after the fade, heard frames are B's own, plain.
        val check = (at + beat + rate / 10).toInt()
        for (i in 0 until rate) for (c in 0 until 2) {
            val b = attenuate(songB.samples[(check + i) * 2 + c], 0.9)
            assertEquals(b, heard[((bFrom + check + i) * 2 + c).toInt()])
        }
    }

    @Test
    fun aRingThatWouldOverflowFallsBackWithEverySampleInOrder() {
        val run = Run(seed = 11, capacitySeconds = 10.0).go()
        println(run.held.note)
        assertEquals(0, run.model.stalls)
        assertTrue(run.held.note.contains("memory") || run.held.note.startsWith("refused") || run.taken == null)
        val gapless = ShortList().apply {
            val a = ShortArray(songA.samples.size) { attenuate(songA.samples[it], 0.8) }
            val b = ShortArray(songB.samples.size) { attenuate(songB.samples[it], 0.9) }
            add(a, 0, a.size)
            add(b, 0, b.size)
        }.toArray()
        assertArrayEquals(gapless, run.model.heard.toArray())
    }

    @Test
    fun eachSongsAttenuationSwitchesOnItsFirstFrame() {
        val run = Run(seed = 12, plan = false, attA = 0.5, attB = 1.0).go()
        val heard = run.model.heard.toArray()
        val switchAt = songA.frames
        // The last frame of A at its attenuation, the first frame of B at its own.
        for (c in 0 until 2) {
            assertEquals(attenuate(songA.samples[(switchAt - 1) * 2 + c], 0.5), heard[(switchAt - 1) * 2 + c])
            assertEquals(songB.samples[c], heard[switchAt * 2 + c])
        }
    }

    @Test
    fun theCaptureRunsFasterThanRealTime() {
        val run = Run(seed = 13, plan = false)
        var armedAt = -1L
        var endedAt = -1L
        Run(seed = 13, plan = false, action = { r ->
            if (armedAt < 0 && r.held.state == HeldTail.State.CAPTURE) armedAt = r.model.clockMs
            if (endedAt < 0 && r.held.state == HeldTail.State.DRAIN) endedAt = r.model.clockMs
        }).go()
        val seconds = (endedAt - armedAt) / 1000.0
        println("40 s held in %.1f s".format(seconds))
        assertTrue(seconds < 10.0)
        assertTrue(abs(run.seed) >= 0)
    }
}
