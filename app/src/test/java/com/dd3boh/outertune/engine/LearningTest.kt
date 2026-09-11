/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class LearningTest {
    private fun x(vararg pairs: Pair<Int, Double>): DoubleArray = DoubleArray(Features.COUNT).also { arr -> pairs.forEach { (i, v) -> arr[i] = v } }

    @Test
    fun `a play moves the weights of what it saw up and a pass moves them down`() {
        val l = Learner()
        val seen = x(Features.SEED to 0.8, Features.ACT to 0.3)
        val before = l.asMap()
        l.apply(Example(seen, Lane.RELATED, 0, y = 1.0, u = 1.0))
        assertTrue(l.asMap()["x_seed"]!! > before["x_seed"]!!)
        assertTrue(l.asMap()["b_related"]!! > before["b_related"]!!)
        val l2 = Learner()
        l2.apply(Example(seen, Lane.RELATED, 0, y = 0.0, u = 0.3))
        assertTrue(l2.asMap()["x_seed"]!! < before["x_seed"]!!)
    }

    @Test
    fun `ten thousand random updates stay in bounds and no penalty turns positive`() {
        val l = Learner(); val r = Random(4)
        repeat(10_000) {
            val f = DoubleArray(Features.COUNT) { r.nextDouble(-1.0, 1.0) }
            l.apply(Example(f, Lane.entries[r.nextInt(4)], r.nextInt(-1, 20), y = r.nextDouble(), u = listOf(1.0, 0.5, 0.3)[r.nextInt(3)], pairwise = r.nextInt(10) == 0))
        }
        for ((name, v) in l.asMap()) {
            val prior = Features.priors[name]!!
            assertTrue("$name out of bounds: $v", v >= prior.lo - 1e-12 && v <= prior.hi + 1e-12)
        }
        for (name in listOf("x_sat", "x_gap", "x_imp", "x_over", "w_pos")) assertTrue(name, l.asMap()[name]!! <= 0.0)
    }

    @Test
    fun `five hundred plays of one song in a day cannot move a weight past the budget`() {
        val l = Learner(); val budget = Budget()
        val seen = x(Features.ACT to 1.0)
        var applied = 0
        repeat(500) { if (budget.canAfford()) { budget.charge(l.apply(Example(seen, Lane.RELATED, 0, 1.0, 1.0))); applied++ } }
        assertTrue("applied $applied", applied < 500)
        assertTrue(budget.spent <= Learner.DAILY_BUDGET + Learner.LEARNING_RATE)
        // Any weight moved at most η per unit of budget, so under about one unit in total.
        assertTrue(l.asMap()["x_act"]!! - Features.priors["x_act"]!!.value <= 1.0 + 1e-9)
    }

    @Test
    fun `a pool pick leaves the biases alone`() {
        val l = Learner()
        val before = l.asMap()
        l.apply(Example(x(Features.DORM to 0.5), null, -1, y = 1.0, u = 0.5, pairwise = true))
        assertEquals(before["b"], l.asMap()["b"])
        assertEquals(before["w_pos"], l.asMap()["w_pos"])
        assertTrue(l.asMap()["x_dorm"]!! > before["x_dorm"]!!)
    }

    @Test
    fun `replaying stored examples reproduces the weights exactly`() {
        val r = Random(9)
        val examples = List(300) { Example(DoubleArray(Features.COUNT) { r.nextDouble(-1.0, 1.0) }, Lane.entries[it % 4], it % 20, r.nextDouble(), 1.0) }
        val a = Learner(); examples.forEach { a.apply(it) }
        val b = Learner(); examples.forEach { b.apply(it) }
        for ((name, v) in a.asMap()) assertEquals(v, b.asMap()[name]!!, 1e-12)
    }

    @Test
    fun `the prior pull returns a weight toward its start`() {
        val l = Learner(mapOf("x_like" to 2.0))
        repeat(200) { l.apply(Example(x(), Lane.RELATED, 0, y = 0.05, u = 1.0)) }   // nothing seen: only the pull acts on x_like
        assertTrue(l.asMap()["x_like"]!! < 2.0)
    }

    @Test
    fun `brier and reliability read the record`() {
        val pairs = listOf(0.1 to 0.0, 0.1 to 0.0, 0.9 to 1.0, 0.9 to 0.0)
        assertEquals((0.01 + 0.01 + 0.01 + 0.81) / 4, Calibration.brier(pairs), 1e-9)
        val bins = Calibration.reliability(pairs, 5)
        assertEquals(2, bins[0].count); assertEquals(0.0, bins[0].playRate, 0.0)
        assertEquals(2, bins[4].count); assertEquals(0.5, bins[4].playRate, 0.0)
    }
}
