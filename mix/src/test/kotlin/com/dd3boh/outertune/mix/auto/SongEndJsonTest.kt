/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.auto

import com.dd3boh.outertune.mix.RegionAnalysis
import com.dd3boh.outertune.mix.RegionKind
import com.dd3boh.outertune.mix.Synth
import com.dd3boh.outertune.mix.SynthSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A song's end kept in the analysis cache reads back as it was written, and Auto picks the same
 * change from it as from the end itself, so a pill says what the planner would.
 */
class SongEndJsonTest {

    @Test
    fun `an end reads back as it was written`() {
        val end = Ends.tail(122.0, key = Ends.key("8A"), outroMs = 9_000.0, fadeMs = 4_000.0)
        val text = SongEndJson.encode(end)
        val back = SongEndJson.decode(text)
        assertNotNull(back)
        assertEquals(text, SongEndJson.encode(back!!))
        assertEquals(end.line!!.bpm, back.line!!.bpm, 1e-9)
        assertEquals(end.phrases!!.beats.toList(), back.phrases!!.beats.toList())
        assertEquals("8A", back.key!!.key.camelot)
    }

    @Test
    fun `Auto picks the same change from the ends read back`() {
        val a = Ends.tail(122.0, key = Ends.key("8A"))
        val b = Ends.head(121.0, key = Ends.key("9A"))
        val direct = AutoPlanner.pick(AutoInput(a, b, "a", "b"))
        val kept = AutoPlanner.pick(AutoInput(SongEndJson.decode(SongEndJson.encode(a))!!, SongEndJson.decode(SongEndJson.encode(b))!!, "a", "b"))
        assertEquals(direct.pill, kept.pill)
        assertEquals(direct.kind, kept.kind)
        assertEquals(direct.a.atMs, kept.a.atMs, 0.01)
        assertEquals(direct.b.atMs, kept.b.atMs, 0.01)
    }

    @Test
    fun `an end found in music reads back whole`() {
        val song = Synth.song(SynthSpec(bpm = 124.0, bars = 24, seed = 5))
        val region = RegionAnalysis.analyse(song.slice(0.0, 30.0), song.sampleRate, 2, RegionKind.HEAD)
        val end = SongEnd.of(region, 0.0, song.seconds * 1000)
        val text = SongEndJson.encode(end)
        val back = SongEndJson.decode(text)!!
        assertEquals(text, SongEndJson.encode(back))
        assertEquals(end.kind, back.kind)
        assertEquals(end.levelDb.size, back.levelDb.size)
    }

    @Test
    fun `an entry of another version or broken is not read`() {
        val text = SongEndJson.encode(Ends.tail(120.0))
        assertNull(SongEndJson.decode(text.replace("\"v\":1", "\"v\":2")))
        assertNull(SongEndJson.decode(text.take(text.length / 2)))
        assertNull(SongEndJson.decode("not json"))
    }
}
