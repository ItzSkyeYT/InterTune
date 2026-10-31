/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.sin

/**
 * A listen of the phone's own sound hears a short video through to where it starts over, and asks
 * Shazam about the whole clip rather than four seconds of it (his two corrections of 16 Oct 2026:
 * "listening way too short", and a remix named as the original song).
 */
class ClipListenTest {
    private val rate = 16_000

    /** Something like music: a chord that changes every fifth of a second or so, never the same twice. */
    private fun music(seconds: Double, seed: Long): ShortArray {
        val random = Random(seed)
        val out = ShortArray((seconds * rate).toInt())
        var i = 0
        while (i < out.size) {
            val length = minOf(((0.12 + random.nextDouble() * 0.16) * rate).toInt(), out.size - i)
            val tones = DoubleArray(3) { 150.0 + random.nextDouble() * 3500 }
            val loudness = DoubleArray(3) { 1500.0 + random.nextDouble() * 5000 }
            for (k in 0 until length) {
                var v = 0.0
                for (j in 0 until 3) v += loudness[j] * sin(2 * PI * tones[j] * (i + k) / rate)
                out[i + k] = v.coerceIn(-32000.0, 32000.0).toInt().toShort()
            }
            i += length
        }
        return out
    }

    /** [clip] played over and over from [from] seconds in, for [seconds]. */
    private fun looped(clip: ShortArray, from: Double, seconds: Double): ShortArray {
        val start = (from * rate).toInt()
        return ShortArray((seconds * rate).toInt()) { clip[(start + it) % clip.size] }
    }

    /** One-second pieces, as the recorder hands them over. */
    private fun pieces(sound: ShortArray): List<ShortArray> =
        (sound.indices step rate).map { sound.copyOfRange(it, minOf(it + rate, sound.size)) }

    @Test
    fun `a clip that starts over is found, to a frame, as soon as three seconds of it came round`() {
        val finder = LoopFinder(rate)
        var found: Double? = null
        var heard = 0
        for (piece in pieces(looped(music(7.3, 1), from = 2.1, seconds = 20.0))) {
            finder.add(piece)
            heard += piece.size
            found = finder.find()
            if (found != null) break
        }
        assertEquals(7.3, found!!, 0.04)
        assertTrue("found only after ${heard / rate} s", heard <= (7.3 + 3 + 1) * rate)
    }

    @Test
    fun `music that never repeats is not taken for a loop`() {
        val finder = LoopFinder(rate)
        for (piece in pieces(music(30.0, 2))) {
            finder.add(piece)
            assertNull(finder.find())
        }
        assertTrue("never compared anything", finder.best != null)
        assertTrue("came close to a loop: ${finder.best}", finder.best!!.first < LoopFinder.SIMILAR)
    }

    @Test
    fun `a short video is asked about as soon as it came round, and the listen goes on after the loop`() {
        val listen = ClipListen(rate)
        val sound = ShortArray(4 * rate) + looped(music(7.3, 3), from = 1.0, seconds = 40.0)
        val questions = mutableListOf<Pair<Int, ClipListen.Question>>()
        var heldAt = -1
        var second = 0
        for (piece in pieces(sound)) {
            listen.add(piece)?.let { questions += (second - 4 + 1) to it }
            second++
            if (listen.loopHeld && heldAt < 0) heldAt = second - 4
            if (listen.over) break
        }
        // The first window comes once the clip has come round again (7.3 s and three of the next turn, heard
        // from the 5th second on), not at twelve, and holds everything heard up to twelve seconds.
        val (firstAt, first) = questions.first()
        assertTrue("first asked at $firstAt s of sound", firstAt in 10..12)
        assertTrue(first.why, "repeats" in first.why)
        assertEquals(firstAt.toDouble(), first.seconds, 0.01)
        assertEquals(0.0, first.fromSeconds, 0.01)
        assertEquals(7.3, listen.loopSeconds!!, 0.04)
        // A repeat that keeps repeating for a whole turn after it was first seen is a loop.
        assertTrue("held only at $heldAt s", heldAt in (7.3 + 3 + 7.3).toInt()..(7.3 + 3 + 7.3 + 3).toInt())
        // And it does not end the listen: that is for the answer, the minute and the silence to do.
        assertFalse("the loop ended the listen", listen.over)
        // Then a window every six seconds, each about the last twelve.
        val gaps = questions.zipWithNext { a, b -> b.first - a.first }
        assertTrue("windows ${questions.map { it.first }}", gaps.all { it == ClipListen.AGAIN_AFTER_SECONDS })
        assertTrue(questions.drop(1).all { it.second.seconds == 12.0 })
    }

