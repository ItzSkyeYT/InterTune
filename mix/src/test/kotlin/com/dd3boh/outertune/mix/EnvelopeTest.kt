/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Where sound starts and ends, and the shape of the level between, against the generator's answers. */
class EnvelopeTest {

    private val rate = 44_100

    private fun region(song: SynthSong, from: Double, to: Double = song.seconds): Pair<RegionAnalysis, Double> =
        RegionAnalysis.analyse(song.slice(from, to), rate, 2, RegionKind.TAIL) to from

    private fun seconds(frame: Long, offset: Double) = offset + frame.toDouble() / rate

    @Test
    fun theFirstSoundIsFoundAfterLeadingSilence() {
        val song = Synth.song(SynthSpec(bpm = 124.0, bars = 12, leadSilence = 3.0, seed = 2))
        val (head, offset) = region(song, 0.0, 30.0)
        val first = seconds(head.envelope.firstSound, offset)
        println("first sound at %.3f s, laid down at %.3f s".format(first, song.firstSound))
        assertEquals(song.firstSound, first, 0.015)
    }

    @Test
    fun theTrueEndIsWhereTheSongStopsNotWhereTheFileDoes() {
        val song = Synth.song(SynthSpec(bpm = 128.0, bars = 24, tailSilence = 20.0, seed = 3))
        val (tail, offset) = region(song, song.seconds - 40.0)
        val end = seconds(tail.envelope.trueEnd, offset)
        println("true end at %.3f s, the music stops at %.3f s, the file at %.3f s".format(end, song.trueEnd, song.seconds))
        assertEquals(song.trueEnd, end, 0.05)
    }

    @Test
    fun aQuietOutroIsFoundWithinABar() {
        val song = Synth.song(SynthSpec(bpm = 128.0, bars = 24, outroBars = 4, seed = 4))
        val (tail, offset) = region(song, song.seconds - 40.0)
        val outro = seconds(tail.envelope.outroStart, offset)
        val bar = 240.0 / 128
        println("outro from %.3f s, laid down at %.3f s".format(outro, song.outroStart))
        assertEquals(song.outroStart, outro, bar)
        assertNull("a quiet outro is not a fade", tail.envelope.fadeFrames)
    }

    @Test
    fun theIntroEndsWhereTheDrumsComeIn() {
        val song = Synth.song(SynthSpec(bpm = 120.0, bars = 12, introBars = 4, seed = 5))
        val (head, offset) = region(song, 0.0, 30.0)
        val introEnd = seconds(checkNotNull(head.envelope.introEnd) { "no intro end found" }, offset)
        println("intro ends at %.3f s, the drums come in at %.3f s".format(introEnd, song.drumsStart))
        assertEquals(song.drumsStart, introEnd, 2.0)
    }

    @Test
    fun aSongThatStartsWithEverythingHasNoIntroToEnd() {
        val song = Synth.song(SynthSpec(bpm = 120.0, bars = 16, seed = 6))
        val (head, _) = region(song, 0.0, 30.0)
        assertNull(head.envelope.introEnd)
    }

    @Test
    fun aFadedEndingIsMeasured() {
        val song = Synth.song(SynthSpec(bpm = 120.0, bars = 24, fadeBars = 8, seed = 7))
        val (tail, _) = region(song, song.seconds - 40.0)
        val fade = checkNotNull(tail.envelope.fadeFrames) { "no fade found" }.toDouble() / rate
        val laidDown = 8 * 240.0 / 120
        println("fade of %.2f s found, %.2f s laid down from 0 to -60 dB".format(fade, laidDown))
        // The fade is measured from where the level leaves the median to where it reaches -50
        // dBFS, about the middle half of a fade that runs on to -60 dB under a -15 dBFS song.
        assertTrue(fade in laidDown * 0.35..laidDown * 0.85)
    }

    @Test
    fun anAbruptEndIsNotAFade() {
        val song = Synth.song(SynthSpec(bpm = 120.0, bars = 24, seed = 8))
        val (tail, _) = region(song, song.seconds - 40.0)
        assertNull(tail.envelope.fadeFrames)
    }

    @Test
    fun loudStretchesAreLoudAndAnOutroIsNot() {
        val song = Synth.song(SynthSpec(bpm = 128.0, bars = 24, outroBars = 6, seed = 9))
        val (tail, offset) = region(song, song.seconds - 40.0)
        val outroFrame = ((song.outroStart - offset) * rate).toLong()
        assertTrue(tail.envelope.staysLoud(outroFrame - 15L * rate, outroFrame - rate / 2))
        assertFalse(tail.envelope.staysLoud(outroFrame + rate / 2, tail.envelope.trueEnd))
    }

    @Test
    fun silenceIsHandled() {
        val tail = RegionAnalysis.analyse(ShortArray(rate * 2 * 10), rate, 2, RegionKind.TAIL)
        assertEquals(tail.envelope.firstSound, tail.envelope.trueEnd)
        assertFalse(tail.beatsConfident)
    }
}
