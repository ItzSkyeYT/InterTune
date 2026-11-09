/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.mix.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * His ask of 9 Nov 2026: "let me create saved presets for the transitions". A preset keeps the
 * shape of a change and none of its songs; laid on another pair it keeps that pair's own places;
 * it is kept in a file beside the saved changes that a new store (an update, a restart) reads back.
 */
class MixPresetTest {

    @get:Rule
    val dir = TemporaryFolder()

    /** Another pair, at other places in other songs. */
    private val other = Specs.fadeOnBands(4).copy(
        a = Anchor("lean-on", 190_000.0, 98.0, fix = BeatFix(nudgeMs = 12)),
        b = Anchor("lush-life", 2_000.0, 98.0),
        bStartMs = 500.0,
    )

    private fun custom(): TransitionSpec = Specs.vocalsOverCustom().copy(
        introTwice = 0,
        introLoop = IntroLoop(bars = 2, fromMs = Specs.b.atMs + 4 * Specs.b.barMs, times = 2),
        outroLoop = OutroLoop(bars = 1, atMs = Specs.a.atMs + 2 * Specs.a.barMs),
        matchSide = Side.B,
    )

    @Test
    fun `a preset keeps the change's shape and none of its songs`() {
        val shape = MixPreset.shapeOf(custom())
        assertEquals(MixPreset.SONG_A, shape.a.song)
        assertEquals(MixPreset.SONG_B, shape.b.song)
        assertEquals(BeatFix.NONE, shape.a.fix)
        assertEquals("", shape.a.source)
        assertEquals(0L, shape.savedAt)
        assertTrue(shape.problems().toString(), shape.isValid)
        assertEquals(custom().moves, shape.moves)
        assertEquals(custom().curves, shape.curves)
        assertEquals(custom().muted, shape.muted)
    }

    @Test
    fun `laid on another pair it takes the shape and keeps that pair's places, fixes and start`() {
        val p = MixPreset("p1", "Long vocal swap", MixPreset.shapeOf(custom()))
        val laid = p.laidOn(other, LaneSet.PARTS)
        assertEquals(other.a, laid.a)
        assertEquals(other.b, laid.b)
        assertEquals(other.bStartMs, laid.bStartMs, 0.0)
        assertEquals(LaneSet.PARTS, laid.lanes)
        assertEquals(8, laid.bars)
        assertEquals(custom().moves, laid.moves)
        assertEquals(custom().curves, laid.curves)
        assertEquals(custom().muted, laid.muted)
        assertEquals(Side.B, laid.matchSide)
        // The loops where they sat against bar 1, in this pair's own bars.
        assertEquals(other.b.atMs + 4 * other.b.barMs, laid.introLoop!!.fromMs, 1e-6)
        assertEquals(2, laid.introLoop!!.times)
        assertEquals(other.a.atMs + 2 * other.a.barMs, laid.outroLoop!!.atMs, 1e-6)
        assertTrue(p.shapes(laid))
        assertFalse(p.shapes(other))
        // One move changed after: no longer this preset.
        val moved = laid.copy(moves = laid.moves + (LaneId.of("A.drums")!! to Move(3.0, 0.0, How.CUT)))
        assertFalse(p.shapes(moved))
    }

    @Test
    fun `a preset made on parts is laid on bands where the songs have none, and back`() {
        val p = MixPreset("p1", "Parts", MixPreset.shapeOf(Specs.vocalsOverCustom()))
        val onBands = p.laidOn(other, LaneSet.BANDS)
        assertEquals(LaneSet.BANDS, onBands.lanes)
        assertEquals(Presets.onBands(p.shape).moves, onBands.moves)
        assertTrue(onBands.isValid)
        val fade = MixPreset("p2", "Fade", MixPreset.shapeOf(Specs.fadeOnBands(4)))
        val onParts = fade.laidOn(other.copy(lanes = LaneSet.PARTS, moves = Presets.onParts(other).moves), LaneSet.PARTS)
        assertEquals(LaneSet.PARTS, onParts.lanes)
        assertTrue(fade.shapes(onParts))
    }

    @Test
    fun `presets are kept in a file that a new store reads back, in order, renamed and deleted`() {
        val files = dir.newFolder()
        val store = JsonFileMixPresetStore.at(files)
        val one = MixPreset("p1", "Long vocal swap", MixPreset.shapeOf(custom()))
        val two = MixPreset("p2", "Short fade", MixPreset.shapeOf(Specs.fadeOnBands(2)))
        store.put(one)
        store.put(two)
        assertEquals(listOf(one, two), store.all())
        assertTrue(File(files, JsonFileMixPresetStore.FILE).isFile)
        // A new store, as after an update: the same presets, the same shapes to the field.
        val again = JsonFileMixPresetStore.at(files)
        assertEquals(listOf(one, two), again.all())
        // Renamed in its place.
        again.put(one.copy(name = "Vocal swap"))
        assertEquals(listOf("Vocal swap", "Short fade"), JsonFileMixPresetStore.at(files).all().map { it.name })
        assertTrue(again.delete("p1"))
        assertFalse(again.delete("p1"))
        assertEquals(listOf(two), JsonFileMixPresetStore.at(files).all())
    }

    @Test
    fun `a damaged file is moved aside and the store starts empty`() {
        val files = dir.newFolder()
        val f = File(files, JsonFileMixPresetStore.FILE).also { it.parentFile!!.mkdirs(); it.writeText("{ not json") }
        val problems = ArrayList<String>()
        val store = JsonFileMixPresetStore.at(files) { m, _ -> problems += m }
        assertEquals(emptyList<MixPreset>(), store.all())
        assertTrue(File(f.parentFile, f.name + ".unreadable").isFile)
        assertEquals(1, problems.size)
        store.put(MixPreset("p1", "Fade", MixPreset.shapeOf(Specs.fadeOnBands())))
        assertEquals(1, JsonFileMixPresetStore.at(files).all().size)
    }

    @Test
    fun `a preset with no name is refused`() {
        val store = JsonFileMixPresetStore.at(dir.newFolder())
        val e = runCatching { store.put(MixPreset("p1", " ", MixPreset.shapeOf(Specs.fadeOnBands()))) }.exceptionOrNull()
        assertNotEquals(null, e)
    }
}
