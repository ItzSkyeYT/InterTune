/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.ChangeSum
import com.dd3boh.outertune.mix.HeldTail
import com.dd3boh.outertune.mix.edit.Anchor
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Move
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.TransitionSpec
import com.dd3boh.outertune.mix.engine.ChangeFacts
import com.dd3boh.outertune.mix.engine.LaneMixer
import com.dd3boh.outertune.mix.engine.SpecPlan
import com.dd3boh.outertune.mix.engine.StoredChange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Jumps through a song whose change is kept as rendered, through the stage, its EntrySkip and the
 * 1.8.0 sink (SinkDriver), the controller's part done here as it does it (his word of 8 Nov 2026:
 * "that's unacceptable that it drops the transition just cuz i jumped through the song"). A jump
 * close before the change plays it whole; a jump into it plays A as it is for the moment the end
 * takes to hold, then the change from there, B coming in where it would have been; a run of jumps
 * leaves nothing behind (a late ring or plan for an end held before is never taken), and B comes
 * in once.
 */
class AutomixJumpTest {

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

    private val songA by lazy { song(80.0, 220.0, 1) }
    private val songB by lazy { song(60.0, 330.0, 2) }
    private val aFrames get() = songA.size / 2

    /** A fades out over six bars from A's 50 s; B comes in at its own 9 s, four bars in, and fades in over two. */
    private val spec = TransitionSpec(
        a = Anchor("a", 50_000.0, 120.0), b = Anchor("b", 1_000.0, 120.0), lanes = LaneSet.BANDS,
        bars = 8, beatMatch = false, preset = Preset.CUSTOM,
        moves = LaneSet.BANDS.lanes.associate { LaneId(Side.A, it) to Move(0.0, 6.0, How.FADE) } +
            LaneSet.BANDS.lanes.associate { LaneId(Side.B, it) to Move(4.0, 2.0, How.FADE) },
        bStartMs = 9_000.0,
    )
    private val written = "the change as saved"

    /** The change as the editor renders and keeps it, from A's whole and B's opening. */
    private val stored: StoredChange by lazy {
        val facts = ChangeFacts(rate, 2, 50.0 * rate, aFrames.toLong(), 1.0 * rate, (songB.size / 2).toLong())
        val plan = (SpecPlan.plan(spec, facts) as SpecPlan.Result.Planned).plan
        StoredChange.of(written, LaneMixer(plan).render(songA, songB, 1.0, 1.0), songA, 0.0, songB)
    }

    /** The jumps: to A's frame [to] once [after] says so, and whether a ring and a plan made for the end before arrive late. */
    class Jump(val to: Long, val after: (Run) -> Boolean, val staleAfter: Boolean = false, val song: Int = 0)

