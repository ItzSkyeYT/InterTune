/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.HeldTail
import com.dd3boh.outertune.mix.IncomingShaper
import com.dd3boh.outertune.mix.Mixer
import com.dd3boh.outertune.mix.MixPlan
import com.dd3boh.outertune.mix.MixRender
import com.dd3boh.outertune.mix.PlanInput
import com.dd3boh.outertune.mix.Planner
import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.RenderedOutgoing
import com.dd3boh.outertune.mix.Style
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * AutoMix's stage in media3's own pipeline, driven as the 1.8.0 sink drives it (SinkDriver): out of
 * the chain with AutoMix off, each song at its own level with it on, today's change sample for
 * sample when the end is held without a plan, the plan's blend to the sample when there is one,
 * the sink's second drain at the start of the next song survived, and every way of breaking off
 * falling back to the plain change. No call ever spins.
 */
class AutomixAudioProcessorTest {

    private val rate = 44_100

    /** A tone with a slow swell and a little noise: a first sound at once and a true end at the end, which is all a blend needs. */
    private fun song(seconds: Double, hz: Double, seed: Int): ShortArray {
        val random = Random(seed)
        val frames = (seconds * rate).toInt()
        return ShortArray(frames * 2) { i ->
            val t = (i / 2).toDouble() / rate
            val v = 0.3 * sin(2 * PI * hz * t + (i % 2) * 0.3) * (0.8 + 0.2 * sin(2 * PI * 0.25 * t)) + 0.01 * (random.nextDouble() - 0.5)
            (v * 32767).toInt().toShort()
        }
    }

    private val songA by lazy { song(70.0, 220.0, 1) }
    private val songB by lazy { song(40.0, 330.0, 2) }
    private val headB by lazy { RegionAnalysis.analyse(songB.copyOfRange(0, 30 * rate * 2), rate, 2, RegionKind.HEAD) }

