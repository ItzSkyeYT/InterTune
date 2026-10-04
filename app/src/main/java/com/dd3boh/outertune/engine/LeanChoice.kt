/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.QuickPicksSource
import com.dd3boh.outertune.constants.orOffered

/** The line under the choice on the Recommendations page: what the choice does to the row as things stand. */
sealed interface LeanLine {
    /** Another source fills Quick picks, so the choice has nothing to act on. */
    data object NotSource : LeanLine

    /**
     * Something asked for right now takes over the row and the choice waits: New songs only when
     * [newOnly], otherwise the chip [chip].
     */
    data class SetAside(val newOnly: Boolean, val chip: Int) : LeanLine

    /**
     * Never heard with New songs only on. The choice is not set aside there: it holds New songs
     * only to its own stricter rule, every card a song never even started, and no lane leads
     * because the whole row is the choice's.
     */
    data object StrictNew : LeanLine

    /** Best recommendations fills the row: the choice holds [cards] of it, the first column included. */
    data class Leading(val cards: Int) : LeanLine

    /** Try both: the choice holds about [cards] of the row and [first] of its first four. */
    data class TryBoth(val cards: Int, val first: Int) : LeanLine
}

/**
 * Quick picks leans toward, as the Recommendations page offers it: the options, what is stored
 * for each, and the lines on the page that depend on the choice. Plain functions, so the JVM
 * tests cover them.
 */
object LeanChoice {
    /** The options in the order the page lists them: Auto first, then the poll's four in its order. */
    val options: List<Lean> = listOf(Lean.AUTO, Lean.NEW, Lean.ARTIST, Lean.FORGOTTEN, Lean.SIMILAR)

    /**
     * The option shown as chosen for what the preference holds. Read as Home reads it, so the
     * page and the row never disagree: unset is Auto, and so is a name this version does not know.
     */
    fun selected(stored: String?): Lean = Lean.ofName(stored)

    /** What choosing an option stores. */
    fun stored(choice: Lean): String = choice.name

    /**
     * The line under the choice, or none for Auto, which needs no telling. [source] is the Quick
     * picks source, [chip] and [newOnly] the chip above the row and New songs only as they stand,
     * [dial] Adventurousness from 0 to 1.
     */
    fun line(choice: Lean, source: QuickPicksSource, chip: Int, newOnly: Boolean, dial: Double, p: EngineParams = EngineParams.DEFAULT): LeanLine? {
        if (choice == Lean.AUTO) return null
        val filling = source.orOffered()
        if (filling != QuickPicksSource.ENGINE && filling != QuickPicksSource.COMPARE) return LeanLine.NotSource
        val shape = EngineRow.shape(p, dial, newOnly, false, chip, choice)
        if (shape.strictNew) return LeanLine.StrictNew
        // No lead lane otherwise: the build follows no lean.
        val lead = shape.lead ?: return LeanLine.SetAside(newOnly, chip)
        if (filling == QuickPicksSource.COMPARE) return LeanRow.compareShare(choice, dial, p).let { LeanLine.TryBoth(it.first, it.second) }
        return LeanLine.Leading(shape.quotas[lead] ?: 0)
    }

    /**
     * The cards the Adventurousness line counts: the new-to-you lane's share of the row. While
     * the choice leads the row it is what the choice leaves that lane: twelve under Never heard
     * whatever the slider says, sixteen with Discover on. With no choice, or one that [chip] or
     * [newOnly] has set aside, it is the count the line always gave.
     */
    fun newCards(choice: Lean, dial: Double, familiarity: Double, chip: Int = ContextChip.AUTO, newOnly: Boolean = false, p: EngineParams = EngineParams.DEFAULT): Int =
        (leading(choice, dial, familiarity, chip, newOnly, p)?.quotas ?: quotas(p.rowSize, dial, false, p))[Lane.EXPLORE] ?: 0

    /** The cards the Familiarity line counts: songs due again, of the row as the choice shapes it while it leads. */
    fun againCards(choice: Lean, dial: Double, familiarity: Double, chip: Int = ContextChip.AUTO, newOnly: Boolean = false, p: EngineParams = EngineParams.DEFAULT): Int =
        (leading(choice, dial, familiarity, chip, newOnly, p)?.quotas ?: quotas(p.rowSize, dial, false, p.withFamiliarity(familiarity)))[Lane.AGAIN] ?: 0

    /** Whether the choice, not the Adventurousness slider, sets how many cards are new: Never heard while it leads the row. */
    fun newSetByChoice(choice: Lean, chip: Int, newOnly: Boolean, p: EngineParams = EngineParams.DEFAULT): Boolean =
        EngineRow.shape(p, 0.5, newOnly, false, chip, choice).lead == Lane.EXPLORE

    /** The row's shape while the choice leads it. None with no choice, or with one set aside, when the row is built as it always was. */
    private fun leading(choice: Lean, dial: Double, familiarity: Double, chip: Int, newOnly: Boolean, p: EngineParams): EngineRow.Shape? =
        EngineRow.shape(p.withFamiliarity(familiarity), dial, newOnly, false, chip, choice).takeIf { it.lead != null }
}
