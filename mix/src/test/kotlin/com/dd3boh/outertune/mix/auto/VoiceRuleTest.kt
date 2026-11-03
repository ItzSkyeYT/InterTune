/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.edit.How
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.Side
import com.dd3boh.outertune.mix.edit.laneAt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Never two voices at once (his rule of 3 Nov 2026), on song ends made by hand whose voices are
 * known: Auto takes the outgoing voice out before the next song's first word, or loops a bar of the
 * next song with no voice in it to bridge the two singers, and leaves a change whose voices never
 * meet as it was.
 */
class VoiceRuleTest {

    private val MIDS_A = LaneId(Side.A, Lane.MIDS)
    private val MIDS_B = LaneId(Side.B, Lane.MIDS)

    private fun pick(a: SongEnd, b: SongEnd): AutoPick = AutoPlanner.pick(AutoInput(a, b, "a", "b")).also { println(it.describe()) }

    /** The next song's voice from its bar [fromBar] (counted from 0) at [bpm], its bar 1 at 60 ms. */
    private fun singsFrom(fromBar: Double, bpm: Double): (Double) -> Double = { ms -> if (ms >= 60.0 + fromBar * 4 * 60_000.0 / bpm) 0.95 else 0.02 }

    private val always: (Double) -> Double = { 0.95 }

    @Test
    fun `a song that sings to its end and one that sings from its first bar never sing together`() {
        val a = Ends.tail(124.0, voice = always)
        val b = Ends.head(124.0, voice = always)
        val p = pick(a, b)
        assertFalse(p.isPlain)
        val spec = p.spec()
        assertTrue("together %.2f s".format(VoiceRule.together(spec, a, b)), VoiceRule.together(spec, a, b) <= VoiceRule.MAX_TOGETHER_S)
        // The outgoing voice is gone before the next one is heard.
        val firstB = checkNotNull(VoiceRule.firstHeardB(spec, b))
        val lastA = checkNotNull(VoiceRule.lastHeardA(spec, a))
        assertTrue("A heard to %.2f, B from %.2f".format(lastA, firstB), lastA <= firstB + 1.0 / 16)
    }

    @Test
    fun `the Fade without voices is left as the preset rule has it`() {
        val a = Ends.tail(128.0, voice = { 0.02 })
        val b = Ends.head(124.0, voice = { 0.02 })
        val plain = pick(Ends.tail(128.0), Ends.head(124.0))
        val p = pick(a, b)
        assertEquals(plain.pill, p.pill)
        assertEquals(plain.moves, p.moves)
        assertNull(p.introLoop)
    }

    @Test
    fun `a next song that sings only after the change keeps the Fade whole`() {
        val a = Ends.tail(124.0, voice = always)
        val b = Ends.head(124.0, voice = singsFrom(16.0, 124.0))
        val p = pick(a, b)
        assertEquals(AutoKind.BEAT_LOCKED, p.kind)
        assertNull(p.introLoop)
        assertEquals(AutoMoves.fade(p.bars.toDouble(), 0.25), p.moves)
    }

    @Test
    fun `bars of the next song with no voice in them are looped to bridge the two singers`() {
        val a = Ends.tail(124.0, voice = always)
        // Two bars of its own before it sings, as Lean On's first bar is under Lush Life's end.
        val b = Ends.head(124.0, voice = singsFrom(2.0, 124.0))
        val p = pick(a, b)
        assertEquals(AutoKind.BEAT_LOCKED, p.kind)
        val loop = checkNotNull(p.introLoop) { "no loop: ${p.why}" }
        // From the next song's own bar 1, bars with no voice in them only.
        assertEquals(p.b.atMs, loop.fromMs, 1e-6)
        assertTrue(loop.bars <= 2)
        val spec = p.spec()
        assertTrue(VoiceRule.together(spec, a, b) <= VoiceRule.MAX_TOGETHER_S)
        // The next song's own bar 1 comes after the loop, its first word later still.
        assertEquals((loop.bars * loop.times).toDouble(), spec.loopBars, 1e-9)
        assertTrue(spec.isValid)
    }

