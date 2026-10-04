/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.QuickPicksSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Quick picks leans toward on the Recommendations page: its options, what is stored, and the lines that follow the choice. */
class LeanChoiceTest {
    private val leans = Lean.entries.filter { it != Lean.AUTO }
    private val chips = listOf(ContextChip.AUTO, ContextChip.DISCOVER, ContextChip.FAVOURITES, ContextChip.FOCUS, ContextChip.CHILL, ContextChip.PARTY)

    private fun line(choice: Lean, source: QuickPicksSource = QuickPicksSource.ENGINE, chip: Int = ContextChip.AUTO, newOnly: Boolean = false, dial: Double = 0.5) =
        LeanChoice.line(choice, source, chip, newOnly, dial)

    @Test
    fun `Auto comes first, then the poll's four in its order, each lean once`() {
        assertEquals(listOf(Lean.AUTO, Lean.NEW, Lean.ARTIST, Lean.FORGOTTEN, Lean.SIMILAR), LeanChoice.options)
        // A lean added to the engine has to be given a place here too, or nobody could choose it.
        assertEquals(Lean.entries.toSet(), LeanChoice.options.toSet())
        assertEquals(Lean.entries.size, LeanChoice.options.size)
    }

    @Test
    fun `nothing stored, or a name this version does not know, shows Auto`() {
        assertEquals(Lean.AUTO, LeanChoice.selected(null))
        assertEquals(Lean.AUTO, LeanChoice.selected(""))
        assertEquals(Lean.AUTO, LeanChoice.selected("SEASONAL"))
        // Names are kept exactly: a near miss is not guessed at.
        assertEquals(Lean.AUTO, LeanChoice.selected("new"))
        assertEquals(Lean.AUTO, LeanChoice.selected(" NEW"))
        assertEquals(Lean.AUTO, LeanChoice.selected("1"))
    }

    @Test
    fun `what a choice stores is read back as that choice, here and on Home`() {
        for (choice in LeanChoice.options) {
            assertEquals(choice, LeanChoice.selected(LeanChoice.stored(choice)))
            // Home reads the preference with Lean.ofName.
            assertEquals(choice, Lean.ofName(LeanChoice.stored(choice)))
        }
        assertEquals(setOf("AUTO", "NEW", "ARTIST", "FORGOTTEN", "SIMILAR"), LeanChoice.options.map { LeanChoice.stored(it) }.toSet())
        // The page and Home read one value the same way, whatever it is.
        for (stored in listOf(null, "", "AUTO", "NEW", "ARTIST", "FORGOTTEN", "SIMILAR", "SEASONAL")) assertEquals(Lean.ofName(stored), LeanChoice.selected(stored))
    }

    @Test
    fun `Auto has no line, whatever the source, the chip or New songs only`() {
        for (source in QuickPicksSource.entries) for (chip in chips) for (newOnly in listOf(false, true)) {
            assertNull(line(Lean.AUTO, source, chip, newOnly))
        }
    }

    @Test
    fun `with another source filling Quick picks the line says where the choice applies`() {
        for (choice in leans) for (source in listOf(QuickPicksSource.YOUTUBE, QuickPicksSource.LIBRARY, QuickPicksSource.OFF)) {
            assertEquals(LeanLine.NotSource, line(choice, source))
            // The source comes first: a chip left on changes nothing about a row that is not showing.
            assertEquals(LeanLine.NotSource, line(choice, source, ContextChip.FAVOURITES, newOnly = true))
        }
    }

    @Test
    fun `with Best recommendations the choice holds twelve cards, at any Adventurousness`() {
        for (choice in leans) for (dial in listOf(0.0, 0.5, 1.0)) assertEquals(LeanLine.Leading(12), line(choice, dial = dial))
    }

    @Test
    fun `Discover under Never heard gives it sixteen`() {
        assertEquals(LeanLine.Leading(16), line(Lean.NEW, chip = ContextChip.DISCOVER))
    }

    @Test
    fun `a mood keeps the choice, except Playing now`() {
        for (mood in ContextChip.MOODS) {
            for (choice in listOf(Lean.NEW, Lean.ARTIST, Lean.FORGOTTEN)) assertEquals(LeanLine.Leading(12), line(choice, chip = mood))
            assertEquals(LeanLine.SetAside(newOnly = false, chip = mood), line(Lean.SIMILAR, chip = mood))
        }
    }

    @Test
    fun `Favourites sets every choice aside, Discover all but Never heard`() {
        for (choice in leans) assertEquals(LeanLine.SetAside(false, ContextChip.FAVOURITES), line(choice, chip = ContextChip.FAVOURITES))
        for (choice in leans.filter { it != Lean.NEW }) assertEquals(LeanLine.SetAside(false, ContextChip.DISCOVER), line(choice, chip = ContextChip.DISCOVER))
    }

