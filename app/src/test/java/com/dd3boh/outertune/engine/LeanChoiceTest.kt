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

    private fun line(choice: Lean, source: QuickPicksSource = QuickPicksSource.ENGINE, chip: Int = ContextChip.AUTO, dial: Double = 0.5) =
        LeanChoice.line(choice, source, chip, dial)

    @Test
    fun `Auto comes first, then the poll's in its order, each lean once`() {
        // Never heard, the poll's first, was taken out: songs never heard are the Discover chip's.
        assertEquals(listOf(Lean.AUTO, Lean.ARTIST, Lean.FORGOTTEN, Lean.SIMILAR), LeanChoice.options)
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
        assertEquals(Lean.AUTO, LeanChoice.selected("artist"))
        assertEquals(Lean.AUTO, LeanChoice.selected(" ARTIST"))
        assertEquals(Lean.AUTO, LeanChoice.selected("2"))
        // Never heard's name, should one outlive the move to Discover (NeverHeardToDiscover), is Auto.
        assertEquals(Lean.AUTO, LeanChoice.selected("NEW"))
    }

    @Test
    fun `what a choice stores is read back as that choice, here and on Home`() {
        for (choice in LeanChoice.options) {
            assertEquals(choice, LeanChoice.selected(LeanChoice.stored(choice)))
            // Home reads the preference with Lean.ofName.
            assertEquals(choice, Lean.ofName(LeanChoice.stored(choice)))
        }
        assertEquals(setOf("AUTO", "ARTIST", "FORGOTTEN", "SIMILAR"), LeanChoice.options.map { LeanChoice.stored(it) }.toSet())
        // The page and Home read one value the same way, whatever it is.
        for (stored in listOf(null, "", "AUTO", "NEW", "ARTIST", "FORGOTTEN", "SIMILAR", "SEASONAL")) assertEquals(Lean.ofName(stored), LeanChoice.selected(stored))
    }

    @Test
    fun `Auto has no line, whatever the source or the chip`() {
        for (source in QuickPicksSource.entries) for (chip in chips) {
            assertNull(line(Lean.AUTO, source, chip))
        }
    }

    @Test
    fun `with another source filling Quick picks the line says where the choice applies`() {
        for (choice in leans) for (source in listOf(QuickPicksSource.YOUTUBE, QuickPicksSource.LIBRARY, QuickPicksSource.OFF)) {
            assertEquals(LeanLine.NotSource, line(choice, source))
            // The source comes first: a chip left on changes nothing about a row that is not showing.
            assertEquals(LeanLine.NotSource, line(choice, source, ContextChip.DISCOVER))
        }
    }

    @Test
    fun `with Best recommendations the choice holds twelve cards, at any Adventurousness`() {
        for (choice in leans) for (dial in listOf(0.0, 0.5, 1.0)) assertEquals(LeanLine.Leading(12), line(choice, dial = dial))
    }

    @Test
    fun `a mood keeps the choice, except Playing now`() {
        for (mood in ContextChip.MOODS) {
            for (choice in listOf(Lean.ARTIST, Lean.FORGOTTEN)) assertEquals(LeanLine.Leading(12), line(choice, chip = mood))
            assertEquals(LeanLine.SetAside(chip = mood), line(Lean.SIMILAR, chip = mood))
        }
    }

    @Test
    fun `Favourites and Discover set every choice aside`() {
        for (choice in leans) assertEquals(LeanLine.SetAside(ContextChip.FAVOURITES), line(choice, chip = ContextChip.FAVOURITES))
        // Discover's row is songs never heard, every card, so no choice has a lane in it.
        for (choice in leans) assertEquals(LeanLine.SetAside(ContextChip.DISCOVER), line(choice, chip = ContextChip.DISCOVER))
    }

    @Test
    fun `the line says set aside exactly when the build follows no lean`() {
        // The engine's own rule decides, so the page cannot promise twelve cards the row will not have.
        for (choice in leans) for (chip in chips) {
            val shape = EngineRow.shape(EngineParams.DEFAULT, 0.5, false, chip, choice)
            val said = line(choice, chip = chip)
            assertEquals("${choice.name} chip $chip", shape.lean == Lean.AUTO, said is LeanLine.SetAside)
            assertEquals("${choice.name} chip $chip", shape.lead != null, said is LeanLine.Leading)
            if (said is LeanLine.Leading) assertEquals(shape.quotas[shape.lead], said.cards)
        }
    }

    @Test
    fun `Try both says about six of the row and two of the first four, and is set aside like the engine's own row`() {
        for (choice in leans) assertEquals(LeanLine.TryBoth(6, 2), line(choice, QuickPicksSource.COMPARE))
        assertEquals(LeanLine.SetAside(ContextChip.FAVOURITES), line(Lean.ARTIST, QuickPicksSource.COMPARE, chip = ContextChip.FAVOURITES))
        assertEquals(LeanLine.SetAside(ContextChip.DISCOVER), line(Lean.FORGOTTEN, QuickPicksSource.COMPARE, chip = ContextChip.DISCOVER))
    }

    @Test
    fun `with no choice the two sliders' lines count the row as it is built`() {
        for (adventurousness in 0..100) for (familiarity in 0..60 step 5) {
            val dial = adventurousness / 100.0
            val f = familiarity / 100.0
            // Both under both sliders, as the row itself is counted. The new songs' line used to
            // leave Familiarity out, and was a card off the row wherever the rounding fell otherwise.
            val row = quotas(20, dial, false, EngineParams.DEFAULT.withFamiliarity(f))
            assertEquals(row[Lane.EXPLORE], LeanChoice.newCards(Lean.AUTO, dial, f))
            assertEquals(row[Lane.AGAIN], LeanChoice.againCards(Lean.AUTO, dial, f))
        }
        assertEquals(2, LeanChoice.newCards(Lean.AUTO, 0.5, 0.25))
        assertEquals(4, LeanChoice.againCards(Lean.AUTO, 0.5, 0.25))
    }

    @Test
    fun `under a choice the sliders' lines count the cards the row really gives`() {
        // Each choice leaves eight cards to the usual mix, and the lines count their share of those.
        assertEquals(1, LeanChoice.newCards(Lean.ARTIST, 0.5, 0.25))
        assertEquals(1, LeanChoice.newCards(Lean.FORGOTTEN, 0.5, 0.25))
        assertEquals(1, LeanChoice.newCards(Lean.SIMILAR, 0.5, 0.25))
        assertEquals(2, LeanChoice.againCards(Lean.ARTIST, 0.5, 0.25))
        assertEquals(2, LeanChoice.againCards(Lean.FORGOTTEN, 0.5, 0.25))
        assertEquals(3, LeanChoice.againCards(Lean.SIMILAR, 0.5, 0.25))
        for (choice in leans) for (adventurousness in 0..100 step 10) for (familiarity in 0..60 step 5) {
            val dial = adventurousness / 100.0
            val f = familiarity / 100.0
            val built = EngineRow.shape(EngineParams.DEFAULT.withFamiliarity(f), dial, false, ContextChip.AUTO, choice).quotas
            assertEquals(built[Lane.EXPLORE], LeanChoice.newCards(choice, dial, f))
            assertEquals(built[Lane.AGAIN], LeanChoice.againCards(choice, dial, f))
            // Never more than the eight cards the choice leaves.
            assertTrue(LeanChoice.newCards(choice, dial, f) <= 8)
            assertTrue(LeanChoice.againCards(choice, dial, f) <= 8)
        }
    }

    @Test
    fun `the sliders' lines follow the choice only while it leads the row`() {
        for (choice in leans) for (chip in chips) for (adventurousness in 0..100 step 25) for (familiarity in 0..60 step 20) {
            val dial = adventurousness / 100.0
            val f = familiarity / 100.0
            val shape = EngineRow.shape(EngineParams.DEFAULT.withFamiliarity(f), dial, false, chip, choice)
            val what = "${choice.name} chip $chip"
            if (shape.lead != null) {
                // Under a chip that keeps the choice, the lines count the row the engine builds.
                assertEquals(what, shape.quotas[Lane.EXPLORE], LeanChoice.newCards(choice, dial, f, chip))
                assertEquals(what, shape.quotas[Lane.AGAIN], LeanChoice.againCards(choice, dial, f, chip))
            } else {
                // Set aside by a chip, the row is built as with no choice, and the lines must not
                // count a lean that is not there.
                assertEquals(what, LeanChoice.newCards(Lean.AUTO, dial, f), LeanChoice.newCards(choice, dial, f, chip))
                assertEquals(what, LeanChoice.againCards(Lean.AUTO, dial, f), LeanChoice.againCards(choice, dial, f, chip))
            }
        }
        // Your artists with Favourites on, at the default dials: the lines read as they did before the choice, not one and two.
        assertEquals(2, LeanChoice.newCards(Lean.ARTIST, 0.5, 0.25, ContextChip.FAVOURITES))
        assertEquals(4, LeanChoice.againCards(Lean.ARTIST, 0.5, 0.25, ContextChip.FAVOURITES))
    }

    @Test
    fun `no choice sets the new cards itself, and under Discover the sliders' lines are the usual row's`() {
        // The Adventurousness line is always the slider's: no choice has the new-to-you lane, and
        // Discover, whose row is all new for now, sets every choice aside.
        for (choice in LeanChoice.options) assertTrue(choice.lane != Lane.EXPLORE)
        for (choice in leans) for (adventurousness in 0..100 step 10) {
            val dial = adventurousness / 100.0
            assertEquals(LeanChoice.newCards(Lean.AUTO, dial, 0.25), LeanChoice.newCards(choice, dial, 0.25, ContextChip.DISCOVER))
            assertEquals(LeanChoice.againCards(Lean.AUTO, dial, 0.25), LeanChoice.againCards(choice, dial, 0.25, ContextChip.DISCOVER))
        }
    }
}
