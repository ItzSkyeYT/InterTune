/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Presets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Default among the presets (8 Nov 2026: "default which is just the song transitioning to the next
 * without anything extra"): first in the row on Advanced and on Simple, it makes the change plain,
 * and any other preset leaves it.
 */
class MixDefaultPresetTest {

    private fun facts() = PairFacts(
        a = SongFacts("am-i-dreaming", "Am I Dreaming", 256_041.0, 184_041.0, 256_041.0, SongGrid(t0Ms = 230_000.0, periodMs = 60_000.0 / 90), focusMs = 240_000.0),
        b = SongFacts("starboy", "Starboy", 230_461.0, 0.0, 60_000.0, SongGrid(t0Ms = 250.0, periodMs = 60_000.0 / 93), firstSoundMs = 250.0, focusMs = 250.0),
    )

    @Test
    fun `Default makes the change plain, and another preset or undo leaves it`() {
        val f = facts()
        val e = MixEditor(f, Presets.onParts(AutoSpec.fallback(f)), null)
        assertFalse(e.spec.plain)
        val before = e.spec
        assertTrue(e.plainChange())
        assertTrue(e.spec.plain)
        assertEquals("a listener's plain change lights no preset of the enum: Default stands for it", null, e.lit())
        assertFalse("tapped again, nothing", e.plainChange())
        assertTrue(e.undo())
        assertEquals(before, e.spec)
        assertTrue(e.plainChange())
        e.tapPreset(Preset.FADE)
        assertFalse(e.spec.plain)
        assertEquals(Preset.FADE, e.lit())
    }

    @Test
    fun `Default is the first chip on both levels, lit whenever the change is plain`() {
        for (path in listOf("MixEditorScreen.kt", "MixSimpleSheet.kt")) {
            val source = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/$path").readText()
            val row = source.substringAfter("val chips: @Composable () -> Unit = {").substringBefore("for (p in EditorPresets.ORDER)")
            assertTrue(path, "DefaultChip(lit = spec.plain," in row)
            assertTrue(path, "vm.edit { plainChange() }" in row)
        }
    }
}
