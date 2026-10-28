/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.aGoneAt
import com.dd3boh.outertune.mix.edit.laneAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Auto's rules on song ends made by hand: every beat, bar and phrase line known, so each rule is
 * held to exactly what a DJ would do with them.
 */
class AutoPlannerTest {

    private fun input(a: SongEnd, b: SongEnd, aId: String = "a", bId: String = "b", aAlbum: String? = null, bAlbum: String? = null, fixA: BeatFix? = null, recent: List<Preset> = emptyList()) =
        AutoInput(a, b, aId, bId, aAlbum, bAlbum, fixA = fixA, recent = recent)

    private fun pick(input: AutoInput): AutoPick = AutoPlanner.pick(input).also { println(it.describe()) }

    /** The phrase level of the outgoing song's bar 1, read back from the map. */
    private fun levelAt(a: SongEnd, ms: Double): Int {
        val l = a.line!!
        return a.phrases!!.levelOf(l.kNear(ms))
    }

    @Test
    fun twoHouseSongsLockOnAPhraseForEightBars() {
        val a = Ends.tail(128.0)
        val b = Ends.head(124.0)
        val p = pick(input(a, b))
        assertEquals(AutoKind.BEAT_LOCKED, p.kind)
        assertEquals(Preset.FADE, p.move)
        assertEquals(8, p.bars)
        assertTrue("bar 1 on a phrase line", p.onPhrase && levelAt(a, p.a.atMs) >= 8)
        // B comes in on its own first downbeat.
        assertEquals(b.line!!.msOf(0), p.b.atMs, 0.5)
        val spec = p.spec()
        assertTrue(spec.problems().toString(), spec.isValid)
        assertTrue(spec.beatMatched)
        assertEquals(124.0 / 128.0, spec.tempoRatio(), 1e-6)
        assertEquals(Preset.AUTO, spec.preset)
        assertEquals(Preset.FADE, spec.from)
        assertEquals("Fade · 8 bars", p.pill)
    }

    @Test
    fun theChangeEndsOnAPhraseLineAndKeepsToTheBudget() {
        val a = Ends.tail(128.0)
        val p = pick(input(a, Ends.head(126.0)))
        val spec = p.spec()
        val end = spec.aMsAt(spec.lengthBars)
        assertTrue("ends on a phrase line", levelAt(a, end) >= 8)
        assertTrue("takes %.1f s".format((a.trueEndMs - p.a.atMs) / 1000), a.trueEndMs - p.a.atMs <= AutoPlanner.BUDGET_MS)
    }

    @Test
    fun theFadeIsTheDesignsRule() {
        val p = pick(input(Ends.tail(128.0), Ends.head(124.0)))
        val m = p.moves
        val beat = 0.25
        assertEquals(How.THIN, m[LaneId(Side.B, Lane.HIGHS)]!!.how)
        assertEquals(2.0, m[LaneId(Side.B, Lane.HIGHS)]!!.len, 1e-9)
        // The basses swap over the beat before bar 5, on its bar line.
        assertEquals(4.0 - beat, m[LaneId(Side.A, Lane.LOWS)]!!.u, 1e-9)
        assertEquals(4.0 - beat, m[LaneId(Side.B, Lane.LOWS)]!!.u, 1e-9)
        val spec = p.spec()
        assertEquals(0.0, spec.laneAt(LaneId(Side.A, Lane.LOWS), 4.0).gain, 1e-9)
        assertEquals(1.0, spec.laneAt(LaneId(Side.B, Lane.LOWS), 4.0).gain, 1e-9)
        assertEquals(8.0, spec.aGoneAt(), 1e-9)
    }

    @Test
    fun doubleTimeFoldsToTheSameSpeed() {
        val p = pick(input(Ends.tail(140.0), Ends.head(70.0, beatsPerBar = 4)))
        assertEquals(AutoKind.BEAT_LOCKED, p.kind)
        assertEquals(1.0, p.speed, 1e-6)
        assertTrue(p.spec().beatMatched)
    }

