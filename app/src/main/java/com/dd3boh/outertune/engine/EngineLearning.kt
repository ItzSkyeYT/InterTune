/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.dd3boh.outertune.constants.EngineBudgetDayKey
import com.dd3boh.outertune.constants.EngineBudgetSpentKey
import com.dd3boh.outertune.constants.LearnFromListeningKey
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.EngineWeight
import com.dd3boh.outertune.db.entities.Impression
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get

/**
 * The loop on the device: grade what Quick picks showed against what was played, apply the graded
 * examples to the weights under the day's budget, and keep the weights in `engine_weight`. Runs
 * when Home loads, before the row is built, so the build sees what the last day taught. Blocking;
 * call it off the main thread.
 */
class EngineLearning(private val context: Context, private val database: MusicDatabase) {
    /** The weights as they stand: the table, or the priors when nothing has been learned. */
    fun weights(): Weights = Weights(database.engineWeights().associate { it.name to it.value.toDouble() })

    /** Grades pending impressions and applies what the budget allows. Returns how many examples were applied. */
    suspend fun run(input: EngineInput, now: Long = System.currentTimeMillis()): Int {
        if (!context.dataStore.get(LearnFromListeningKey, true)) return 0
        grade(input, now)
        return apply(now)
    }

    private fun grade(input: EngineInput, now: Long) {
        val pending = database.pendingImpressions()
        if (pending.isEmpty()) return
        val rows = pending.map { i ->
            ImpressionRow(i.id, i.songId, i.slot, i.lane.takeIf { it in 1..4 }?.let { Lane.entries[it - 1] }, Grading.parseFeatures(i.features), i.p?.toDouble(), i.visibleAt ?: 0L, i.tappedAt)
        }
        val groups = VersionGroups(input.songs.values, input.versionLinks)
        // The listens are read afresh: the input may be a minute old, and the play that finished
        // a moment ago is the one to grade.
        val listens = database.engineListens().map { if (it.endReason == com.dd3boh.outertune.constants.EndReason.OPEN) it.copy(endedAt = now) else it }
        val graded = Grading.grade(rows, listens, input.songs, groups, now)
        if (graded.isEmpty()) return
        database.transactionNow {
            graded.forEach { markGraded(it.impressionId, it.outcome, it.y.toFloat(), it.u.toFloat(), now) }
        }
    }

    private fun example(i: Impression): Example? {
        val x = Grading.parseFeatures(i.features) ?: return null
        val y = i.y ?: return null; val u = i.u ?: return null
        return Example(x, i.lane.takeIf { it in 1..4 }?.let { Lane.entries[it - 1] }, i.slot, y.toDouble(), u.toDouble(), pairwise = i.slot < 0)
    }

    private suspend fun apply(now: Long): Int {
        val examples = database.unappliedExamples()
        if (examples.isEmpty()) return 0
        val today = now / 86_400_000L
        var spent = 0.0
        context.dataStore.edit { prefs ->
            spent = if (prefs[EngineBudgetDayKey] == today) (prefs[EngineBudgetSpentKey] ?: 0f).toDouble() else 0.0
        }
        val budget = Budget(spent)
        val learner = learner()
        val applied = ArrayList<Long>()
        for (i in examples) {
            if (!budget.canAfford()) break
            val e = example(i) ?: continue
            budget.charge(learner.apply(e))
            applied += i.id
        }
        if (applied.isEmpty()) return 0
        database.transactionNow {
            applied.forEach { markApplied(it, now) }
            store(learner)
        }
        context.dataStore.edit { prefs -> prefs[EngineBudgetDayKey] = today; prefs[EngineBudgetSpentKey] = budget.spent.toFloat() }
        Log.d(TAG, "applied ${applied.size} of ${examples.size} examples, budget ${"%.2f".format(budget.spent)}")
        return applied.size
    }

    private fun learner(): Learner {
        val rows = database.engineWeights()
        val learner = Learner(rows.associate { it.name to it.value.toDouble() })
        return learner
    }

    private fun MusicDatabase.store(learner: Learner) {
        val updates = engineWeights().associate { it.name to it.updates }
        val count = (updates.values.maxOrNull() ?: 0) + learner.updates
        upsertEngineWeights(learner.asMap().map { (name, value) ->
            val prior = Features.priors[name]!!
            EngineWeight(name, value.toFloat(), prior.value.toFloat(), prior.lo.toFloat(), prior.hi.toFloat(), count)
        })
    }

    /** Back to the priors; the examples stay graded and applied, so Rebuild can bring the learning back. */
    fun reset() {
        database.transactionNow { clearEngineWeights() }
    }

    /**
     * The priors, then every applied example again in the order it was applied: a pure function of
     * stored rows, so this always lands within rounding of what the day-by-day loop produced.
     */
    fun rebuild(): Map<String, Double> {
        val learner = Learner()
        val examples = database.appliedExamples()
        examples.forEach { i -> example(i)?.let { learner.apply(it) } }
        database.transactionNow { clearEngineWeights(); store(learner) }
        return learner.asMap()
    }

    companion object {
        private const val TAG = "EngineLearning"
    }
}
