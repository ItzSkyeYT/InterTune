/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.MixPreset
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * His ask of 9 Nov 2026: "let me create saved presets for the transitions". The change on the page
 * kept as a preset is laid on another pair in one tap, one undo step, its chip lit; a preset that
 * cannot fit the pair says why and changes nothing; both levels offer the presets.
 */
class MixUserPresetsTest {

    private val gridA = SongGrid(t0Ms = 281_340.0, periodMs = 60_000.0 / 111)
    private val gridB = SongGrid(t0Ms = 1_210.0, periodMs = 60_000.0 / 109)

    private fun facts(aWindowStartMs: Double = 236_000.0) = PairFacts(
        a = SongFacts("wake-me-up", "Wake Me Up", 308_000.0, aWindowStartMs, 308_000.0, gridA, focusMs = 290_000.0),
        b = SongFacts("after-hours", "After Hours", 361_000.0, 0.0, 60_000.0, gridB, firstSoundMs = 1_210.0, focusMs = 1_210.0),
    )

    /** Another pair, at other tempos and places. */
    private fun otherFacts() = PairFacts(
        a = SongFacts("lush-life", "Lush Life", 201_000.0, 120_000.0, 201_000.0, SongGrid(t0Ms = 150_120.0, periodMs = 60_000.0 / 98), focusMs = 180_000.0),
        b = SongFacts("lean-on", "Lean On", 176_000.0, 0.0, 60_000.0, SongGrid(t0Ms = 610.0, periodMs = 60_000.0 / 98), firstSoundMs = 610.0, focusMs = 610.0),
    )

    private fun editor(f: PairFacts = facts()) = MixEditor(f, AutoSpec.fallback(f), null)

    private val aMids = LaneId(Side.A, Lane.MIDS)

    /** A change made by hand: 4 bars, Cut's moves, A's mids muted. */
    private fun made(): MixPreset {
        val e = editor()
        e.setLength(4)
        assertEquals(4, e.spec.bars)
        e.tapPreset(Preset.CUT)
        e.toggleMute(aMids)
        val shape = e.presetShape()!!
        return MixPreset("p1", "Short cut", shape)
    }

    @Test
    fun `a preset is laid on another pair in one tap, one undo step, and its chip is lit`() {
        val p = made()
        val e = editor(otherFacts())
        val before = e.spec
        assertNull(e.litPreset(listOf(p)))
        assertNull(e.applyPreset(p))
        assertEquals(4, e.spec.bars)
        assertEquals(p.shape.moves, e.spec.moves)
        assertTrue(aMids in e.spec.muted)
        // Its own songs and places: only the shape is the preset's.
        assertEquals("lush-life", e.spec.a.song)
        assertEquals("lean-on", e.spec.b.song)
        assertEquals(p, e.litPreset(listOf(p)))
        assertTrue(e.dirty)
        assertTrue(e.undo())
        assertEquals(before, e.spec)
    }

    @Test
    fun `a preset longer than the pair can hold says why and changes nothing`() {
        val long = editor()
        long.setLength(16)
        assertEquals(16, long.spec.bars)
        val p = MixPreset("p2", "Long fade", long.presetShape()!!)
        // Only the last 20 s of the song before were read.
        val e = editor(facts(aWindowStartMs = 288_000.0))
        val before = e.spec
        assertNotNull(e.applyPreset(p))
        assertEquals(before, e.spec)
        assertFalse(e.canUndo)
    }

    @Test
    fun `a plain change is the Default chip, not a preset`() {
        val e = editor()
        assertTrue(e.plainChange())
        assertNull(e.presetShape())
    }

    @Test
    fun `for TalkBack a preset's chip is one node with its name, its state and a double tap that lays it on`() {
        // The layer over the chip took the touches and a long press, and was the node a double tap
        // reached: it had no name, and the chip under it, which had one, did nothing (10 Nov 2026).
        val chips = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixUserPresets.kt").readText()
        val body = chips.substringAfter("for ((index, p) in presets.withIndex()) {").substringBefore("DropdownMenu(expanded = menu")
        // The chip carries the tap, and its options as an action (a long press has no touch for TalkBack).
        assertTrue(body, "onClick = lay," in body.substringBefore("Over the chip"))
        assertTrue(body, "customActions = listOf(CustomAccessibilityAction(options) { menu = true; true })" in body)
        // Its name and whether it is lit are the chip's own: its label and `selected`.
        assertTrue(body, "label = { Text(p.name," in body)
        assertTrue(body, "selected = selected," in body)
        // The layer is out of the tree before its click is added, and no longer names the click for the chip.
        assertTrue(body, ".clearAndSetSemantics { }\n                    .combinedClickable(" in body)
        assertTrue(body, "onClickLabel" !in body)
    }

    @Test
    fun `both levels offer the presets, kept beside the saved changes`() {
        val dir = File("src/main/java/com/dd3boh/outertune")
        assertTrue("UserPresetChips(" in File(dir, "ui/screens/mix/MixEditorScreen.kt").readText())
        assertTrue("UserPresetChips(" in File(dir, "ui/screens/mix/MixSimpleSheet.kt").readText())
        assertTrue("JsonFileMixPresetStore.at(context.applicationContext.filesDir)" in File(dir, "playback/automix/MixStores.kt").readText())
    }
}
