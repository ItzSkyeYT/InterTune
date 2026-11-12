/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Phrases: the last strong bar found where the song winds down, and the candidates counted back
 * from it landing where the song's own phrases turn.
 */
class PhrasesTest {

    private val rate = 44_100

    private class Tail(val song: SynthSong, val region: RegionAnalysis, val offset: Double) {
        fun lineSeconds(line: Int): Double = offset + region.beatFrame(region.phrases!!.barBeats[line]) / 44_100
    }

    private fun tail(spec: SynthSpec): Tail {
        val song = Synth.song(spec)
        val offset = song.seconds - 40.0
        return Tail(song, RegionAnalysis.analyse(song.slice(offset), rate, 2, RegionKind.TAIL), offset)
    }

    @Test
    fun theLastStrongBarEndsWhereTheOutroStarts() {
        val t = tail(SynthSpec(bpm = 128.0, bars = 32, outroBars = 4, seed = 1))
        val phrases = checkNotNull(t.region.phrases) { "no phrases found" }
        val last = t.lineSeconds(phrases.lastStrong)
        println("last strong bar line %.3f s, outro from %.3f s".format(last, t.song.outroStart))
        assertEquals(t.song.outroStart, last, 240.0 / 128 * 0.5)
    }

    @Test
    fun withNoOutroTheLastStrongBarEndsTheSong() {
        val t = tail(SynthSpec(bpm = 124.0, bars = 32, seed = 2))
        val phrases = t.region.phrases!!
        val last = t.lineSeconds(phrases.lastStrong)
        println("last strong bar line %.3f s, song ends %.3f s".format(last, t.song.trueEnd))
        assertEquals(t.song.trueEnd, last, 240.0 / 124 * 0.5)
    }

    @Test
    fun candidatesCountedBackLandOnPhraseStarts() {
        for ((bpm, seed) in listOf(128.0 to 3L, 124.0 to 4L)) {
            val t = tail(SynthSpec(bpm = bpm, bars = 32, outroBars = 4, seed = seed))
            val phrases = t.region.phrases!!
            val bar = 240.0 / bpm
            // The song's phrases turn every 8 bars from the drums' first bar, and its outro starts
            // on one of those turns; the 8 and 16 bar grids must land on turns, within a bar.
            val turns = t.song.phrases.toList() + t.song.outroStart
            for (c in phrases.candidates.filter { it.step >= 8 }) {
                val at = t.lineSeconds(c.line)
                if (at < t.offset + 2 * bar) continue
                val nearest = turns.minOf { abs(it - at) }
                assertTrue("candidate at %.2f s on the %d grid is %.2f s from a turn".format(at, c.step, nearest), nearest < bar * 0.5)
            }
        }
    }

    @Test
    fun noveltyPeaksOnTheTurns() {
        val t = tail(SynthSpec(bpm = 126.0, bars = 32, outroBars = 4, seed = 5))
        val phrases = t.region.phrases!!
        val bar = 240.0 / 126
        val onTurns = ArrayList<Double>()
        val elsewhere = ArrayList<Double>()
        for (line in 0..phrases.bars) {
            val at = t.lineSeconds(line)
            val turn = t.song.phrases.any { abs(it - at) < bar * 0.5 } || abs(t.song.outroStart - at) < bar * 0.5
            (if (turn) onTurns else elsewhere) += phrases.novelty[line]
        }
        println("novelty on turns %s, elsewhere mean %.2f".format(onTurns.map { "%.2f".format(it) }, elsewhere.average()))
        assertTrue(onTurns.isNotEmpty())
        assertTrue(onTurns.min() > elsewhere.average() * 2)
    }
}