    @Test
    fun farTemposHandOverOnAPhraseWithAnEcho() {
        val a = Ends.tail(128.0)
        val b = Ends.head(90.0)
        val p = pick(input(a, b))
        assertEquals(AutoKind.HAND_OVER, p.kind)
        assertEquals(Preset.ECHO_OUT, p.move)
        assertTrue(p.onPhrase)
        assertFalse(p.beatMatch)
        val spec = p.spec()
        assertTrue(spec.problems().toString(), spec.isValid)
        assertFalse(spec.beatMatched)
        // Nothing of A's beat is left once B's first downbeat lands: lows and highs gone at bar 1,
        // the mids only echoing.
        assertEquals(0.0, spec.laneAt(LaneId(Side.A, Lane.LOWS), 0.0).gain, 1e-9)
        assertEquals(0.0, spec.laneAt(LaneId(Side.A, Lane.HIGHS), 0.0).gain, 1e-9)
        assertEquals(0.0, spec.aGoneAt(), 1e-9)
        assertEquals(1.0, spec.laneAt(LaneId(Side.B, Lane.LOWS), 0.0).gain, 1e-9)
        assertEquals(b.line!!.msOf(0), p.b.atMs, 0.5)
    }

    @Test
    fun aHardCutAlternatesWithTheEchoSoItIsNotTheOnlyTrick() {
        val a = Ends.tail(128.0)
        val b = Ends.head(90.0)
        val p = pick(input(a, b, recent = listOf(Preset.ECHO_OUT, Preset.ECHO_OUT)))
        assertEquals(AutoKind.HAND_OVER, p.kind)
        assertEquals(Preset.CUT, p.move)
    }

    @Test
    fun withoutTheNextSongsBarsItIsAHandOverIntoItsFirstBeat() {
        val p = pick(input(Ends.tail(128.0), Ends.head(127.0, barsTrusted = false)))
        assertEquals(AutoKind.HAND_OVER, p.kind)
        assertFalse(p.bOnBar)
        assertTrue(p.onPhrase)
    }

    @Test
    fun withoutThisSongsBarsItLeavesOnABeatAndSaysSo() {
        val p = pick(input(Ends.tail(128.0, barsTrusted = false), Ends.head(127.0)))
        assertEquals(AutoKind.HAND_OVER, p.kind)
        assertFalse(p.onPhrase)
        assertEquals(0, p.phraseBars)
        assertTrue(p.why, p.why.contains("a beat"))
    }

    @Test
    fun withNoBeatsItBlendsForTheEndingsOwnFade() {
        val a = Ends.noBeat(RegionKind.TAIL, fadeMs = 8_000.0)
        val b = Ends.noBeat(RegionKind.HEAD)
        val p = pick(input(a, b))
        assertEquals(AutoKind.BLEND, p.kind)
        assertEquals(0, p.bars)
        assertEquals(8.0, p.seconds, 1e-9)
        val spec = p.spec()
        assertTrue(spec.problems().toString(), spec.isValid)
        assertEquals(8.0, spec.lengthMs / 1000, 1e-6)
        // It ends where this song's sound does, the next song's first sound at its start.
        assertEquals(a.trueEndMs, spec.aMsAt(spec.lengthBars), 1.0)
        assertEquals(b.firstSoundMs, p.b.atMs, 1e-9)
        assertEquals("Blend · 8 s", p.pill)
    }

    @Test
    fun twoSteadyBeatsThatCannotLockAreNeverBlended() {
        // Both keep a steady beat, neither's bars nor beats can be trusted to lock to.
        val a = Ends.tail(128.0, beatsTrusted = false, barsTrusted = false)
        val b = Ends.head(100.0, beatsTrusted = true, barsTrusted = false)
        val steadyA = SongEnd(
            a.kind, a.songMs, a.fromMs, a.toMs, a.sampleRate, a.channels, a.firstSoundMs, a.trueEndMs, a.outroStartMs, a.introEndMs,
            a.fadeMs, a.medianDb, a.levelDb, a.lowDb, a.stepMs, a.bpm, true, a.line, null, null, a.density, "steady, not trusted",
        )
        val candidates = AutoPlanner.candidates(input(steadyA, b))
        assertTrue(candidates.none { it.kind == AutoKind.BLEND })
    }

