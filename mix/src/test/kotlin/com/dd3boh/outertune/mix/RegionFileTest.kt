/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException

/**
 * The Mix editor keeps each window's analysis in a file (his ask of 6 Nov 2026: going back to a
 * pair should not analyse it again), so a region read back must be the region written, to the bit.
 */
class RegionFileTest {

    private fun bytes(r: RegionAnalysis): ByteArray = ByteArrayOutputStream().also { DataOutputStream(it).use { out -> RegionFile.write(r, out) } }.toByteArray()

    private fun back(b: ByteArray): RegionAnalysis = DataInputStream(ByteArrayInputStream(b)).use { RegionFile.read(it) }

    private val song = Synth.song(SynthSpec(bpm = 124.0, bars = 24, introBars = 4, outroBars = 4))

    private fun sameRegion(r: RegionAnalysis, again: RegionAnalysis) {
        assertArrayEquals(bytes(r), bytes(again))
        assertEquals(r.kind, again.kind)
        assertEquals(r.bpm, again.bpm)
        assertEquals(r.beatsConfident, again.beatsConfident)
        assertEquals(r.barsConfident, again.barsConfident)
        assertEquals(r.firstDownbeat, again.firstDownbeat)
        assertArrayEquals(r.beatFrames(), again.beatFrames(), 0.0)
        assertArrayEquals(r.guide, again.guide, 0f)
        assertEquals(r.envelope.firstSound, again.envelope.firstSound)
        assertEquals(r.envelope.trueEnd, again.envelope.trueEnd)
        assertEquals(r.phrases?.candidates?.map { Triple(it.line, it.step, it.score) }, again.phrases?.candidates?.map { Triple(it.line, it.step, it.score) })
    }

    @Test
    fun `an opening and an end read back as they were written`() {
        val head = RegionAnalysis.analyse(song.slice(0.0, 30.0), song.sampleRate, 2, RegionKind.HEAD)
        val tail = RegionAnalysis.analyse(song.slice(song.seconds - 40.0), song.sampleRate, 2, RegionKind.TAIL)
        // Something to keep: beats, bars and phrases found, and the opening's filtered peaks.
        assertTrue(head.beatsConfident && tail.beatsConfident)
        assertNotNull(tail.phrases)
        assertNotNull(head.hopPeakAbove120)
        sameRegion(head, back(bytes(head)))
        sameRegion(tail, back(bytes(tail)))
    }

    @Test
    fun `a region with no beat keeps its empty parts empty`() {
        val quiet = RegionAnalysis.analyse(ShortArray(20 * 44_100 * 2), 44_100, 2, RegionKind.TAIL)
        assertNull(quiet.rhythm.tempo)
        assertNull(quiet.grid)
        assertNull(quiet.phrases)
        val again = back(bytes(quiet))
        assertNull(again.rhythm.tempo)
        assertNull(again.grid)
        assertNull(again.downbeats)
        assertNull(again.phrases)
        sameRegion(quiet, again)
        // A pad with no pulse, whatever was or was not found in it.
        val pad = RegionAnalysis.analyse(Synth.pad(20.0), 44_100, 2, RegionKind.TAIL)
        sameRegion(pad, back(bytes(pad)))
    }

    @Test
    fun `a file cut short or of another version is refused, never half read`() {
        val b = bytes(RegionAnalysis.analyse(song.slice(0.0, 20.0), song.sampleRate, 2, RegionKind.HEAD))
        for (broken in listOf(b.copyOf(b.size / 2), b.copyOf().also { it[7] = (RegionFile.VERSION + 1).toByte() }, ByteArray(0))) {
            try {
                back(broken)
                fail("a broken file was read")
            } catch (_: IOException) {
            }
        }
    }

    /**
     * The file writes every value each constructor takes. A class that gains one fails here: write it
     * in RegionFile, read it back in the same place, and raise RegionFile.VERSION so older files are
     * analysed again.
     */
    @Test
    fun `every value of every class in a region is written`() {
        val written = mapOf(
            RegionAnalysis::class.java to 16,
            Envelope::class.java to 10,
            Rhythm::class.java to 4,
            TempoEstimate::class.java to 5,
            BeatGrid::class.java to 12,
            Downbeats::class.java to 6,
            Gates::class.java to 11,
            Phrases::class.java to 7,
            PhraseCandidate::class.java to 3,
        )
        for ((type, count) in written) {
            val takes = type.declaredConstructors.filter { !it.isSynthetic }.maxOf { it.parameterCount }
            assertEquals("${type.simpleName} takes $takes values; RegionFile writes $count", count, takes)
        }
    }
}
