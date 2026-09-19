/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine



/**
 * What a mood chip means before it has been taught anything.
 *
 * The chips were built to learn: Chill means whatever gets played while Chill is on. That is the
 * right idea and it cannot start. Below [ContextChip.MIN_TAGGED] tagged listens the chip does
 * nothing at all and the row falls back to Auto, so the listener asks for calm, receives phonk,
 * and the twenty listens the chip eventually learns from are the phonk. The mood converges on
 * exactly the thing it was pressed to escape.
 *
 * So a chip needs an opinion on day one, from something that is not the listener's history,
 * because the history is what it is trying to override. Titles are what there is. A third of this
 * library says out loud what was done to the recording, and slowed is not party and nightcore is
 * not chill. It is a coarse instrument and it is the only free one.
 *
 * It fades. At zero tagged listens the prior is the whole answer; by [ContextChip.MIN_TAGGED] it
 * is gone and what the listener actually played under the chip has taken over. The prior exists
 * to make the first twenty listens worth learning from, not to decide anything forever.
 */
object ChipPrior {

    /** Tags that pull toward each mood, and tags that pull against it. */
    private val FOR: Map<Int, Set<String>> = mapOf(
        ContextChip.CHILL to setOf("slowed", "reverb", "acoustic", "instrumental", "loop", "eightd"),
        ContextChip.PARTY to setOf("nightcore", "sped", "bassboost", "remix", "mashup", "mix", "extended"),
        ContextChip.FOCUS to setOf("instrumental", "loop", "acoustic", "eightd", "extended"),
    )

    private val AGAINST: Map<Int, Set<String>> = mapOf(
        ContextChip.CHILL to setOf("nightcore", "sped", "bassboost"),
        ContextChip.PARTY to setOf("slowed", "acoustic", "instrumental"),
        ContextChip.FOCUS to setOf("nightcore", "sped", "bassboost", "remix", "mashup"),
    )

    /** Whether this chip is one of the moods the prior knows about. */
    fun knows(chip: Int): Boolean = chip in FOR

    /**
     * How well a candidate's treatment fits the mood, from minus one to one.
     *
     * An untagged song scores zero rather than negative. Most music carries no qualifier at all,
     * and "this title says nothing" is not evidence against it; treating silence as a mark down
     * would hand the whole row to the quarter of the library that happens to be labelled.
     */
    fun fit(chip: Int, tags: Set<String>): Double {
        if (tags.isEmpty()) return 0.0
        val f = FOR[chip] ?: return 0.0
        val a = AGAINST[chip].orEmpty()
        val forCount = tags.count { it in f }
        val againstCount = tags.count { it in a }
        if (forCount == 0 && againstCount == 0) return 0.0
        return ((forCount - againstCount).toDouble() / tags.size).coerceIn(-1.0, 1.0)
    }

    /**
     * How much the prior still counts, given how much the chip has learned.
     *
     * One while it knows nothing, nothing once it has [ContextChip.MIN_TAGGED] listens of its own.
     */
    fun weight(taggedListens: Int): Double {
        if (taggedListens <= 0) return 1.0
        if (taggedListens >= ContextChip.MIN_TAGGED) return 0.0
        return 1.0 - taggedListens.toDouble() / ContextChip.MIN_TAGGED
    }
}