    @Test
    fun aBlendIsNeverLongerThanTheQuietStretchThatLetsItThrough() {
        // A keeps a steady 100 BPM pulse its beats cannot be trusted on, and ends in an 8 s quiet
        // outro after a fade; B's beat starts with its first sound. Blending is allowed only while
        // A is in its outro: a 12 s blend would lay A's pulse under B's beat for 4 s.
        val a = Ends.tail(100.0, beatsTrusted = false, barsTrusted = false, outroMs = 8_000.0, fadeMs = 10_000.0)
        val steadyA = SongEnd(
            a.kind, a.songMs, a.fromMs, a.toMs, a.sampleRate, a.channels, a.firstSoundMs, a.trueEndMs, a.outroStartMs, a.introEndMs,
            a.fadeMs, a.medianDb, a.levelDb, a.lowDb, a.stepMs, a.bpm, true, a.line, null, null, a.density, "steady, not trusted",
        )
        val intoBeat = AutoPlanner.candidates(input(steadyA, Ends.head(128.0))).filter { it.kind == AutoKind.BLEND }
        intoBeat.forEach { println("${it.pill}: ${it.why}") }
        assertTrue(intoBeat.isNotEmpty())
        assertTrue(intoBeat.joinToString { it.pill }, intoBeat.all { it.a.atMs >= steadyA.outroStartMs })
        // The other way round: A keeps its trusted beat to the end, and B's quiet intro lasts 7.5 s
        // before its beat comes in. No blend runs past it.
        val quietIntro = Ends.head(128.0, introEndBars = 4.0, beatsFromBars = 4)
        val introQuiet = quietIntro.introEndMs!! - quietIntro.firstSoundMs
        val fromBeat = AutoPlanner.candidates(input(Ends.tail(120.0), quietIntro)).filter { it.kind == AutoKind.BLEND }
        fromBeat.forEach { println("${it.pill}: ${it.why}") }
        assertTrue(fromBeat.isNotEmpty())
        assertTrue(fromBeat.joinToString { it.pill }, fromBeat.all { it.seconds * 1000 <= introQuiet })
    }

    @Test
    fun everyGuardGivesPlainWithItsReason() {
        val a = Ends.tail(128.0)
        val b = Ends.head(124.0)
        assertEquals(AutoPlain.SAME_SONG, pick(input(a, b, aId = "x", bId = "x")).plain)
        assertEquals(AutoPlain.SHORT_SONG, pick(input(Ends.tail(128.0, songMs = 55_000.0, regionMs = 50_000.0), b)).plain)
        assertEquals(AutoPlain.SHORT_SONG, pick(input(a, Ends.head(124.0, songMs = 59_000.0, regionMs = 50_000.0))).plain)
        assertEquals("Plain · same song", pick(input(a, b, aId = "x", bId = "x")).pill)
        val p = pick(input(a, Ends.head(124.0, songMs = 59_000.0, regionMs = 50_000.0)))
        assertTrue(p.isPlain)
        assertTrue(p.spec().problems().toString(), p.spec().isValid)
        assertTrue(p.spec().plain)
    }

    @Test
    fun twoSongsOfOneAlbumMixUnlessTheyRunTogether() {
        val a = Ends.tail(128.0)
        val b = Ends.head(124.0)
        // This one ends with a second and a half of silence and the next starts half a second in: mixed.
        assertFalse(pick(input(Ends.tail(128.0, silenceAtEndMs = 1_500.0), Ends.head(124.0, firstSoundMs = 500.0, firstDownbeatMs = 520.0), aAlbum = "one", bAlbum = "one")).isPlain)
        val glued = Ends.head(124.0, firstSoundMs = 0.0, firstDownbeatMs = 10.0)
        val gluedTail = SongEnd(
            a.kind, a.songMs, a.fromMs, a.toMs, a.sampleRate, a.channels, a.firstSoundMs, a.toMs, a.outroStartMs, a.introEndMs,
            a.fadeMs, a.medianDb, a.levelDb, a.lowDb, a.stepMs, a.bpm, a.steadyTempo, a.line, a.phrases, a.key, a.density, a.trustNote,
        )
        assertEquals(AutoPlain.RUN_TOGETHER, pick(input(gluedTail, glued, aAlbum = "one", bAlbum = "one")).plain)
        assertFalse(pick(input(gluedTail, glued, aAlbum = "one", bAlbum = "two")).isPlain)
    }

