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
    fun `a short video is asked about once, one whole turn of it, after the silence before it`() {
        val listen = ClipListen(rate)
        val sound = ShortArray(4 * rate) + looped(music(7.3, 3), from = 1.0, seconds = 30.0)
        val questions = mutableListOf<ClipListen.Question>()
        var piecesUntilOver = 0
        for (piece in pieces(sound)) {
            listen.add(piece)?.let { questions += it }
            piecesUntilOver++
            if (listen.over) break
        }
        assertEquals(1, questions.size)
        assertEquals(7.3, questions[0].samples.size.toDouble() / rate, 0.05)
        assertTrue(questions[0].why, "repeats" in questions[0].why)
        assertEquals(7.3, listen.loopSeconds!!, 0.04)
        // Four seconds of silence, the turn and three seconds of it again, then one more piece.
        assertTrue("over only after $piecesUntilOver s", piecesUntilOver <= 4 + 12 + 1)
    }

    @Test
    fun `a long video is asked about its last twelve seconds, then again every six, for thirty`() {
        val listen = ClipListen(rate)
        val asked = mutableListOf<Pair<Int, Int>>()
        var second = 0
        for (piece in pieces(music(45.0, 4))) {
            second++
            listen.add(piece)?.let { asked += second to it.samples.size / rate }
            if (listen.over) break
        }
        assertEquals(listOf(12 to 12, 18 to 12, 24 to 12, 30 to 12), asked)
        assertTrue(listen.over)
        assertNull(listen.loopSeconds)
    }

    @Test
    fun `only silence, nothing is asked and the listen ends`() {
        val listen = ClipListen(rate)
        var seconds = 0
        while (!listen.over && seconds < 60) {
            assertNull(listen.add(ShortArray(rate)))
            seconds++
        }
        assertTrue(listen.over)
        assertFalse(listen.heardAnything)
        assertEquals(ClipListen.MOST_WAITED_SECONDS, seconds)
    }
}