    /** One run: the stage, the sink around it, and what the controller would do, done at once on this thread. */
    inner class Run(
        val wanted: Boolean = true,
        val armSecondsLeft: Double? = 40.0,
        val style: Style? = Style.BLEND,
        /** Ticks between the end being held and the plan being offered: the worker's time. */
        val planAfterTicks: Int = 50,
        val reportsNewStream: Boolean = true,
        val levelA: Double = 1.0,
        val levelB: Double = 1.0,
        val seed: Long = 1,
        val songs: List<ShortArray> = listOf(songA, songB),
        val during: (Run) -> Unit = {},
    ) {
        var clockNanos = 0L
        val notes = ArrayList<String>()
        val stage = AutomixAudioProcessor(log = { notes += it }, nanoTime = { clockNanos }).also { it.wanted = wanted }
        val driver: SinkDriver
        var armRequested = false
        var armed = false
        var held: HeldTail? = null
        var tailFrames = 0L
        var readAtTail = 0L
        var plan: MixPlan? = null
        var rendered: RenderedOutgoing? = null
        private var offerAtTick = -1L
        private var ticks = 0L
        var songChanges = ArrayList<Boolean>()
        var blendsDone = 0
        val actives = ArrayList<Boolean>()
        val positions = ArrayList<Pair<Int, Long>>()

        init {
            stage.levels = AutomixAudioProcessor.Levels(AutomixAudioProcessor.Level("a", levelA), AutomixAudioProcessor.Level("b", levelB))
            stage.events = object : AutomixAudioProcessor.Events {
                override fun armed(id: Long, sampleRate: Int, channels: Int, tailStartUs: Long, positionUs: Long) {
                    armed = true
                }

                override fun tailHeld(id: Long, held: HeldTail, ring: ShortArray, frames: Long, songChange: Boolean, formatMatches: Boolean) {
                    this@Run.held = held
                    tailFrames = frames
                    readAtTail = stage.readMark
                    if (style == null || !songChange || !formatMatches) return
                    val tail = RegionAnalysis.analyse(held.heldTail()!!.copyOf((frames * 2).toInt()), rate, 2, RegionKind.TAIL)
                    val p = Planner.plan(
                        PlanInput(
                            tail = tail, head = headB, readMark = stage.readMark,
                            earliestChange = AutomixSchedule.earliestChange(stage.readMark, rate, 2_000),
                            attenuationA = levelA, attenuationB = levelB,
                            aDurationMs = 100_000, bDurationMs = 100_000, aId = "a", bId = "b", forced = style,
                        )
                    )
                    plan = p
                    rendered = MixRender.renderOutgoing(p, held.heldTail()!!, tail.guide, tail.decimation)
                    offerAtTick = ticks + planAfterTicks
                }

                override fun note(id: Long, text: String) {
                    notes += text
                }

                override fun songChanged(id: Long, mixing: Boolean) {
                    songChanges += mixing
                }

                override fun blendDone(id: Long, clipped: Long, peak: Int) {
                    blendsDone++
                }

                override fun lockFrames(id: Long, frames: ShortArray, fromFrame: Long) = Unit

                override fun activeChanged(active: Boolean) {
                    actives += active
                }
            }
            driver = SinkDriver(stage, songs, Random(seed), rate, reportsNewStream = reportsNewStream)
        }

        fun go(maxSeconds: Double = 200.0): Run {
            while (!driver.ended && driver.clockMs < maxSeconds * 1000) {
                control()
                during(this)
                driver.step()
                clockNanos = driver.clockMs * 1_000_000L
                ticks++
                positions += driver.position()
            }
            return this
        }

        private fun control() {
            val (song, frame) = driver.position()
            val aFrames = songs[0].size / 2
            val left = armSecondsLeft
            if (!armRequested && left != null && song == 0 && frame >= aFrames - left * rate) {
                armRequested = true
                stage.requestArm(AutomixAudioProcessor.Arm(1, ShortArray(AutomixSchedule.capacityFrames(rate) * 2), rate, 2, clockNanos))
            }
            if (offerAtTick >= 0 && ticks >= offerAtTick) {
                stage.offer(held!!, plan!!, rendered!!)
                offerAtTick = -1
            }
            // The player names the next song once it is heard; till then the stage holds its level for it.
            if (song == 1 && stage.levels.now.id == "a") {
                stage.levels = AutomixAudioProcessor.Levels(AutomixAudioProcessor.Level("b", levelB), AutomixAudioProcessor.Level(null, 1.0))
            }
        }

        val taken: Boolean get() = notes.any { it.startsWith("planned") }

        /** What should have been heard, worked out from the songs and the plan. */
        fun expected(): ShortArray {
            val a = songs[0]
            val b = songs[1]
            val out = ShortList()
            fun plain(samples: ShortArray, from: Int, to: Int, level: Double) {
                val chunk = ShortArray((to - from) * 2) { AutomixAudioProcessor.scale(samples[from * 2 + it], level) }
                out.add(chunk, 0, chunk.size)
            }
            val p = plan
            val r = rendered
            if (p == null || r == null || p.isGapless || !taken) {
                plain(a, 0, a.size / 2, levelA)
                plain(b, 0, b.size / 2, levelB)
                return out.toArray()
            }
            val armFrame = (a.size / 2 - tailFrames).toInt()
            plain(a, 0, armFrame + p.aFirstProcessed.toInt(), levelA)
            val alone = ShortArray(((p.bStart - p.aFirstProcessed) * 2).toInt()) { r.at(p.aFirstProcessed + it / 2, it % 2) }
            out.add(alone, 0, alone.size)
            val mixed = (p.shapedUntil - p.bStart).toInt()
            val shaped = DoubleArray(mixed * 2)
            IncomingShaper(p, r.headroom).shape(b, 0, mixed, 0, shaped)
            val mix = ShortArray(mixed * 2)
            Mixer().mix(r, p.bStart, shaped, 0, mixed, mix, 0)
            out.add(mix, 0, mix.size)
            plain(b, mixed, b.size / 2, levelB)
            return out.toArray()
        }

        /** The first sample where what was heard and what should have been differ, or -1. */
        fun firstDifference(): Int {
            val heard = driver.heard.toArray()
            val want = expected()
            for (i in 0 until minOf(heard.size, want.size)) if (heard[i] != want[i]) return i
            return if (heard.size == want.size) -1 else minOf(heard.size, want.size)
        }

        /** Whether each song's position only ever ran forward, and the songs came in order. */
        fun positionsRunForward(): Boolean {
            for (i in 1 until positions.size) {
                val (s0, f0) = positions[i - 1]
                val (s1, f1) = positions[i]
                if (s1 < s0 || (s1 == s0 && f1 < f0)) return false
            }
            return true
        }
    }

    @Test
    fun `with AutoMix off the stage stays out of the chain, and the songs play as they are`() {
        val run = Run(wanted = false, armSecondsLeft = 40.0).go()
        assertFalse(run.stage.activeForStream)
        assertFalse("nothing was held", run.armed)
        assertEquals(-1, run.firstDifference())
        assertEquals(0, run.driver.stalls)
    }