    @Test
    fun aKeyClashShortensTheOverlapOfTwoMelodies() {
        val tieA = Ends.tail(128.0, key = Ends.key("8A"))
        val good = pick(input(tieA, Ends.head(126.0, key = Ends.key("9A"))))
        val clash = pick(input(tieA, Ends.head(126.0, key = Ends.key("3B"))))
        assertEquals(1, good.keyDistance)
        assertEquals(3, clash.keyDistance)
        assertEquals(AutoKind.BEAT_LOCKED, clash.kind)
        // The clash keeps the next song's mids muffled (Rise), or shortens the change.
        assertTrue(clash.describe(), clash.move == Preset.RISE || clash.bars < good.bars)
        assertTrue(good.score > clash.score)
    }

    @Test
    fun anOpeningWithoutAKickRisesInMuffled() {
        val p = pick(input(Ends.tail(128.0), Ends.head(126.0, kicklessBars = 8)))
        assertEquals(AutoKind.BEAT_LOCKED, p.kind)
        assertEquals(Preset.RISE, p.move)
    }

    @Test
    fun aBeatlessIntroIsMixedSoItsDropLandsAsTheOldSongLeaves() {
        // Eight bars of chords with no beat to lock to, then the drop, where its beats start.
        val b = Ends.head(126.0, beatsFromBars = 8, kicklessBars = 8, firstSoundMs = 40.0, firstDownbeatMs = 60.0)
        val a = Ends.tail(128.0)
        val p = pick(input(a, b))
        assertEquals(AutoKind.INTRO_MIX, p.kind)
        assertEquals(8, p.bars)
        val spec = p.spec()
        assertTrue(spec.problems().toString(), spec.isValid)
        val dropMs = b.line!!.msOf(32)
        // The drop is bar 9 of the change, where the outgoing song is gone, on its phrase line.
        assertEquals(dropMs, spec.bMsAt(8.0), 1.0)
        assertTrue(levelAt(a, spec.aMsAt(8.0)) >= 8)
        assertEquals(8.0, spec.aGoneAt(), 1e-9)
    }

    @Test
    fun theNextSongsDropIsNotLaidUnderTheOldSong() {
        // B's first rise comes 4 bars in: a 4-bar change ends on it, an 8-bar one would put it under A.
        val p = pick(input(Ends.tail(128.0), Ends.head(127.0, introEndBars = 4.0)))
        assertEquals(AutoKind.BEAT_LOCKED, p.kind)
        assertEquals(4, p.bars)
    }

    @Test
    fun busyEndsGetFourBars() {
        val p = pick(input(Ends.tail(128.0, density = 7.5), Ends.head(127.0, density = 7.0)))
        assertEquals(AutoKind.BEAT_LOCKED, p.kind)
        assertEquals(4, p.bars)
    }

    @Test
    fun tempos5PercentApartLockOnlyForFourBars() {
        val p = pick(input(Ends.tail(128.0), Ends.head(128.0 * 0.95)))
        assertEquals(AutoKind.BEAT_LOCKED, p.kind)
        assertTrue(p.bars <= 4)
    }

    @Test
    fun anOutroIsWhereTheMixStarts() {
        // The last 8 bars (15 s at 128) are a quiet outro, starting on an 8-bar phrase line.
        val a = Ends.tail(128.0, outroMs = 14_880.0, phraseOffsetBars = 0)
        val p = pick(input(a, Ends.head(126.0)))
        assertEquals(AutoKind.BEAT_LOCKED, p.kind)
        assertTrue(abs(p.a.atMs - a.outroStartMs) <= a.line!!.barMs)
    }

    @Test
    fun aListenersFixToTheBeatIsUsed() {
        val a = Ends.tail(128.0)
        val b = Ends.head(124.0)
        val plainPick = pick(input(a, b))
        val shifted = pick(input(a, b, fixA = BeatFix(downbeatShift = 1)))
        // The one moved a beat later, so every bar line of A did, and its phrase lines with them.
        assertEquals(1, Math.floorMod(a.line!!.kNear(shifted.a.atMs) - a.line!!.kNear(plainPick.a.atMs), 4))
        assertEquals(AutoKind.BEAT_LOCKED, shifted.kind)
        val wrongTempo = pick(input(a, b, fixA = BeatFix(bpm = 100.0)))
        assertTrue(wrongTempo.kind != AutoKind.BEAT_LOCKED)
    }

