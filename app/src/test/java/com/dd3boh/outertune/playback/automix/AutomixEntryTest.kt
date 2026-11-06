/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.HeldTail
import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.ChangeFacts
import com.dd3boh.outertune.mix.engine.ChangeRender
import com.dd3boh.outertune.mix.engine.LaneMixer
import com.dd3boh.outertune.mix.engine.SpecPlan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * A change far from the end through the stage, its EntrySkip in front of media3's sink as
 * AutomixAudioSink has it, and the 1.8.0 sink's drains and syncs (SinkDriver): the next song's
 * frames before its entry dropped as decoded, the held end playing on meanwhile, the change heard
 * as rendered to the bit, the position counting the next song from its entry, nothing spinning
 * and nothing running dry.
 */
class AutomixEntryTest {

    private val rate = 44_100

    private fun song(seconds: Double, hz: Double, seed: Int): ShortArray {
        val random = Random(seed)
        val frames = (seconds * rate).toInt()
        return ShortArray(frames * 2) { i ->
            val t = (i / 2).toDouble() / rate
            val v = 0.3 * sin(2 * PI * hz * t + (i % 2) * 0.3) * (0.8 + 0.2 * sin(2 * PI * 0.25 * t)) + 0.01 * (random.nextDouble() - 0.5)
            (v * 32767).toInt().toShort()
        }
    }

    private val songA by lazy { song(160.0, 220.0, 1) }
    private val songB by lazy { song(90.0, 330.0, 2) }

    @Test
    fun `the next song comes in at its entry bar, 30 s into it, through the sink as the phone has it`() {
        for (seed in 1L..2L) {
            val entry = 30L * rate
            val notes = ArrayList<String>()
            var clock = 0L
            val stage = AutomixAudioProcessor(log = { notes += it }, nanoTime = { clock }).also { it.wanted = true }
            var held: HeldTail? = null
            var tailFrames = 0L
            var change: ChangeRender? = null
            var tail: ShortArray? = null
            stage.levels = AutomixAudioProcessor.Levels(AutomixAudioProcessor.Level("a", 1.0), AutomixAudioProcessor.Level("b", 1.0))
            stage.events = object : AutomixAudioProcessor.Events {
                override fun armed(id: Long, sampleRate: Int, channels: Int, tailStartUs: Long, positionUs: Long) = Unit
                override fun tailHeld(id: Long, held0: HeldTail, ring: ShortArray, frames: Long, songChange: Boolean, formatMatches: Boolean) {
                    held = held0
                    tailFrames = frames
                    val t = ring.copyOf((frames * 2).toInt())
                    tail = t
                    // The box: A cut on its line 55 s before its end, B in 8 bars of 120 BPM before it at its frame E.
                    val aFrames = songA.size / 2
                    val line = aFrames - 55.0 * rate
                    val aBar1 = line - 8 * 2.0 * rate - (aFrames - frames)
                    val moves = LaneSet.BANDS.lanes.associate { LaneId(Side.A, it) to Move(8.0, 0.0, How.CUT) } +
                        mapOf(
                            LaneId(Side.B, Lane.LOWS) to Move(4.0, 0.0, How.CUT),
                            LaneId(Side.B, Lane.MIDS) to Move(0.0, 0.0, How.FROM_START),
                            LaneId(Side.B, Lane.HIGHS) to Move(0.0, 0.0, How.FROM_START),
                        )
                    val bMs = entry * 1000.0 / rate
                    val spec = TransitionSpec(
                        a = Anchor("a", 1.0, 120.0), b = Anchor("b", bMs, 120.0), lanes = LaneSet.BANDS, bars = 8,
                        beatMatch = false, preset = Preset.CUSTOM, moves = moves, bStartMs = bMs,
                    )
                    val facts = ChangeFacts(rate, 2, aBar1, frames, bMs * rate / 1000, (songB.size / 2).toLong(), AutomixSchedule.earliestChange(stage.readMark, rate, 2_000))
                    val lp = (SpecPlan.plan(spec, facts) as SpecPlan.Result.Planned).plan
                    change = LaneMixer(lp).render(t, songB, 1.0, 1.0)
                }
                override fun note(id: Long, text: String) {
                    notes += text
                }
                override fun songChanged(id: Long, mixing: Boolean) = Unit
                override fun blendDone(id: Long, clipped: Long, peak: Int) = Unit
                override fun lockFrames(id: Long, frames: ShortArray, fromFrame: Long) = Unit
                override fun activeChanged(active: Boolean) = Unit
                override fun dropped(id: Long) = Unit
            }
            val driver = SinkDriver(stage, listOf(songA, songB), Random(seed), rate, skipsEntries = true)
            var armed = false
            var offered = false
            while (!driver.ended && driver.clockMs < 300_000) {
                val (song, frame) = driver.position()
                if (!armed && song == 0 && frame >= songA.size / 2 - 99 * rate) {
                    armed = true
                    stage.requestArm(AutomixAudioProcessor.Arm(1, ShortArray(AutomixSchedule.ringFrames(rate, 98_000) * 2), rate, 2, clock, entryFrames = entry))
                }
                change?.takeIf { !offered }?.let { c ->
                    stage.offer(held!!, c.heldPlan(), c.outgoing, c.incoming)
                    offered = true
                }
                if (song == 1 && stage.levels.now.id == "a") {
                    stage.levels = AutomixAudioProcessor.Levels(AutomixAudioProcessor.Level("b", 1.0), AutomixAudioProcessor.Level(null, 1.0))
                }
                driver.step()
                clock = driver.clockMs * 1_000_000L
            }
            val c = change!!
            val lp = c.plan
            println("seed $seed: ${notes.filter { it.startsWith("planned") || it.contains("skipped") || it.startsWith("let go") || it.startsWith("refused") }}")
            assertTrue(notes.toString(), notes.any { it.startsWith("planned") })
            assertTrue(notes.toString(), notes.any { it.startsWith("the next song's first 30.0 s skipped in") })
            // What was heard: A as it is, the change as rendered, then B from where the change leaves it.
            val armFrame = (songA.size / 2 - tailFrames).toInt()
            val out = ShortList()
            out.add(songA, 0, (armFrame + lp.mixFrom.toInt()) * 2)
            val mixed = c.mix(tail!!, songB, lp.mixFrom, lp.mixTo)
            out.add(mixed, 0, mixed.size)
            val from = (lp.mixTo - lp.bOrigin).toInt()
            out.add(songB, from * 2, songB.size - from * 2)
            assertArrayEquals(out.toArray(), driver.heard.toArray())
            // The sink counts B from its entry, on the frame the plan put it on.
            val cp = driver.checkpoints.first { it.song == 1 }
            assertEquals(armFrame + lp.bStart, cp.trackFrame)
            assertEquals(entry, cp.songFrame)
            assertEquals(0, driver.stalls)
            assertEquals(0, driver.underruns)
        }
    }
}
