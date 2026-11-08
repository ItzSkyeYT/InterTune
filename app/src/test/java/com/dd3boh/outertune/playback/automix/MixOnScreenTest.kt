/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback.automix

import com.dd3boh.outertune.mix.Curve
import com.dd3boh.outertune.mix.Headroom
import com.dd3boh.outertune.mix.MixPlan
import com.dd3boh.outertune.mix.Ramp
import com.dd3boh.outertune.mix.Shape
import com.dd3boh.outertune.mix.Style
import com.dd3boh.outertune.mix.TimeMap
import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the player's screen is told of a change that plays as a mix (MixOnScreen): where it lies
 * in the incoming song, where that song takes over, where the outgoing song was, and when the
 * screen takes it off again (MixScreenState).
 */
class MixOnScreenTest {

    private val rate = 44_100

    private fun song(id: String, seconds: Int = 200) = MediaMetadata(id = id, title = id, artists = emptyList(), duration = seconds, genre = null)

    private fun frames(seconds: Double) = Math.round(seconds * rate)

    /**
     * A blend of [overlap] s from [bStart] s into the end held, A gone at its end, with the plan's
     * own equal-power gains over it, or gains that stay at 1 as a change on bands hands over.
     */
    private fun plan(
        bStart: Double = 2.0,
        overlap: Double = 20.0,
        bEntry: Double = 0.0,
        flat: Boolean = false,
        style: Style = Style.BLEND,
    ): MixPlan {
        val from = frames(bStart)
        val to = frames(bStart + overlap)
        return MixPlan(
            style = style, reason = "a test", sampleRate = rate, channels = 2, tailFrames = to + frames(1.0),
            bStart = from, aMap = TimeMap.IDENTITY, aFirstProcessed = from, aGone = to,
            overlapStart = from.toDouble(), overlapEnd = to.toDouble(),
            gainA = if (flat) Curve.constant(1.0) else Curve(1.0, listOf(Ramp(from.toDouble(), to.toDouble(), 1.0, 0.0, Shape.COSINE_FALL))),
            gainB = if (flat) Curve.constant(1.0) else Curve(0.0, listOf(Ramp(from.toDouble(), to.toDouble(), 0.0, 1.0, Shape.SINE_RISE))),
            filtersA = emptyList(), filtersB = emptyList(), headroom = Headroom.NONE, incomingLoad = DoubleArray(0),
            attenuationA = 1.0, attenuationB = 1.0, shapedUntil = to, lockFrom = 0, lockTo = 0, speed = 1.0, bars = 0,
            bEntry = frames(bEntry),
        )
    }

    @Test
    fun `a blend is shown from the incoming song's entry to the outgoing song gone`() {
        // The end held from 3:00 of 3:20, the incoming song in 2 s later, from its 16 s bar.
        val shown = MixOnScreen.of(7, plan(bStart = 2.0, overlap = 20.0, bEntry = 16.0), tailStartMs = 180_000, song("a"), outgoingLengthMs = 200_000, song("b"))
        assertNotNull(shown)
        shown!!
        assertEquals(7L, shown.id)
        assertEquals("b", shown.incoming.id)
        assertEquals(16_000L, shown.entryMs)
        assertEquals(20_000L, shown.lengthMs)
        assertEquals("where the outgoing song was as the incoming one came in", 182_000L, shown.outgoingFromMs)
        assertEquals(200_000L, shown.outgoingLengthMs)
    }

    @Test
    fun `the share heard is the incoming song's place since its entry over the change, so a pause or a jump need nothing more`() {
        val shown = MixOnScreen.of(1, plan(overlap = 20.0, bEntry = 16.0), 180_000, song("a"), 200_000, song("b"))!!
        assertEquals(0f, shown.heard(16_000), 0f)
        assertEquals(0.25f, shown.heard(21_000), 1e-6f)
        assertEquals(1f, shown.heard(36_000), 0f)
        assertEquals("before its entry, nothing yet", 0f, shown.heard(3_000), 0f)
        assertEquals("long after, all of it", 1f, shown.heard(120_000), 0f)
        // Where the outgoing song is: where it was, and the time since.
        assertEquals(187_000L, shown.outgoingAt(21_000))
    }

    @Test
    fun `with the plan's own gains, the incoming song takes over where its gain passes the outgoing one's`() {
        val shown = MixOnScreen.of(1, plan(overlap = 20.0), 180_000, song("a"), 200_000, song("b"))!!
        assertNotNull(shown.gains)
        val gains = shown.gains!!
        // An equal-power pair: they cross half way.
        assertEquals(0.5f, shown.handover, 0.01f)
        assertTrue("${shown.handoverMs}", kotlin.math.abs(shown.handoverMs - 10_000) <= 200)
        assertEquals(1f, gains.outgoingAt(0f), 1e-3f)
        assertEquals(0f, gains.incomingAt(0f), 1e-3f)
        assertEquals(MixGains.equalPowerOut(0.3f), gains.outgoingAt(0.3f), 0.01f)
        assertEquals(MixGains.equalPowerIn(0.3f), gains.incomingAt(0.3f), 0.01f)
    }

    @Test
    fun `a change on bands, whose gains are in its parts, takes an equal-power pair`() {
        val shown = MixOnScreen.of(1, plan(overlap = 30.0, flat = true), 180_000, song("a"), 200_000, song("b"))!!
        assertNull(shown.gains)
        assertEquals(0.5f, shown.handover, 0f)
        assertEquals(15_000L, shown.handoverMs)
    }

