/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.Groove
import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.Synth
import com.dd3boh.outertune.mix.SynthSong
import com.dd3boh.outertune.mix.SynthSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Phrase lines found from where the music changes, on synthetic songs whose phrases are known,
 * and the whole of Auto from audio: analysis, phrases and the pick.
 */
class PhraseFinderTest {

    private fun tailOf(song: SynthSong, seconds: Double = 60.0): SongEnd {
        val from = song.seconds - seconds
        val region = RegionAnalysis.analyse(song.slice(from), song.sampleRate, 2, RegionKind.TAIL)
        return SongEnd.of(region, from * 1000, song.seconds * 1000)
    }

    private fun headOf(song: SynthSong, seconds: Double = 60.0): SongEnd {
        val region = RegionAnalysis.analyse(song.slice(0.0, seconds), song.sampleRate, 2, RegionKind.HEAD)
        return SongEnd.of(region, 0.0, song.seconds * 1000)
    }

    /** How many of [end]'s lines on the [bars]-bar grid fall within a beat of one of [song]'s own phrase lines, of how many. */
    private fun agreement(end: SongEnd, song: SynthSong, bars: Int): Pair<Int, Int> {
        val line = end.line!!
        val p = end.phrases!!
        val truth = song.phrases.map { it * 1000 }
        var right = 0
        var total = 0
        println("  lines: " + p.beats.indices.joinToString { "%.2f s/%d".format(line.msOf(p.beats[it]) / 1000, p.level[it]) } +
            "; first sound %.2f, true end %.2f, truth %s".format(end.firstSoundMs / 1000, end.trueEndMs / 1000, song.phrases.joinToString { "%.2f".format(it) }))
        for (j in p.beats.indices) {
            if (p.level[j] < bars) continue
            val ms = line.msOf(p.beats[j])
            if (ms < end.firstSoundMs || ms > end.trueEndMs - line.barMs) continue
            total++
            if (truth.any { abs(it - ms) <= line.beatMs * 0.5 }) right++
        }
        return right to total
    }

    @Test
    fun aSongThatEndsMidPhraseKeepsItsPhraseLines() {
        // 44 bars of 8-bar phrases: the last phrase is 4 bars, so counting back from the last
        // loud bar puts every line 4 bars off.
        for (groove in listOf(Groove.FOUR_ON_THE_FLOOR, Groove.BACKBEAT)) {
            val song = Synth.song(SynthSpec(bpm = 128.0, bars = 44, groove = groove, seed = 5))
            val end = tailOf(song)
            val (right, total) = agreement(end, song, 8)
            println("$groove: ${end.trustNote}; 8-bar lines right $right of $total, heard ${end.phrases!!.heard} t ${end.phrases!!.margin}")
            assertTrue(total >= 2)
            assertEquals("$groove", total, right)
            assertTrue(end.phrases!!.heard)
        }
    }

    @Test
    fun aSongThatEndsOnAPhraseLineToo() {
        val song = Synth.song(SynthSpec(bpm = 124.0, bars = 48, seed = 6))
        val end = tailOf(song)
        val (right, total) = agreement(end, song, 8)
        println("${end.trustNote}; 8-bar lines right $right of $total")
        assertTrue(total >= 2)
        assertEquals(total, right)
    }

    @Test
    fun autoFromAudioLocksOnTheRealPhraseLine() {
        val a = Synth.song(SynthSpec(bpm = 128.0, bars = 44, seed = 7))
        val b = Synth.song(SynthSpec(bpm = 124.0, bars = 40, seed = 8))
        val pick = AutoPlanner.pick(AutoInput(tailOf(a), headOf(b), "a", "b"))
        println(pick.describe())
        assertEquals(AutoKind.BEAT_LOCKED, pick.kind)
        assertTrue(pick.onPhrase)
        val beatMs = 60_000.0 / 128.0
        assertTrue("bar 1 at %.0f ms, phrases at %s".format(pick.a.atMs, a.phrases.joinToString { "%.0f".format(it * 1000) }),
            a.phrases.any { abs(it * 1000 - pick.a.atMs) <= beatMs * 0.25 })
        assertEquals(b.downbeats.first() * 1000, pick.b.atMs, 25.0)
        assertTrue(pick.spec().isValid)
    }
}