    @Test
    fun `New songs only takes the whole row from every choice but Never heard, and is named before any chip`() {
        for (choice in leans.filter { it != Lean.NEW }) for (chip in chips) {
            assertEquals("${choice.name} under chip $chip", LeanLine.SetAside(newOnly = true, chip = chip), line(choice, chip = chip, newOnly = true))
        }
    }

    @Test
    fun `Never heard is not set aside by New songs only, it holds it to songs never started`() {
        // Whatever the chip, and under Try both as well: the engine's cards follow the stricter rule.
        for (chip in chips) for (source in listOf(QuickPicksSource.ENGINE, QuickPicksSource.COMPARE)) {
            assertEquals("chip $chip, $source", LeanLine.StrictNew, line(Lean.NEW, source, chip = chip, newOnly = true))
        }
        // The line is the engine's own rule: said exactly when a build keeps the stricter rule.
        for (choice in leans) for (chip in chips) for (newOnly in listOf(false, true)) {
            assertEquals("${choice.name} chip $chip newOnly $newOnly", EngineRow.shape(EngineParams.DEFAULT, 0.5, newOnly, false, chip, choice).strictNew, line(choice, chip = chip, newOnly = newOnly) == LeanLine.StrictNew)
        }
        // With the switch off the choice leads as usual, and another source still comes first.
        assertEquals(LeanLine.Leading(12), line(Lean.NEW))
        assertEquals(LeanLine.NotSource, line(Lean.NEW, QuickPicksSource.YOUTUBE, newOnly = true))
    }

    @Test
    fun `the line says set aside exactly when the build follows no lean`() {
        // The engine's own rule decides, so the page cannot promise twelve cards the row will not have.
        for (choice in leans) for (chip in chips) for (newOnly in listOf(false, true)) {
            val shape = EngineRow.shape(EngineParams.DEFAULT, 0.5, newOnly, false, chip, choice)
            val said = line(choice, chip = chip, newOnly = newOnly)
            assertEquals("${choice.name} chip $chip newOnly $newOnly", shape.lean == Lean.AUTO, said is LeanLine.SetAside)
            assertEquals("${choice.name} chip $chip newOnly $newOnly", shape.lead != null, said is LeanLine.Leading)
            if (said is LeanLine.Leading) assertEquals(shape.quotas[shape.lead], said.cards)
        }
    }

    @Test
    fun `Try both says about six of the row and two of the first four, and is set aside like the engine's own row`() {
        for (choice in leans) assertEquals(LeanLine.TryBoth(6, 2), line(choice, QuickPicksSource.COMPARE))
        assertEquals(LeanLine.SetAside(false, ContextChip.FAVOURITES), line(Lean.ARTIST, QuickPicksSource.COMPARE, chip = ContextChip.FAVOURITES))
        assertEquals(LeanLine.SetAside(true, ContextChip.AUTO), line(Lean.FORGOTTEN, QuickPicksSource.COMPARE, newOnly = true))
    }

    @Test
    fun `with no choice the two sliders' lines count what they always did`() {
        for (adventurousness in 0..100) for (familiarity in 0..60 step 5) {
            val dial = adventurousness / 100.0
            val f = familiarity / 100.0
            // The page's own arithmetic from before the choice existed.
            assertEquals(quotas(20, dial, false)[Lane.EXPLORE], LeanChoice.newCards(Lean.AUTO, dial, f))
            assertEquals(quotas(20, dial, false, EngineParams.DEFAULT.withFamiliarity(f))[Lane.AGAIN], LeanChoice.againCards(Lean.AUTO, dial, f))
        }
        assertEquals(2, LeanChoice.newCards(Lean.AUTO, 0.5, 0.25))
        assertEquals(4, LeanChoice.againCards(Lean.AUTO, 0.5, 0.25))
    }

