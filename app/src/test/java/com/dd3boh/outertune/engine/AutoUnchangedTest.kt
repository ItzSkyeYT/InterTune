/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Quick picks with no lean is the row it was before it could lean.
 *
 * `engine/auto-rows.txt` holds one line for each build in [AutoRows]: a digest of the whole row
 * (the cards in order with their lanes, scores, features, reasons and seeds, then the pool, the
 * seeds, the quotas and what the tidy pass leaves on screen) and the cards themselves, to read.
 * The lines were written by the engine as it stood before leans, at commit 3c9f51744, compiled
 * on its own. So this holds today's rows against that code, not against today's code called
 * another way, and anything a lean changes on the way to an Auto row moves a line.
 *
 * The first 88 lines are builds over a big library, where every lane has more songs than it is
 * asked for. The 16 after them are builds over a small one, written by the same engine (the same
 * files, at commit 037e60e9d), where lanes run short and hand their places on: the part of the
 * assembly a new listener's row goes through, and one a lean has a rule of its own for.
 *
 * A change to the ordinary row made on purpose moves them too. Write the file again with
 * `AUTO_ROWS_WRITE=<path>` and say why in the commit.
 */
class AutoUnchangedTest {
    private val before: List<String> = requireNotNull(javaClass.getResourceAsStream("/engine/auto-rows.txt")) { "missing engine/auto-rows.txt" }
        .bufferedReader().readLines().filter { it.isNotBlank() }

    /** Every build of [AutoRows], with the lean stored as given; null is the call that names no lean at all. */
    private fun rows(stored: Lean?, each: (BuiltRow) -> Unit = {}): List<String> = AutoRows.lines { input, weights, p, dial, newOnly, neverPlayed, random, lastFmShare ->
        (if (stored == null) EngineRow.build(input, weights, p, dial, newOnly, neverPlayed, random, lastFmShare)
        else EngineRow.build(input, weights, p, dial, newOnly, neverPlayed, random, lastFmShare, stored)).also(each)
    }

    private val builds = AutoRows.cases.flatMap { c -> (0 until c.dice).map { c to it } }

    private fun same(expected: List<String>, got: List<String>, what: String) {
        assertEquals("$what: builds", expected.size, got.size)
        for (i in expected.indices) {
            val (name, digest, cards) = expected[i].split('\t')
            val (gotName, gotDigest, gotCards) = got[i].split('\t')
            assertEquals(name, gotName)
            assertEquals("$what, $name: the cards", cards, gotCards)
            assertEquals("$what, $name: the same cards, but their lanes, scores, reasons, the pool, the seeds, the quotas or the row on screen differ", digest, gotDigest)
        }
    }

    @Test
    fun `with no lean chosen every row is the one the engine built before leans`() {
        val unset = rows(null)
        System.getenv("AUTO_ROWS_WRITE")?.takeIf { it.isNotBlank() }?.let { File(it).writeText(unset.joinToString("\n") + "\n") }
        assertEquals(builds.size, before.size)
        same(before, unset, "no lean named")
        // The preference unset, set to Auto, or holding something this version does not know.
        for (name in listOf(null, "AUTO", "", "auto", "LATER")) assertEquals(Lean.AUTO, Lean.ofName(name))
        same(before, rows(Lean.AUTO) { row ->
            assertEquals(Lean.AUTO, row.lean); assertEquals(0, row.leanPlaced); assertEquals(1.0, row.leadWeight, 0.0)
            assertEquals(EngineParams.DEFAULT.maxPerArtist, row.leadArtistCap)
            assertTrue(row.pool.size <= LeanWeighting.POOL_PICK_CANDIDATES)
            assertNull(row.gaveWay)
        }, "Auto stored")
    }

    @Test
    fun `a lean set aside by a chip or New songs only is that row too, so is one with too little to give, and one that applies is another`() {
        for (lean in Lean.entries.filter { it != Lean.AUTO }) {
            var aside = 0; var applied = 0; var gaveWay = 0
            for ((i, build) in builds.withIndex()) {
                val (case, dice) = build
                val name = before[i].substringBefore('\t')
                var row: BuiltRow? = null
                val got = AutoRows.line(case, dice) { input, weights, p, dial, newOnly, neverPlayed, random, lastFmShare ->
                    EngineRow.build(input, weights, p, dial, newOnly, neverPlayed, random, lastFmShare, lean).also { row = it }
                }
                when {
                    Lean.applied(lean, case.chip, case.newOnly, case.neverPlayed) == Lean.AUTO -> { assertEquals("$lean set aside, $name", before[i], got); aside++ }
                    // A lean with nothing to give, or too little for a row, gives Auto's row: the
                    // very line the engine wrote before leans, not only this engine's Auto.
                    row?.gaveWay != null -> { assertEquals("$lean with too little, $name", before[i], got); gaveWay++ }
                    // The big library tells every lean from Auto in every build where it
                    // applies, so a lean reaching an Auto row could not leave these lines alone.
                    !case.small -> { assertNotEquals("$lean, $name", before[i].split('\t')[1], got.split('\t')[1]); applied++ }
                }
            }
            assertTrue("$lean: $aside set aside, $applied applied", aside >= 12 && applied >= 40)
            // The small library has nothing forgotten and no related list for its last session,
            // in its plain and Both builds; the other two leans have something there.
            assertEquals("$lean gave way", if (lean == Lean.FORGOTTEN || lean == Lean.SIMILAR) 8 else 0, gaveWay)
        }
    }

    @Test
    fun `learning from a row with no lean weighs every card as it did`() {
        val none = BuildLean(1, Lean.AUTO.code, 1.0)
        rows(null) { row ->
            // Grading: the stored u is the grade itself, to the bit, for every lane and every source.
            for (lane in Lane.entries + listOf(null)) for (team in 1..5) for (u in listOf(0.0, 0.3, 0.5, 1.0)) {
                assertEquals(u, LeanWeighting.gradedU(u, team, lane, none), 0.0)
                assertEquals(u, LeanWeighting.gradedU(u, team, lane, null), 0.0)
            }
            // Pool picks: any song of the pool may be one, against the plain mean of the cards.
            assertEquals(row.pool, LeanWeighting.poolPickCandidates(row.pool))
            val plain = DoubleArray(Features.COUNT) { i -> row.cards.sumOf { it.features[i] } / row.cards.size }
            assertArrayEquals(plain, LeanWeighting.reference(row.cards, row.lean.lane, row.leadWeight), 0.0)
        }
    }
}
