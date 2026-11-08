/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import com.dd3boh.outertune.mix.edit.Key
import com.dd3boh.outertune.mix.edit.Lane
import com.dd3boh.outertune.mix.edit.LaneId
import com.dd3boh.outertune.mix.edit.LaneSet
import com.dd3boh.outertune.mix.edit.Presets
import com.dd3boh.outertune.mix.edit.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A single part's curve edited in Adjust (7 Nov 2026: "edits on a single stem doesn't save even if I
 * press save, plus the backwards/forwards doesn't apply"): every finished edit is an undo step, the
 * first going back to the page before Adjust opened, and Save keeps what Adjust shows.
 */
class MixAdjustStepsTest {

    private fun facts() = PairFacts(
        a = SongFacts("wake-me-up", "Wake Me Up", 308_000.0, 236_000.0, 308_000.0, SongGrid(t0Ms = 281_340.0, periodMs = 60_000.0 / 111), focusMs = 290_000.0),
        b = SongFacts("after-hours", "After Hours", 361_000.0, 0.0, 60_000.0, SongGrid(t0Ms = 1_210.0, periodMs = 60_000.0 / 109), firstSoundMs = 1_210.0, focusMs = 1_210.0),
    )

    @Test
    fun `each edit in Adjust is an undo step, and Save keeps the last`() {
        val f = facts()
        val e = MixEditor(f, Presets.onParts(AutoSpec.fallback(f)), null)
        assertEquals(LaneSet.PARTS, e.spec.lanes)
        val voice = LaneId(Side.A, Lane.VOCALS)
        // Adjust opens: the lane's curve drawn from its move, not yet an undo step.
        val beforeAdjust = e.spec
        e.live(e.curveSet(e.spec, voice, e.curveFromMove(voice).gain))
        // First edit: its step goes back to the page before Adjust opened (adjustStep).
        val g1 = e.spec
        e.live(e.curveSet(e.spec, voice, e.spec.curves.getValue(voice).gain + Key(1.5, 0.3)))
        assertTrue(e.commit(beforeAdjust))
        val afterFirst = e.spec
        // Second edit: its own step.
        e.live(e.curveSet(e.spec, voice, e.spec.curves.getValue(voice).gain + Key(2.5, 0.6)))
        assertTrue(e.commit(afterFirst))
        val afterSecond = e.spec
        assertTrue(g1 != afterFirst)

        val kept = e.saveAction(1L)
        assertTrue("Save keeps the edited part", kept is SaveAction.Keep && voice in kept.spec.curves && kept.spec.lanes == LaneSet.PARTS)

        assertTrue(e.undo())
        assertEquals(afterFirst, e.spec)
        assertTrue(e.undo())
        assertEquals("the first step goes back to before Adjust", beforeAdjust, e.spec)
        assertFalse(voice in e.spec.curves)
        assertTrue(e.redo())
        assertTrue(e.redo())
        assertEquals(afterSecond, e.spec)
    }

    @Test
    fun `undo, redo and Save stay on in Adjust, and Save closes Adjust first`() {
        val screen = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixEditorScreen.kt").readText()
        assertFalse("undo, redo and Save are not turned off in Adjust", "canvas.adjust == null &&" in screen.substringAfter("IconButton(onClick = { step(vm, canvas, back = true) }").substringBefore("FilledTonalButton"))
        assertTrue("if (canvas.adjust != null) leaveAdjust(vm, canvas)\n                            save(vm, context, haptic, navController)" in screen)
        val leave = screen.substringAfter("private fun leaveAdjust(").substringBefore("\n}\n")
        assertFalse("leaving Adjust adds no step of its own", "commit(" in leave)
        val view = File("src/main/java/com/dd3boh/outertune/ui/screens/mix/MixCanvasView.kt").readText()
        val step = view.substringAfter("internal fun adjustStep(").substringBefore("\n}\n")
        assertTrue(step, "editor.commit(from)" in step)
        assertFalse("no edit in Adjust stays a draft", "vm.adjustEdited = true\n                vm.publish()" in view)
    }
}