    @Test
    fun `under a choice the sliders' lines count the cards the row really gives`() {
        // Never heard: the slider no longer sets how many are new.
        for (adventurousness in 0..100 step 10) assertEquals(12, LeanChoice.newCards(Lean.NEW, adventurousness / 100.0, 0.25))
        // The others leave eight cards to the usual mix, and the lines count their share of those.
        assertEquals(1, LeanChoice.newCards(Lean.ARTIST, 0.5, 0.25))
        assertEquals(1, LeanChoice.newCards(Lean.FORGOTTEN, 0.5, 0.25))
        assertEquals(1, LeanChoice.newCards(Lean.SIMILAR, 0.5, 0.25))
        assertEquals(2, LeanChoice.againCards(Lean.NEW, 0.5, 0.25))
        assertEquals(2, LeanChoice.againCards(Lean.ARTIST, 0.5, 0.25))
        assertEquals(2, LeanChoice.againCards(Lean.FORGOTTEN, 0.5, 0.25))
        assertEquals(3, LeanChoice.againCards(Lean.SIMILAR, 0.5, 0.25))
        for (choice in leans) for (adventurousness in 0..100 step 10) for (familiarity in 0..60 step 5) {
            val dial = adventurousness / 100.0
            val f = familiarity / 100.0
            val built = EngineRow.shape(EngineParams.DEFAULT.withFamiliarity(f), dial, false, false, ContextChip.AUTO, choice).quotas
            assertEquals(built[Lane.EXPLORE], LeanChoice.newCards(choice, dial, f))
            assertEquals(built[Lane.AGAIN], LeanChoice.againCards(choice, dial, f))
            // Never more than the eight cards the choice leaves, unless the lane is the choice's own.
            if (choice != Lean.NEW) assertTrue(LeanChoice.newCards(choice, dial, f) <= 8)
            assertTrue(LeanChoice.againCards(choice, dial, f) <= 8)
        }
    }

    @Test
    fun `the sliders' lines follow the choice only while it leads the row`() {
        for (choice in leans) for (chip in chips) for (newOnly in listOf(false, true)) for (adventurousness in 0..100 step 25) for (familiarity in 0..60 step 20) {
            val dial = adventurousness / 100.0
            val f = familiarity / 100.0
            val shape = EngineRow.shape(EngineParams.DEFAULT.withFamiliarity(f), dial, newOnly, false, chip, choice)
            val what = "${choice.name} chip $chip newOnly $newOnly"
            if (shape.lead != null) {
                // Under a chip that keeps the choice, the lines count the row the engine builds.
                assertEquals(what, shape.quotas[Lane.EXPLORE], LeanChoice.newCards(choice, dial, f, chip, newOnly))
                assertEquals(what, shape.quotas[Lane.AGAIN], LeanChoice.againCards(choice, dial, f, chip, newOnly))
            } else {
                // Set aside by a chip or New songs only, the row is built as with no choice, and
                // the lines must not count a lean that is not there.
                assertEquals(what, LeanChoice.newCards(Lean.AUTO, dial, f), LeanChoice.newCards(choice, dial, f, chip, newOnly))
                assertEquals(what, LeanChoice.againCards(Lean.AUTO, dial, f), LeanChoice.againCards(choice, dial, f, chip, newOnly))
                assertEquals(what, false, LeanChoice.newSetByChoice(choice, chip, newOnly))
            }
        }
        // Your artists with Favourites on, at the default dials: the lines read as they did before the choice, not one and two.
        assertEquals(2, LeanChoice.newCards(Lean.ARTIST, 0.5, 0.25, ContextChip.FAVOURITES))
        assertEquals(4, LeanChoice.againCards(Lean.ARTIST, 0.5, 0.25, ContextChip.FAVOURITES))
    }

    @Test
    fun `Never heard sets the new cards itself while it leads, sixteen of them with Discover`() {
        assertTrue(LeanChoice.newSetByChoice(Lean.NEW, ContextChip.AUTO, false))
        assertTrue(LeanChoice.newSetByChoice(Lean.NEW, ContextChip.DISCOVER, false))
        for (mood in ContextChip.MOODS) assertTrue(LeanChoice.newSetByChoice(Lean.NEW, mood, false))
        for (adventurousness in 0..100 step 10) assertEquals(16, LeanChoice.newCards(Lean.NEW, adventurousness / 100.0, 0.25, ContextChip.DISCOVER))
        // The line under the choice and the Adventurousness line give one count.
        assertEquals(LeanLine.Leading(LeanChoice.newCards(Lean.NEW, 0.5, 0.25, ContextChip.DISCOVER)), line(Lean.NEW, chip = ContextChip.DISCOVER))
        // Favourites or New songs only takes the row, and the slider's own line is back.
        assertEquals(false, LeanChoice.newSetByChoice(Lean.NEW, ContextChip.FAVOURITES, false))
        assertEquals(false, LeanChoice.newSetByChoice(Lean.NEW, ContextChip.AUTO, true))
        // No other choice sets it, and Auto never does.
        for (choice in Lean.entries.filter { it != Lean.NEW }) for (chip in chips) assertEquals(false, LeanChoice.newSetByChoice(choice, chip, false))
    }
}