    @Test
    fun `with AutoMix on and nothing held, each song plays at its own level from its first frame`() {
        val run = Run(armSecondsLeft = null, levelA = 0.8, levelB = 0.5).go()
        assertTrue(run.stage.activeForStream)
        assertEquals("the next song took its own level before the player named it", -1, run.firstDifference())
        assertEquals(0, run.driver.stalls)
    }

    @Test
    fun `an end held without a plan is the change of today, sample for sample`() {
        val run = Run(style = null).go()
        assertTrue(run.armed)
        assertEquals(-1, run.firstDifference())
        assertEquals(0, run.driver.stalls)
        assertEquals(0, run.driver.underruns)
        // The end was taken in far faster than it was played: most of it was still to be heard.
        assertTrue("%d of %d frames out as the end was all held".format(run.readAtTail, run.tailFrames), run.readAtTail < run.tailFrames / 2)
        assertEquals(listOf(false), run.songChanges)
        assertTrue(run.positionsRunForward())
    }

    @Test
    fun `a blend comes out as planned, to the sample, through the drain the sink makes as the next song starts`() {
        val run = Run(levelA = 0.9, levelB = 0.7).go()
        assertTrue(run.notes.toString(), run.taken)
        assertEquals(Style.BLEND, run.plan!!.style)
        assertEquals(listOf(true), run.songChanges)
        assertEquals("the blend ran to its end", 1, run.blendsDone)
        assertEquals(-1, run.firstDifference())
        assertEquals(0, run.driver.stalls)
        assertEquals(0, run.driver.underruns)
        assertTrue(run.positionsRunForward())
    }

    @Test
    fun `and the same without that drain`() {
        val run = Run(reportsNewStream = false).go()
        assertTrue(run.taken)
        assertEquals(1, run.blendsDone)
        assertEquals(-1, run.firstDifference())
        assertEquals(0, run.driver.stalls)
    }

    @Test
    fun `the smooth blend with its filters comes out as planned too`() {
        val run = Run(style = Style.SMOOTH).go()
        assertTrue(run.notes.toString(), run.taken)
        assertEquals(Style.SMOOTH, run.plan!!.style)
        assertEquals(-1, run.firstDifference())
    }

    @Test
    fun `a plan that comes too late is refused, and the change is today's`() {
        // About seven seconds of work: by then the next song would have had to start already.
        val run = Run(planAfterTicks = 3_000).go()
        assertFalse(run.taken)
        assertTrue(run.notes.toString(), run.notes.any { it.startsWith("refused") })
        assertEquals(-1, run.firstDifference())
    }

    @Test
    fun `a seek while the end is held drops it, and the song plays on from where the seek went`() {
        var sought = -1
        var seekAtSample = -1
        val run = Run(during = { r ->
            if (sought < 0 && r.armed && r.stage.state == HeldTail.State.CAPTURE && r.driver.clockMs > 0) {
                val (_, frame) = r.driver.position()
                sought = frame.toInt() - rate * 5
                seekAtSample = r.driver.heard.size
                r.driver.seek(0, sought)
            }
        }).go()
        assertTrue(sought > 0)
        assertEquals(HeldTail.State.PASS, run.stage.state)
        val heard = run.driver.heard.toArray()
        // From the seek on, the song from where it went, then the next one, untouched.
        for (i in 0 until rate * 2 * 2) assertEquals("sample $i after the seek", songA[sought * 2 + i], heard[seekAtSample + i])
        assertTrue(run.notes.toString(), run.notes.any { it.startsWith("dropped") })
        assertEquals(0, run.driver.stalls)
    }

    @Test
    fun `an abort before anything planned is heard is the change of today`() {
        var aborted = false
        val run = Run(during = { r ->
            if (!aborted && r.taken) {
                r.stage.requestAbort(1, "testing", rate / 2L)
                aborted = true
            }
        }).go()
        assertTrue(aborted)
        assertTrue(run.notes.toString(), run.notes.any { it.startsWith("aborted before it was heard") })
        assertEquals(listOf(false), run.songChanges)
        val heard = run.driver.heard.toArray()
        val plain = songA + songB
        assertEquals(plain.size, heard.size)
        for (i in plain.indices) if (plain[i] != heard[i]) throw AssertionError("sample $i differs")
    }

    @Test
    fun `an end too long for the ring lets the rest through, every sample in order`() {
        // Asked to hold sixty seconds where there is room for forty-eight.
        val run = Run(armSecondsLeft = 60.0, style = null).go()
        assertTrue(run.armed)
        assertTrue(run.notes.toString(), run.notes.any { it.contains("did not fit") })
        val heard = run.driver.heard.toArray()
        val plain = songA + songB
        assertEquals(plain.size, heard.size)
        for (i in plain.indices) if (plain[i] != heard[i]) throw AssertionError("sample $i differs")
        assertEquals(0, run.driver.stalls)
    }