    @Test
    fun `two bars that repeat for a while are not the end of a video, and a repeat never shortens a window`() {
        val listen = ClipListen(rate)
        // A 4 s figure four times over, then music that is never the same again.
        val sound = looped(music(4.0, 5), from = 0.0, seconds = 16.0) + music(40.0, 6)
        val asked = mutableListOf<Int>()
        var second = 0
        var longest = 0.0
        for (piece in pieces(sound)) {
            second++
            listen.add(piece)?.let { asked += second; longest = maxOf(longest, it.seconds) }
            if (listen.over) break
        }
        assertTrue("asked at $asked", asked.size >= 6)
        assertTrue("a window is the last twelve seconds at most, and the first one all there was: $longest", longest == 12.0)
        assertFalse("ended after $second s", second < 50)
        // The figure stopped repeating, so the listen no longer takes the clip for a loop.
        assertNull(listen.loopSeconds)
        assertFalse(listen.loopHeld)
    }

    @Test
    fun `a long video is asked about its last twelve seconds, then again every six, for a minute`() {
        val listen = ClipListen(rate)
        val asked = mutableListOf<Pair<Int, Int>>()
        val starts = mutableListOf<Double>()
        var second = 0
        for (piece in pieces(music(75.0, 4))) {
            second++
            listen.add(piece)?.let { asked += second to it.samples.size / rate; starts += it.fromSeconds }
            if (listen.over) break
        }
        assertEquals((12..60 step 6).map { it to 12 }, asked)
        // Each window starts where the sound it is about starts: twelve seconds before it ends.
        assertEquals((0..48 step 6).map { it.toDouble() }, starts)
        assertTrue(listen.over)
        assertEquals(ClipListen.MOST_HEARD_SECONDS.toDouble(), listen.heardSeconds, 0.01)
        assertNull(listen.loopSeconds)
    }

    @Test
    fun `silence after the sound means the video ended`() {
        val listen = ClipListen(rate)
        var second = 0
        for (piece in pieces(music(10.0, 7) + ShortArray(20 * rate))) {
            second++
            listen.add(piece)
            if (listen.over) break
        }
        assertTrue(listen.over)
        assertEquals(10 + ClipListen.QUIET_END_SECONDS, second)
    }

    @Test
    fun `a window ends where the sound did, and a video that stops is asked about what came after the last window`() {
        fun asked(soundSeconds: Int): List<Triple<Int, Double, Double>> {
            val listen = ClipListen(rate)
            val out = mutableListOf<Triple<Int, Double, Double>>()
            var second = 0
            for (piece in pieces(music(soundSeconds.toDouble(), 9) + ShortArray(10 * rate))) {
                second++
                listen.add(piece)?.let { out += Triple(second, it.fromSeconds, it.seconds) }
                if (listen.over) break
            }
            return out
        }
        // Five seconds that never repeat: asked once, when the sound had stopped for good.
        assertEquals(listOf(Triple(5 + ClipListen.QUIET_END_SECONDS, 0.0, 5.0)), asked(5))
        // Eight: the first window falls due at twelve, four seconds into the silence, and holds the eight.
        assertEquals(listOf(Triple(12, 0.0, 8.0)), asked(8))
        // Twenty-three: the windows at twelve and eighteen, then the last twelve of sound at twenty-four.
        assertEquals(listOf(Triple(12, 0.0, 12.0), Triple(18, 6.0, 12.0), Triple(24, 11.0, 12.0)), asked(23))
        // Twenty-two: four seconds of new sound since the last window say nothing.
        assertEquals(listOf(12, 18), asked(22).map { it.first })
        // Four seconds in all say nothing either.
        assertEquals(emptyList<Triple<Int, Double, Double>>(), asked(4))
    }

    @Test
    fun `only silence, nothing is asked and the listen ends`() {
        val listen = ClipListen(rate)
        var seconds = 0
        while (!listen.over && seconds < 100) {
            assertNull(listen.add(ShortArray(rate)))
            seconds++
        }
        assertTrue(listen.over)
        assertFalse(listen.heardAnything)
        assertEquals(ClipListen.MOST_WAITED_SECONDS, seconds)
    }
}