    inner class Run(
        private val jumps: List<Jump>,
        private val clockErrorMs: Double = 350.0,
        private val skipSpeed: Double = 8.0,
        /** B as it plays, where it differs from its copy: a decoder started again at a jump. */
        private val liveB: ShortArray = songB,
    ) {
        var clock = 0L
        val notes = ArrayList<String>()
        val stage = AutomixAudioProcessor(log = { notes += it }, nanoTime = { clock }).also { it.wanted = true }
        val driver: SinkDriver
        var id = 0L
        var next = 0
        var seekAt = -1
        var seekClock = -1L
        var armWanted = false
        var held: HeldTail? = null
        var ring: ShortArray? = null
        var tailFrames = 0L
        var play: StoredChange.KeptPlay? = null
        var offered = false
        var heldWhenOffered = false
        private var plannedBefore = 0
        private fun planned() = notes.count { it.startsWith("planned") }

        /** A play and a ring made for the end held before the last jump, handed over late. */
        private var stalePlay: StoredChange.KeptPlay? = null
        private var staleRing: ShortArray? = null
        private var staleHeld: HeldTail? = null
        var staleArmSent = false
        var staleOfferSent = false

        init {
            stage.levels = AutomixAudioProcessor.Levels(AutomixAudioProcessor.Level("a", 1.0), AutomixAudioProcessor.Level("b", 1.0))
            stage.events = object : AutomixAudioProcessor.Events {
                override fun armed(id: Long, sampleRate: Int, channels: Int, tailStartUs: Long, positionUs: Long) = Unit
                override fun tailHeld(id: Long, held: HeldTail, ring: ShortArray, frames: Long, songChange: Boolean, formatMatches: Boolean) {
                    if (id != this@Run.id) return
                    this@Run.held = held
                    this@Run.ring = ring
                    tailFrames = frames
                    // As the controller's worker does it (playKept): found by the clock, a little out.
                    val readMark = stage.readMark
                    val startMs = (aFrames - frames) * 1000.0 / rate + clockErrorMs
                    play = stored.replayFrom(
                        written, spec, ring, frames, startMs, songB, songB.size / 2,
                        AutomixSchedule.earliestChange(readMark, rate, AutomixSchedule.KEPT_WORK_MS), 1.0, 1.0,
                        StoredChange.EntryRule.Open(readMark + AutomixSchedule.KEPT_WORK_MS * rate / 1000, skipSpeed, AutomixSchedule.ENTRY_SKIP_MARGIN_MS * rate / 1000),
                    ).onFailure { notes += "not as kept: ${it.message}" }.getOrNull()
                    offered = false
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
            driver = SinkDriver(stage, listOf(songA, liveB), Random(7), rate, skipsEntries = true)
        }

        fun go(): Run {
            while (!driver.ended && driver.clockMs < 200_000) {
                val j = jumps.getOrNull(next)
                if (j != null && j.after(this)) {
                    // The controller on a jump: the change before it given up, its late ring never taken.
                    if (j.staleAfter) {
                        stalePlay = play
                        staleRing = ring
                        staleHeld = held
                    }
                    stage.cancelArm()
                    stage.withdrawArmsBefore(id + 1)
                    seekAt = driver.heard.size
                    driver.seek(j.song, j.to.toInt())
                    seekClock = driver.clockMs
                    next++
                    if (j.song == 0) {
                        id++
                        armWanted = true
                        play = null
                        held = null
                        ring = null
                        offered = false
                    }
                }
                // The worker's ring for the end before, handed over after the jump: never taken.
                if (staleRing != null && !staleArmSent && driver.clockMs >= seekClock + 40) {
                    stage.requestArm(AutomixAudioProcessor.Arm(id - 1, ShortArray(staleRing!!.size), rate, 2, clock, entryOpen = true))
                    staleArmSent = true
                } else if (armWanted && driver.clockMs >= seekClock + 80) {
                    val (song, frame) = driver.position()
                    if (song == 0) {
                        val left = aFrames - frame
                        if (left <= 0) throw AssertionError("jump $next at ${driver.clockMs} ms (sought at $seekClock): position $song/$frame, checkpoints ${driver.checkpoints.takeLast(4).map { "${it.trackFrame}:${it.song}/${it.songFrame}" }}, played ${driver.played}, written ${driver.written}, notes ${notes.takeLast(8)}")
                        stage.requestArm(AutomixAudioProcessor.Arm(id, ShortArray(((left + 8L * rate) * 2).toInt()), rate, 2, clock, entryOpen = true))
                        armWanted = false
                    }
                }
                play?.takeIf { !offered }?.let { p ->
                    plannedBefore = planned()
                    stage.offer(held!!, p.plan, p.outgoing, p.incoming, ring = ring)
                    offered = true
                    heldWhenOffered = stage.state == HeldTail.State.DRAIN
                }
                // The plan worked out on the end before, handed over once the new one is taken.
                val stale = stalePlay
                if (stale != null && offered && !staleOfferSent && planned() > plannedBefore) {
                    stage.offer(held!!, stale.plan, stale.outgoing, stale.incoming, ring = staleRing)
                    staleOfferSent = true
                }
                if (driver.position().first == 1 && stage.levels.now.id == "a") {
                    stage.levels = AutomixAudioProcessor.Levels(AutomixAudioProcessor.Level("b", 1.0), AutomixAudioProcessor.Level(null, 1.0))
                }
                driver.step()
                clock = driver.clockMs * 1_000_000L
            }
            return this
        }

        /** What should have been heard from the last jump on: A as it is, crossed into the change, the change as kept, B as it is. */
        fun expected(from: Long): ShortArray {
            val p = play!!
            val plan = p.plan
            val armFrame = aFrames - tailFrames
            val out = ShortList()
            fun add(v: Short) = out.add(shortArrayOf(v), 0, 1)
            for (f in from until armFrame) for (c in 0 until 2) add(songA[(f * 2 + c).toInt()])
            val ringA = { o: Long, c: Int -> songA[((armFrame + o) * 2 + c).toInt()] }
            for (o in 0 until plan.aFirstProcessed) for (c in 0 until 2) add(ringA(o, c))
            for (o in plan.aFirstProcessed until plan.bStart) for (c in 0 until 2) {
                val r = p.outgoing.at(o, c).toDouble()
                val x = if (o < plan.aFirstProcessed + plan.crossIn) (o - plan.aFirstProcessed + 1).toDouble() / plan.crossIn else 1.0
                add(Math.round(if (x < 1.0) r * x + ringA(o, c) * (1.0 - x) else r).toShort())
            }
            for (o in plan.bStart until plan.shapedUntil) for (c in 0 until 2) {
                add(ChangeSum.add(p.outgoing.at(o, c).toDouble(), p.incoming.at(o, c).toDouble()))
            }
            val bFrom = plan.bEntry + (plan.shapedUntil - plan.bStart)
            for (f in bFrom until songB.size / 2) for (c in 0 until 2) add(songB[(f * 2 + c).toInt()])
            return out.toArray()
        }

        fun check() {
            val p = play ?: throw AssertionError("no change worked out: $notes")
            assertTrue(notes.toString(), notes.any { it.startsWith("planned") })
            assertTrue("offered while the end drained", heldWhenOffered)
            val heard = driver.heard.toArray()
            val want = expected(jumps.last().to)
            val got = heard.copyOfRange(seekAt, heard.size)
            assertEquals("frames heard after the last jump", want.size, got.size)
            for (i in want.indices) if (want[i] != got[i]) throw AssertionError("sample $i of ${want.size} differs: ${got[i]} for ${want[i]}; ${p.plan.describe()}, joined at ${p.joinedAt}")
            // B came in once, counted from the frame it came in on.
            val bPoints = driver.checkpoints.filter { it.song == 1 }
            assertEquals(p.plan.bEntry, bPoints.first().songFrame)
            assertTrue(bPoints.all { it.songFrame >= p.plan.bEntry })
            assertEquals(0, driver.stalls)
        }
    }

    private val mixFromA: Long get() = stored.mixFrom
    private val bStartA: Long get() = stored.bStart

    @Test
    fun `a jump close before the change plays it whole, B in as planned`() {
        // Six seconds before the change: held at once, found by the clock, the whole change.
        val run = Run(listOf(Jump(mixFromA - 6L * rate, { it.driver.clockMs >= 100 }))).go()
        run.check()
        assertEquals(null, run.play!!.joinedAt)
        assertEquals(0, run.play!!.plan.crossIn)
        assertEquals(stored.inEntry, run.play!!.plan.bEntry)
    }

    @Test
    fun `a jump into the change before B comes in is joined there, B in as planned`() {
        val run = Run(listOf(Jump(mixFromA + 2L * rate, { it.driver.clockMs >= 100 }))).go()
        run.check()
        val p = run.play!!
        assertTrue(p.joinedAt != null)
        assertEquals(stored.inEntry, p.plan.bEntry)
        assertTrue(run.notes.toString(), run.notes.any { it.startsWith("planned: ") && it.endsWith("joined part-way") })
    }

    @Test
    fun `a jump past where B comes in brings B in where it would have been`() {
        val run = Run(listOf(Jump(bStartA + 2L * rate, { it.driver.clockMs >= 100 }))).go()
        run.check()
        val p = run.play!!
        val armFrame = aFrames - run.tailFrames
        // B's frame where it comes in is where it would have been at that point of A.
        assertEquals(stored.inEntry + (armFrame + p.plan.bStart - bStartA), p.plan.bEntry)
        assertTrue(p.plan.bEntry > stored.inEntry)
    }

    /** What should have been heard from a jump to B's frame [to] while the blend played: the blend from there, then B as it is. */
    private fun Run.expectedInB(to: Long): ShortArray {
        val p = play!!.plan
        val k = play!!
        val out = ShortList()
        val from = p.bStart + (to - p.bEntry)
        for (o in from until p.shapedUntil) {
            val frame = ShortArray(2) { c -> ChangeSum.add(k.outgoing.at(o, c).toDouble(), k.incoming.at(o, c).toDouble()) }
            out.add(frame, 0, 2)
        }
        val bFrom = to + (p.shapedUntil - from)
        out.add(songB, (bFrom * 2).toInt(), (songB.size - bFrom * 2).toInt())
        return out.toArray()
    }

    @Test
    fun `a jump inside the next song while the blend plays takes the blend up again there`() {
        // 13:06:28 on 8 Nov: a jump 3 s into a 16-bar change ended it. Back and forward inside it now.
        val back = stored.inEntry + 1L * rate
        val forward = stored.inEntry + 3L * rate
        val run = Run(listOf(
            Jump(mixFromA - 6L * rate, { it.driver.clockMs >= 100 }),
            Jump(forward, { it.driver.position().first == 1 && it.driver.position().second >= stored.inEntry + 2L * rate }, song = 1),
            Jump(back, { it.driver.position().first == 1 && it.driver.position().second >= forward + rate / 2 }, song = 1),
        )).go()
        assertEquals(2, run.notes.count { it.startsWith("picked up again after a jump inside the next song") })
        val heard = run.driver.heard.toArray()
        val want = run.expectedInB(back)
        val got = heard.copyOfRange(run.seekAt, heard.size)
        assertEquals(want.size, got.size)
        for (i in want.indices) if (want[i] != got[i]) throw AssertionError("sample $i of ${want.size} differs: ${got[i]} for ${want[i]}")
        assertEquals(0, run.driver.stalls)
        // Nothing crossed over to the song as it plays: it was its copy to the frame.
        assertTrue(run.notes.toString(), run.notes.none { it.contains("crossing over") })
    }

    @Test
    fun `a jump inside the next song is taken up where its decoder, started again, is near its copy but not equal`() {
        // On the emulator (8 Nov 2026) the first frames after a jump were not the copy's to the bit,
        // and no place was found: the song played on alone. Here a fifth of a second after the jump
        // carries noise 26 dB under the song.
        val back = stored.inEntry + 1L * rate
        val noisy = songB.copyOf().also { m ->
            val random = Random(5)
            for (i in (back * 2).toInt() until ((back + rate / 5) * 2).toInt()) m[i] = (m[i] + (random.nextInt(1001) - 500)).toShort()
        }
        val forward = stored.inEntry + 3L * rate
        val run = Run(listOf(
            Jump(mixFromA - 6L * rate, { it.driver.clockMs >= 100 }),
            Jump(forward, { it.driver.position().first == 1 && it.driver.position().second >= stored.inEntry + 2L * rate }, song = 1),
            Jump(back, { it.driver.position().first == 1 && it.driver.position().second >= forward + rate / 2 }, song = 1),
        ), liveB = noisy).go()
        assertEquals(run.notes.toString(), 2, run.notes.count { it.startsWith("picked up again after a jump inside the next song, at its frame $back, 0 frames") || it.startsWith("picked up again after a jump inside the next song, at its frame $forward") })
        // The blend as rendered from its copy; the song as it plays only after it, which is its own again by then.
        val heard = run.driver.heard.toArray()
        val want = run.expectedInB(back)
        val got = heard.copyOfRange(run.seekAt, heard.size)
        assertEquals(want.size, got.size)
        for (i in want.indices) if (want[i] != got[i]) throw AssertionError("sample $i of ${want.size} differs: ${got[i]} for ${want[i]}")
        assertTrue(run.notes.toString(), run.notes.none { it.contains("crossing over") })
    }

    @Test
    fun `a jump inside the next song past the blend, or back to the song before, plays as it is`() {
        val past = Run(listOf(
            Jump(mixFromA - 6L * rate, { it.driver.clockMs >= 100 }),
            Jump(stored.inEntry + 25L * rate, { it.driver.position().first == 1 && it.driver.position().second >= stored.inEntry + 2L * rate }, song = 1),
        )).go()
        assertTrue(past.notes.toString(), past.notes.any { it.endsWith("it went outside the blend") })
        val heard = past.driver.heard.toArray()
        val got = heard.copyOfRange(past.seekAt, heard.size)
        val from = (stored.inEntry + 25L * rate).toInt()
        assertEquals(songB.size - from * 2, got.size)
        for (i in got.indices) assertEquals("sample $i", songB[from * 2 + i], got[i])

        // Back into A from the blend: another song, no blend taken up; A plays from there, and its change is held again.
        val toA = Run(listOf(
            Jump(mixFromA - 6L * rate, { it.driver.clockMs >= 100 }),
            Jump(mixFromA - 8L * rate, { it.driver.position().first == 1 && it.driver.position().second >= stored.inEntry + 2L * rate }),
        )).go()
        assertTrue(toA.notes.toString(), toA.notes.none { it.startsWith("picked up again") })
        toA.check()
        assertEquals(null, toA.play!!.joinedAt)
    }

    @Test
    fun `jumps back and forth leave nothing behind, and a late ring or plan for an end held before is never taken`() {
        val run = Run(listOf(
            // Far before the change, then into it once that end is held, back before it once that one
            // drains, and into it again past where B comes in: each end let go, its late work refused.
            Jump(mixFromA - 25L * rate, { it.driver.clockMs >= 100 }),
            Jump(mixFromA + 1L * rate, { it.stage.state == HeldTail.State.CAPTURE }, staleAfter = true),
            Jump(mixFromA - 4L * rate, { it.offered && it.stage.state == HeldTail.State.DRAIN }, staleAfter = true),
            Jump(bStartA + 1L * rate, { it.offered && it.notes.any { n -> n.startsWith("planned") } && it.stage.state == HeldTail.State.DRAIN }, staleAfter = true),
        )).go()
        run.check()
        assertTrue(run.staleArmSent)
        assertTrue(run.notes.toString(), run.notes.any { it.contains("the request was for a change given up") })
        assertTrue(run.staleOfferSent)
        assertTrue(run.notes.toString(), run.notes.any { it == "refused: planned on an end held before" })
    }
}