    @Test
    fun `without a loop to play the outgoing voice leaves on the bar line before the next song sings`() {
        val a = Ends.tail(124.0, voice = always)
        // It sings from bar 3, and its first two bars hold a voice too, so none can be looped.
        val b = Ends.head(124.0, voice = { ms -> if (ms < 60.0 + 2 * 4 * 60_000.0 / 124.0) 0.4 else 0.95 })
        val p = pick(a, b)
        assertFalse(p.isPlain)
        assertNull(p.introLoop)
        val spec = p.spec()
        assertTrue(VoiceRule.together(spec, a, b) <= VoiceRule.MAX_TOGETHER_S)
        if (p.kind == AutoKind.BEAT_LOCKED) {
            // A's mids leave on a bar line, over the beat before it, by bar 3 of the change.
            val m = checkNotNull(p.moves[MIDS_A])
            assertEquals(How.FADE, m.how)
            assertEquals(0.0, (m.end + 1e-9) % 1.0, 1e-6)
            assertTrue(m.end <= 2.0 + 1e-9)
            assertEquals(0.0, spec.laneAt(MIDS_A, 2.0).gain, 1e-9)
        }
    }

    @Test
    fun `an echo out never rings the outgoing voice over the next song's first word`() {
        // Too far apart to lock: a hand-over on a line.
        val a = Ends.tail(128.0, voice = always)
        val b = Ends.head(90.0, voice = always)
        val p = pick(a, b)
        assertEquals(AutoKind.HAND_OVER, p.kind)
        assertTrue(VoiceRule.together(p.spec(), a, b) <= VoiceRule.MAX_TOGETHER_S)
    }

    @Test
    fun `where a voice is not known Auto changes nothing`() {
        val a = Ends.tail(124.0)
        val b = Ends.head(124.0, voice = always)
        val p = pick(a, b)
        assertEquals(AutoMoves.fade(p.bars.toDouble(), 0.25), p.moves)
        assertEquals(0.0, VoiceRule.together(p.spec(), a, b), 0.0)
    }

    @Test
    fun `the voice heard through a lane follows its gain and filters`() {
        assertEquals(1.0, VoiceRule.voiceThrough(com.dd3boh.outertune.mix.edit.LaneState(1.0)), 1e-9)
        assertEquals(0.0, VoiceRule.voiceThrough(com.dd3boh.outertune.mix.edit.LaneState(1.0, lowPassHz = 300.0)), 1e-9)
        assertEquals(1.0, VoiceRule.voiceThrough(com.dd3boh.outertune.mix.edit.LaneState(1.0, highPassHz = 300.0)), 1e-9)
        assertEquals(0.0, VoiceRule.voiceThrough(com.dd3boh.outertune.mix.edit.LaneState(1.0, highPassHz = 6_000.0)), 1e-9)
        assertEquals(0.5, VoiceRule.voiceThrough(com.dd3boh.outertune.mix.edit.LaneState(0.5)), 1e-9)
    }

    @Test
    fun `a short rise before the next song's beat is left out and the song comes in where its beat starts`() {
        val a = Ends.tail(124.0)
        // Two bars with no kick, as Starboy's rising melody before its beat.
        val b = Ends.head(124.0, kicklessBars = 2, voice = singsFrom(2.0, 124.0))
        val p = pick(a, b)
        val bar = 4 * 60_000.0 / 124.0
        assertEquals(AutoKind.BEAT_LOCKED, p.kind)
        assertEquals(60.0 + 2 * bar, p.b.atMs, 0.5)
        assertTrue(p.why, p.why.contains("left out"))
        // Nothing of the opening before bar 1 is heard.
        for (lane in listOf(Lane.LOWS, Lane.MIDS, Lane.HIGHS)) assertEquals(0.0, p.spec().laneAt(LaneId(Side.B, lane), -0.01).gain, 1e-9)
    }

    @Test
    fun `a sung pickup before the beat is kept`() {
        val a = Ends.tail(124.0)
        val b = Ends.head(124.0, kicklessBars = 2, voice = always)
        val p = pick(a, b)
        assertFalse(p.why, p.why.contains("left out"))
    }

    @Test
    fun `a song that opens with its beat comes in on its bar 1`() {
        val b = Ends.head(124.0, voice = { 0.02 })
        val p = pick(Ends.tail(124.0), b)
        assertEquals(b.line!!.msOf(0), p.b.atMs, 0.5)
        assertFalse(p.why.contains("left out"))
        assertNull(riserEnd(b, 0))
    }

    @Test
    fun `B's voice comes in with its mids`() {
        val a = Ends.tail(124.0, voice = { 0.02 })
        val b = Ends.head(124.0, voice = always)
        val spec = pick(a, b).spec()
        // Before bar 1 B is silent; once its mids are up its voice is heard.
        assertEquals(false, VoiceRule.heardB(spec, b, -0.5))
        assertEquals(true, VoiceRule.heardB(spec, b, spec.lengthBars - 0.1))
        assertEquals(0.0, VoiceRule.together(spec, a, b), 0.0)
        assertTrue(spec.laneAt(MIDS_B, spec.lengthBars).gain > 0.99)
    }
}
