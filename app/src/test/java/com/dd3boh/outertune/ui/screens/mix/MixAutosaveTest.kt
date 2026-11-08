/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.BeatFix
import com.dd3boh.outertune.mix.edit.Preset
import com.dd3boh.outertune.mix.edit.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The editor saves by itself (8 Nov 2026: "Can we also make it so the transitions AUTOSAVE"): a
 * moment after each edit, and on leaving the page, so Back keeps the change and asks nothing.
 */
class MixAutosaveTest {

    private fun facts() = PairFacts(
        a = SongFacts("am-i-dreaming", "Am I Dreaming", 256_041.0, 184_041.0, 256_041.0, SongGrid(t0Ms = 230_000.0, periodMs = 60_000.0 / 90), focusMs = 240_000.0),
        b = SongFacts("starboy", "Starboy", 230_461.0, 0.0, 60_000.0, SongGrid(t0Ms = 250.0, periodMs = 60_000.0 / 93), firstSoundMs = 250.0, focusMs = 250.0),
    )

    @Test
    fun `a saved page is clean until the next edit, beat fixes included`() {
        val f = facts()
        val e = MixEditor(f, AutoSpec.fallback(f), null)
        assertFalse(e.dirty)
        e.tapPreset(Preset.FADE)
        assertTrue(e.dirty)
        val written = e.spec
        e.markSaved(written)
        assertFalse("saved", e.dirty)
        assertTrue("what was saved is kept, not Auto's", e.saveAction(1L) is SaveAction.Keep)
        e.tapPreset(Preset.CUT)
        assertTrue(e.dirty)
        e.markSaved(e.spec)
        assertTrue(e.setFix(Side.B, BeatFix(bpm = 186.0)))
        assertTrue("a beat fix is an edit to save", e.dirty)
        assertEquals(setOf("starboy"), e.fixesChanged().keys)
        e.markSaved(e.spec)
        assertFalse("the fix saved stays saved", e.dirty)
        assertTrue(e.fixesChanged().isEmpty())
    }

    @Test
    fun `each edit is saved a moment later, and leaving saves what is left`() {
        val vm = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixEditorViewModel.kt").readText()
        val publish = vm.substringAfter("fun publish() {").substringBefore("\n    }\n")
        assertTrue("every edit shown schedules the save", publish.trimEnd().endsWith("scheduleAutosave()"))
        val schedule = vm.substringAfter("private fun scheduleAutosave() {").substringBefore("\n    }\n")
        assertTrue(schedule, "delay(AUTOSAVE_AFTER_MS)" in schedule && "saveNow()" in schedule)
        val now = vm.substringAfter("private suspend fun saveNow(): Boolean {").substringBefore("\n    }\n")
        assertTrue("marked with what was written, not what the page has by then", "val written = e.spec" in now && "e.markSaved(written)" in now)
        val cleared = vm.substringAfter("override fun onCleared() {").substringBefore("\n    }\n")
        assertTrue("leaving writes what was not saved yet, past the page", "KeptChanges.scope.launch" in cleared && "write(pending)" in cleared)
    }

    @Test
    fun `Back keeps the change and asks nothing`() {
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixEditorScreen.kt").readText()
        assertFalse("no Discard question", "mix_edit_discard_title" in screen)
        assertTrue("Back is Adjust's alone", "ScreenBackHandler(enabled = canvas.adjust != null) { leaveAdjust(vm, canvas) }" in screen)
        assertTrue("the button says the change is saved", "R.string.mix_edit_saved_state" in screen)
    }
}