    @Test
    fun everyCandidateIsAValidSpec() {
        val pairs = listOf(
            Ends.tail(128.0) to Ends.head(124.0),
            Ends.tail(128.0) to Ends.head(90.0),
            Ends.tail(140.0) to Ends.head(70.0),
            Ends.tail(128.0, barsTrusted = false) to Ends.head(127.0),
            Ends.noBeat(RegionKind.TAIL) to Ends.head(120.0),
            Ends.tail(128.0) to Ends.head(126.0, beatsFromBars = 8, kicklessBars = 8),
        )
        for ((a, b) in pairs) for (c in AutoPlanner.candidates(input(a, b))) {
            assertTrue(c.describe() + ": " + c.spec().problems(), c.spec().isValid)
        }
    }

    @Test
    fun theSameInputGivesTheSamePick() {
        val one = pick(input(Ends.tail(128.0), Ends.head(124.0)))
        val two = pick(input(Ends.tail(128.0), Ends.head(124.0)))
        assertEquals(one.spec(), two.spec())
    }

    @Test
    fun aProofThatFailsMovesToTheNextCandidate() {
        val a = Ends.tail(128.0)
        val b = Ends.head(124.0)
        val best = AutoPlanner.candidates(input(a, b)).first()
        // A renderer whose proof of the best candidate clips, and of every other is clean.
        val renderer = Proof.Renderer { c ->
            val mix = FloatArray(2 * 44_100 * 20) { 0.3f * kotlin.math.sin(it * 0.05).toFloat() }
            if (c.spec() == best.spec()) mix[2 * 10 * 44_100] = 1.5f
            Proof(44_100, mix, 5 * 44_100, 15 * 44_100)
        }
        val p = AutoPlanner.pick(input(a, b), renderer)
        println(p.describe())
        assertTrue(p.spec() != best.spec())
        assertTrue(p.why, p.why.contains("refused"))
        val none = AutoPlanner.pick(input(a, b), Proof.Renderer { Proof(44_100, FloatArray(2 * 44_100 * 20) { 2f }, 0, 44_100 * 20) })
        assertEquals(AutoPlain.NO_CLEAN_MIX, none.plain)
    }

    @Test
    fun nothingStartsBeforeTheEarliestFrame() {
        val a = Ends.tail(128.0)
        val earliest = a.songMs - 22_000.0
        for (c in AutoPlanner.candidates(AutoInput(a, Ends.head(124.0), "a", "b", earliestMs = earliest))) {
            if (c.isPlain) continue
            val spec = c.spec()
            val firstU = spec.moves.values.minOf { it.u }.coerceAtMost(0.0)
            assertTrue(c.describe(), spec.aMsAt(firstU) >= earliest - 1)
        }
    }

    @Test
    fun noKeyReadingNoKeyRule() {
        val p = pick(input(Ends.tail(128.0), Ends.head(124.0)))
        assertNull(p.keyDistance)
    }

    @Test
    fun aSongWhoseFirstDownbeatIsItsFirstFrameIsMixedOnIt() {
        // Its fitted line puts that downbeat 4 ms before its first frame, inside the 30 ms bar 1 may sit before the first sound.
        val b = Ends.head(128.0, firstSoundMs = 0.0, firstDownbeatMs = -4.0)
        val p = pick(input(Ends.tail(128.0), b))
        assertFalse(p.why, p.isPlain)
        assertTrue("${p.b.atMs}", p.b.atMs >= 0.0)
        val spec = p.spec()
        assertTrue(spec.problems().toString(), spec.isValid)
        assertEquals(0.0, atOrAfterBStart(-4.0), 0.0)
        assertEquals(-40.0, atOrAfterBStart(-40.0), 0.0)
        assertEquals(12.0, atOrAfterBStart(12.0), 0.0)
    }
}