    @Test
    fun `where the gains cross is found between two points of them`() {
        val n = MixGains.STEPS
        // The incoming song louder from a third of the way.
        val crossing = MixGains(FloatArray(n + 1) { 1f - it.toFloat() / n }, FloatArray(n + 1) { 0.5f * it.toFloat() / n * 3f })
        // 1 - x = 1.5 x at x = 0.4.
        assertEquals(0.4f, crossing.handover(), 1e-4f)
        assertEquals("louder from the start", 0f, MixGains(FloatArray(n + 1) { 0f }, FloatArray(n + 1) { 1f }).handover(), 0f)
        assertEquals("never louder", 1f, MixGains(FloatArray(n + 1) { 1f }, FloatArray(n + 1) { 0.5f }).handover(), 0f)
    }

    @Test
    fun `a gapless change, and one too short for the covers to come and go, are not shown`() {
        assertNull(MixOnScreen.of(1, MixPlan.gapless(rate, 2, frames(30.0), 1.0, 1.0, "a test"), 180_000, song("a"), 200_000, song("b")))
        assertNull("a one-beat cut", MixOnScreen.of(1, plan(overlap = 0.5, style = Style.CUT), 180_000, song("a"), 200_000, song("b")))
        assertNull(MixOnScreen.of(1, plan(overlap = 2.1), 180_000, song("a"), 200_000, song("b")))
        assertNotNull(MixOnScreen.of(1, plan(overlap = 2.2), 180_000, song("a"), 200_000, song("b")))
    }

    @Test
    fun `the outgoing song's length is the player's, or its own where the player had none`() {
        val shown = MixOnScreen.of(1, plan(), 180_000, song("a", seconds = 215), outgoingLengthMs = -1, song("b"))!!
        assertEquals(215_000L, shown.outgoingLengthMs)
    }

    // When the screen takes the change off (MixScreenState)

    private val said = mutableListOf<String>()
    private val screen = MixScreenState { said += it }
    private val change = MixOnScreen.of(3, plan(), 180_000, song("a"), 200_000, song("b"))!!

    @Test
    fun `it is shown from its song change, kept into the song it brings in, and gone at the next`() {
        screen.show(change, playing = "a")
        assertEquals(change, screen.shown.value)
        // The player names the incoming song a moment after the stage changed songs.
        screen.songStarted("b")
        assertEquals(change, screen.shown.value)
        screen.songStarted("c")
        assertNull(screen.shown.value)
        assertTrue(said.last(), said.last().startsWith("the player shows one cover"))
    }

    @Test
    fun `the same song again is another song`() {
        screen.show(change, playing = "b")
        // Repeat one, or the song started over by hand: not the change any more.
        screen.songStarted("b")
        assertNull(screen.shown.value)
    }

    @Test
    fun `a change dropped part way leaves the screen at once`() {
        for (text in listOf(
            "not picked up again after a jump inside the next song: it went outside the blend",
            "the next song plays differently from its copy (-12.0 dB): crossing over to it",
            "aborted in the blend, fading out: the speed or the pitch changed",
            "failed while processing: IllegalStateException",
            "dropped: mix interrupted",
        )) {
            screen.show(change, playing = "b")
            screen.stageSaid(3, text, resumable = false)
            assertNull(text, screen.shown.value)
        }
        // Given up by the controller: a guard, the lock check, the speed changed.
        screen.show(change, playing = "b")
        screen.hide(3, "the incoming song does not line up with its opening")
        assertNull(screen.shown.value)
    }

    @Test
    fun `a jump after the blend shows the one song, a jump inside it waits for the stage`() {
        screen.show(change, playing = "b")
        screen.jumped(blendPlaying = true)
        assertEquals(change, screen.shown.value)
        screen.jumped(blendPlaying = false)
        assertNull(screen.shown.value)
    }

    @Test
    fun `a blend let go by a jump stays while the stage may take it up again, and goes once it has not`() {
        screen.show(change, playing = "b")
        assertTrue(screen.dropped(3))
        screen.stageSaid(3, "dropped: mix interrupted", resumable = true)
        assertTrue(screen.resumePending)
        assertTrue("still waiting", screen.resumeLook(3, blendPlaying = false, playedMs = 400))
        assertEquals(change, screen.shown.value)
        // Taken up again: kept, and nothing more to wait for.
        assertFalse(screen.resumeLook(3, blendPlaying = true, playedMs = 600))
        assertFalse(screen.resumePending)
        assertEquals(change, screen.shown.value)

        // Another jump, and the song plays on a second past it with no blend.
        assertTrue(screen.dropped(3))
        assertTrue(screen.resumeLook(3, blendPlaying = false, playedMs = 900))
        assertFalse(screen.resumeLook(3, blendPlaying = false, playedMs = MixScreenState.RESUME_WAIT_MS + 1))
        assertNull(screen.shown.value)
    }

    @Test
    fun `the stage saying the blend was taken up again ends the wait`() {
        screen.show(change, playing = "b")
        screen.dropped(3)
        screen.stageSaid(3, "picked up again after a jump inside the next song, at its frame 123456", resumable = false)
        assertFalse(screen.resumePending)
        assertEquals(change, screen.shown.value)
    }

    @Test
    fun `what is said of another change, and a drop of another, leave this one alone`() {
        screen.show(change, playing = "b")
        screen.stageSaid(2, "aborted in the blend, fading out: a test", resumable = false)
        screen.hide(4, "a test")
        assertFalse(screen.dropped(9))
        assertEquals(change, screen.shown.value)
        // Playback stopping takes off whichever is up.
        screen.hide(null, "playback stopped")
        assertNull(screen.shown.value)
    }
}
