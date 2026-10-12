/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.component

import com.dd3boh.outertune.engine.ContextChip
import com.dd3boh.outertune.engine.EngineParams
import com.dd3boh.outertune.engine.EngineRow
import com.dd3boh.outertune.engine.Lane
import com.dd3boh.outertune.engine.Lean
import com.dd3boh.outertune.engine.LeanChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The picture of the Best recommendations row over its settings: twenty tiles, each the colour of
 * a kind of card. What it has to get right is the count of each kind, which is the row's own, and
 * that a small change of a slider is a small change of the picture.
 */
class RowPictureTest {

    private fun mix(lean: Lean = Lean.AUTO, adventurous: Int = 50, familiar: Int = 25, chip: Int = ContextChip.AUTO, newOnly: Boolean = false) =
        LeanChoice.mix(lean, adventurous / 100.0, familiar / 100.0, chip, newOnly)

    @Test
    fun `the tiles are the row's own cards, kind for kind, wherever the controls stand`() {
        for (lean in LeanChoice.options) for (adventurous in 0..100 step 5) for (familiar in 0..60 step 5) for (newOnly in listOf(false, true)) {
            for (chip in listOf(ContextChip.AUTO, ContextChip.DISCOVER, ContextChip.FAVOURITES)) {
                val mix = mix(lean, adventurous, familiar, chip, newOnly)
                val shape = EngineRow.shape(EngineParams.DEFAULT.withFamiliarity(familiar / 100.0), adventurous / 100.0, newOnly, false, chip, lean)
                assertEquals(shape.quotas.filterValues { it > 0 }, mix.cards.filterValues { it > 0 })
                assertEquals(shape.lead, mix.lead)
                val places = rowPlaces(mix)
                assertEquals(ROW_PLACES, places.size)
                assertEquals("$lean $adventurous $familiar $chip $newOnly", mix.cards.filterValues { it > 0 }, places.groupingBy { it }.eachCount())
            }
        }
    }

    @Test
    fun `it counts as the lines under the two sliders count`() {
        for (lean in LeanChoice.options) for (adventurous in 0..100 step 10) for (familiar in 0..60 step 10) {
            val mix = mix(lean, adventurous, familiar)
            assertEquals(LeanChoice.againCards(lean, adventurous / 100.0, familiar / 100.0), mix.cards.getValue(Lane.AGAIN))
            assertEquals(LeanChoice.newCards(lean, adventurous / 100.0, familiar / 100.0), mix.cards.getValue(Lane.EXPLORE))
        }
    }

    @Test
    fun `with the controls as they come, two tiles are new and four are songs due again`() {
        val row = mix()
        assertEquals(2, row.cards.getValue(Lane.EXPLORE))
        assertEquals(4, row.cards.getValue(Lane.AGAIN))
        assertEquals(null, row.lead)
        // One to three new songs along Adventurousness, none to eleven due again along Familiarity.
        assertEquals(1, mix(adventurous = 0).cards.getValue(Lane.EXPLORE))
        assertEquals(3, mix(adventurous = 100).cards.getValue(Lane.EXPLORE))
        assertEquals(0, mix(familiar = 0).cards.getValue(Lane.AGAIN))
        assertEquals(11, mix(familiar = 60).cards.getValue(Lane.AGAIN))
    }

    @Test
    fun `a choice has the head of the row and most of it, and New songs only has all of it`() {
        for (lean in LeanChoice.options - Lean.AUTO) {
            val row = mix(lean)
            val places = rowPlaces(row)
            assertEquals(lean.lane, row.lead)
            assertTrue("$lean", places.take(ROW_HEAD).all { it == lean.lane })
            assertTrue("$lean", places.count { it == lean.lane } >= 12)
        }
        assertTrue(rowPlaces(mix(newOnly = true)).all { it == Lane.EXPLORE })
    }

    @Test
    fun `one step of a slider turns a few tiles and leaves the others as they were`() {
        var most = 0
        for (lean in LeanChoice.options) {
            for (familiar in listOf(0, 25, 60)) for (adventurous in 0 until 100) {
                most = maxOf(most, rowPlaces(mix(lean, adventurous, familiar)).zip(rowPlaces(mix(lean, adventurous + 1, familiar))).count { it.first != it.second })
            }
            for (adventurous in listOf(0, 50, 100)) for (familiar in 0 until 60) {
                most = maxOf(most, rowPlaces(mix(lean, adventurous, familiar)).zip(rowPlaces(mix(lean, adventurous, familiar + 1))).count { it.first != it.second })
            }
        }
        assertTrue("a step turned $most tiles", most in 1..4)
    }

    @Test
    fun `every place is handed out once, and any stretch of the order lies over both lines of the row`() {
        assertEquals((0 until ROW_PLACES).toList(), ROW_SPREAD.sorted())
        assertEquals(Lane.entries.toSet(), ROW_KINDS.toSet())
        // Four places in a row of the order are never all in the same half of a line.
        for (from in 0..ROW_PLACES - 4) {
            val stretch = ROW_SPREAD.slice(from until from + 4)
            assertTrue("$stretch", stretch.map { it / 10 }.toSet().size == 2)
            assertTrue("$stretch", stretch.map { (it % 10) / 5 }.toSet().size == 2)
        }
    }
}