    @Test
    fun `an end that begins to be held with the track run dry plays again at once, not after the whole end`() {
        val aFrames = songA.size / 2
        var phase = 0
        var unpausedAt = -1L
        var underrunsAtUnpause = 0
        val run = Run(armSecondsLeft = null, style = null, during = { r ->
            val (song, frame) = r.driver.position()
            when (phase) {
                // The decoder starves for a moment just before the window, and the track runs dry.
                0 -> if (song == 0 && frame >= aFrames - 41 * rate) {
                    r.driver.renderPaused = true
                    phase = 1
                }
                1 -> if (r.driver.written == r.driver.played) {
                    r.stage.requestArm(AutomixAudioProcessor.Arm(1, ShortArray(AutomixSchedule.capacityFrames(rate) * 2), rate, 2, r.clockNanos))
                    r.driver.renderPaused = false
                    unpausedAt = r.driver.clockMs
                    underrunsAtUnpause = r.driver.underruns
                    phase = 2
                }
            }
        }).go()
        assertTrue(unpausedAt > 0)
        assertTrue(run.armed)
        assertTrue("the whole end was held", run.tailFrames > 0)
        assertEquals(-1, run.firstDifference())
        assertEquals(0, run.driver.stalls)
        // A fifth of a second for the stage to see the output has stopped, then it plays on.
        assertTrue("underruns after the end began to be held: ${run.driver.underruns - underrunsAtUnpause}", run.driver.underruns - underrunsAtUnpause <= 25)
    }

    @Test
    fun `a pause while the end is held goes on taking it in, and plays on from where it was`() {
        var pausedAt = -1L
        var readAtPause = 0L
        var readAfterPause = 0L
        var heldDuringPause = false
        val run = Run(style = null, during = { r ->
            if (pausedAt < 0 && r.armed && r.stage.state == HeldTail.State.CAPTURE) {
                pausedAt = r.driver.clockMs
                r.driver.trackPlaying = false
                readAtPause = r.stage.readMark
            } else if (pausedAt >= 0 && !r.driver.trackPlaying && r.driver.clockMs >= pausedAt + 6_000) {
                readAfterPause = r.stage.readMark
                heldDuringPause = r.tailFrames > 0
                r.driver.trackPlaying = true
            }
        }).go()
        assertTrue(pausedAt >= 0)
        assertTrue("the end was taken in while paused", heldDuringPause)
        // Nothing but what the paused track had room for, and once the end was all held, one chunk of
        // the drain waiting for it.
        assertTrue("${readAfterPause - readAtPause} frames went out while paused", readAfterPause - readAtPause <= AutomixAudioProcessor.OUT_MAX + 2048)
        assertEquals(-1, run.firstDifference())
        assertEquals(0, run.driver.stalls)
    }

    @Test
    fun `a request to hold the end that waited too long is not taken`() {
        val stage = AutomixAudioProcessor(log = {}, nanoTime = { 5_000_000_000L })
        stage.wanted = true
        val notes = ArrayList<String>()
        stage.events = object : AutomixAudioProcessor.Events {
            override fun armed(id: Long, sampleRate: Int, channels: Int, tailStartUs: Long, positionUs: Long) = throw AssertionError("armed")
            override fun tailHeld(id: Long, held: HeldTail, ring: ShortArray, frames: Long, songChange: Boolean, formatMatches: Boolean) = Unit
            override fun note(id: Long, text: String) { notes += text }
            override fun songChanged(id: Long, mixing: Boolean) = Unit
            override fun blendDone(id: Long, clipped: Long, peak: Int) = Unit
            override fun lockFrames(id: Long, frames: ShortArray, fromFrame: Long) = Unit
            override fun activeChanged(active: Boolean) = Unit
        }
        val driver = SinkDriver(stage, listOf(song(5.0, 220.0, 3)), Random(1), rate)
        repeat(10) { driver.step() }
        stage.requestArm(AutomixAudioProcessor.Arm(1, ShortArray(AutomixSchedule.capacityFrames(rate) * 2), rate, 2, atNanos = 0))
        repeat(10) { driver.step() }
        assertTrue(notes.toString(), notes.any { it.contains("too long ago") })
        assertEquals(HeldTail.State.PASS, stage.state)
    }
}
