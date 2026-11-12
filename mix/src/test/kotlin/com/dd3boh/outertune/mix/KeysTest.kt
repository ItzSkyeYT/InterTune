/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** The Camelot wheel and the key finder, on chord progressions made to order. */
class KeysTest {

    private val rate = 11_025.0

    /** Saw-like notes (five harmonics) for each chord of [chords], each [seconds] long, with the root an octave down as a bass. */
    private fun progression(chords: List<IntArray>, seconds: Double = 2.0, repeats: Int = 4, seed: Int = 1): FloatArray {
        val perChord = (seconds * rate).toInt()
        val out = FloatArray(perChord * chords.size * repeats)
        var at = 0
        repeat(repeats) {
            for (chord in chords) {
                for ((i, midi) in chord.withIndex()) {
                    val notes = if (i == 0) listOf(midi - 12, midi) else listOf(midi)
                    for (m in notes) {
                        val hz = 440.0 * 2.0.pow((m - 69) / 12.0)
                        for (h in 1..5) {
                            if (hz * h > rate / 2.2) break
                            val gain = 0.08 / h
                            for (n in 0 until perChord) out[at + n] += (gain * sin(2 * PI * hz * h * n / rate + seed * h)).toFloat()
                        }
                    }
                }
                at += perChord
            }
        }
        return out
    }

    private fun triad(root: Int, minor: Boolean) = intArrayOf(root, root + if (minor) 3 else 4, root + 7)

    @Test
    fun camelotCodes() {
        assertEquals("8B", MusicalKey(0, minor = false).camelot)
        assertEquals("8A", MusicalKey(9, minor = true).camelot)
        assertEquals("5A", MusicalKey(0, minor = true).camelot)
        assertEquals("4A", MusicalKey(5, minor = true).camelot)
        assertEquals("1B", MusicalKey(11, minor = false).camelot)
        assertEquals("12A", MusicalKey(1, minor = true).camelot)
        for (minor in listOf(false, true)) for (tonic in 0..11) {
            val k = MusicalKey(tonic, minor)
            assertEquals(k, MusicalKey.ofCamelot(k.camelot))
        }
        assertNull(MusicalKey.ofCamelot("13A"))
        assertNull(MusicalKey.ofCamelot("8C"))
    }

    @Test
    fun distancesOnTheWheel() {
        fun d(a: String, b: String) = MusicalKey.ofCamelot(a)!!.distanceTo(MusicalKey.ofCamelot(b)!!)
        assertEquals(0, d("8A", "8A"))
        assertEquals(1, d("8A", "9A"))
        assertEquals(1, d("8A", "7A"))
        assertEquals(1, d("8A", "8B"))
        assertEquals(1, d("12A", "1A"))
        assertEquals(2, d("8A", "10A"))
        assertEquals(3, d("8A", "9B"))
        assertEquals(3, d("8A", "3B"))
        assertEquals(3, d("5A", "11A"))
    }

    @Test
    fun aMajorProgressionReadsAsItsKey() {
        // C, F, G, C with the tonic in the bass: C major, 8B.
        val c = 60
        val music = progression(listOf(triad(c, false), triad(c + 5, false), triad(c + 7, false), triad(c, false)))
        val k = Keys.estimate(music, rate)
        assertNotNull(k)
        println(k!!.describe())
        assertEquals("8B", k.key.camelot)
        assertTrue(k.sure)
    }

    @Test
    fun aMinorProgressionReadsAsItsKey() {
        // A minor, D minor, E major, A minor: 8A.
        val a = 57
        val music = progression(listOf(triad(a, true), triad(a + 5, true), triad(a + 7, false), triad(a, true)))
        val k = Keys.estimate(music, rate)!!
        println(k.describe())
        assertEquals("8A", k.key.camelot)
        assertTrue(k.sure)
    }

    @Test
    fun everyTonicIsFoundTransposed() {
        for (shift in 0..11) {
            val root = 48 + shift
            val music = progression(listOf(triad(root, false), triad(root + 5, false), triad(root + 7, false), triad(root, false)), seconds = 1.5, repeats = 3)
            val k = Keys.estimate(music, rate)!!
            assertEquals("shift $shift: ${k.describe()}", MusicalKey(Math.floorMod(root, 12), false).camelot, k.key.camelot)
        }
    }

    @Test
    fun noiseAndSilenceAreNotSure() {
        val random = java.util.Random(3)
        val noise = FloatArray((20 * rate).toInt()) { (random.nextGaussian() * 0.1).toFloat() }
        val k = Keys.estimate(noise, rate)
        assertTrue(k == null || !k.sure)
        assertNull(Keys.estimate(FloatArray((10 * rate).toInt()), rate))
    }
}
