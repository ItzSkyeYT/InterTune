/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The 0.14 engine's Auto (Unreleased.NEW_ENGINE, step 2 of experiments/features/recs-014/STEPS.md): the row the
 * flag builds under Auto is the arm the crowd and the replay measured, nothing else changes, and the developer's two
 * switches it replaced are gone from the code.
 */
class NewAutoTest {
    private val main = File("src/main/java/com/dd3boh/outertune").takeIf { it.isDirectory } ?: File("app/src/main/java/com/dd3boh/outertune")

    private fun code(path: String) = File(main, path).readText()

    @Test
    fun `under the flag Auto is the arm that was measured, MIX+ranker+cap3`() {
        assertEquals(TrialArm.lean("MIX+ranker+cap3"), NewAuto.lean(Lean.AUTO, on = true))
        // The slider in the middle nudges nothing, so the row is the measured arm exactly.
        assertEquals(TrialArm.params("MIX+ranker+cap3"), NewAuto.params(EngineParams.DEFAULT, Lean.AUTO, on = true, adventurousness = 50))
    }

    @Test
    fun `the slider nudges the learned mix, fewer new songs to the left, more to the right`() {
        val left = NewAuto.params(EngineParams.DEFAULT, Lean.AUTO, on = true, adventurousness = 0)
        val right = NewAuto.params(EngineParams.DEFAULT, Lean.AUTO, on = true, adventurousness = 100)
        assertTrue("left: ${left.mixNudge}", left.mixNudge < 0)
        assertTrue("right: ${right.mixNudge}", right.mixNudge > 0)
        assertEquals(MixShare.nudge(0), left.mixNudge)
    }

    @Test
    fun `with the flag off, and for a lean the listener picks, the row is built as before`() {
        val p = EngineParams.DEFAULT.copy(maxPerArtist = 2)
        for (lean in Lean.entries) {
            assertEquals(lean, NewAuto.lean(lean, on = false))
            assertEquals(p, NewAuto.params(p, lean, on = false, adventurousness = 80))
            assertFalse(NewAuto.applies(lean, on = false))
            if (lean == Lean.AUTO) continue
            assertEquals(lean, NewAuto.lean(lean, on = true))
            assertEquals(p, NewAuto.params(p, lean, on = true, adventurousness = 80))
        }
    }

    @Test
    fun `Home builds and Settings draws Auto through the flag, and the two developer switches are gone`() {
        val home = code("viewmodels/HomeViewModel.kt")
        assertTrue("storedLean reads NewAuto.lean under the flag", "NewAuto.lean(Lean.ofName(context.dataStore[QuickPicksLeanKey]), Unreleased.NEW_ENGINE)" in home)
        assertTrue("engineParams reads NewAuto.params under the flag", Regex("""NewAuto\.params\(it, Lean\.ofName\(context\.dataStore\[QuickPicksLeanKey]\), Unreleased\.NEW_ENGINE""").containsMatchIn(home))
        assertTrue("Settings asks NewAuto whether Auto is the new one", "val newAuto = NewAuto.applies(lean, Unreleased.NEW_ENGINE)" in code("ui/screens/settings/RecommendationsSettings.kt"))
        val left = main.walkTopDown().filter { it.extension == "kt" }
            .filter { f -> listOf("FamiliarFirstKey", "FamiliarMixKey", "R.string.familiar_first", "R.string.familiar_mix").any { it in f.readText() } }
            .map { it.name }.toList()
        assertTrue("the developer's switches are still read in $left", left.isEmpty())
    }

    @Test
    fun `Discover keeps its own settings`() {
        // Its build reads the overrides and Familiarity, never engineParams(), so the new Auto's cap and mix stay out of it.
        val home = code("viewmodels/HomeViewModel.kt")
        val discover = home.substringAfter("private suspend fun buildDiscoverRow(").substringBefore("\n    private ")
        assertFalse("Discover's build reads engineParams()", "engineParams(" in discover)
        assertFalse("Discover's build reads NewAuto", "NewAuto" in discover)
    }
}
