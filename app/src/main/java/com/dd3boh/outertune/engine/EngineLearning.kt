/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import kotlinx.coroutines.flow.first
import com.dd3boh.outertune.db.Converters
import com.dd3boh.outertune.constants.EndReason
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

    /**
     * Grades pending impressions and applies what the budget allows. Returns how many examples were
     * applied. Cheap enough to run whenever Home comes back into view: it reads the listen log and
     * only the songs the pending cards and recent listens name, never the whole library.
     */
    suspend fun run(now: Long = System.currentTimeMillis()): Int {
        if (!context.dataStore.get(LearnFromListeningKey, true)) return 0
        grade(now)
        runCatching { scoreBuilds(now) }.onFailure { Log.w(TAG, "Could not score builds", it) }
        return apply(now)
    }

    /**
     * A day after a row was built (shown or shadow), how many of the listener's picks in its time
     * it held: picks are plays at depth 0 heard well, from the build until the next build of the
     * same kind or a day, whichever comes first, by version group. For an engine row the picks from
     * its pool but not its row become pool-pick examples, applied pairwise against the mean of the
     * row's unplayed cards.
     */
    private suspend fun scoreBuilds(now: Long) {
        val builds = database.buildsToScore(now - 86_400_000L)
        if (builds.isEmpty()) return
        val listens = database.engineListens().filter { it.learn && it.autoplayDepth == 0 && it.endReason != EndReason.OPEN }
        for (b in builds) {
            val cards = RowBuildCodec.decode(b.cards)
            val pool = RowBuildCodec.decode(b.pool)
            val end = minOf(database.nextBuildAt(b.rowKey, b.builtAt) ?: Long.MAX_VALUE, b.builtAt + 86_400_000L)
            val window = listens.filter { it.startedAt > b.builtAt && it.startedAt <= end }
            val ids = (cards.map { it.songId } + pool.map { it.songId } + window.map { it.songId }).toSet().toList()
            val songs = ids.chunked(900).flatMap { chunk -> database.songsByIds(chunk).first() }
                .associate { it.id to SongRow(it.id, it.song.title, it.artists.firstOrNull()?.id, it.artists.firstOrNull()?.name, it.song.liked, it.song.likedDate?.let { d -> storedLocalToInstant(Converters().dateToTimestamp(d)!!) }?.takeIf { _ -> it.song.liked }) }
            val groups = VersionGroups(songs.values, database.engineVersionLinks().map { VersionLink(it.songId, it.versionId) })
            val picks = window.filter { Signals.engagement(it, songs[it.songId]?.likedAt) >= EngineParams.DEFAULT.justPlayedEngagement }
                .groupBy { groups.groupOf(it.songId) }
            val rowGroups = cards.map { groups.groupOf(it.songId) }.toSet()
            val hits = picks.keys.count { it in rowGroups }
            database.transactionNow { scoreBuild(b.id, picks.size, hits, now) }
            if (b.rowKey != 1 || pool.isEmpty() || database.poolPicksOf(b.id) > 0) continue
            // Pool picks: at most three, against the row's cards that were not played.
            val playedGroups = picks.keys
            val reference = cards.filter { groups.groupOf(it.songId) !in playedGroups }.map { it.features }
            if (reference.isEmpty()) continue
            val mean = DoubleArray(Features.COUNT) { i -> reference.sumOf { it[i] } / reference.size }
            val poolPicks = pool.filter { c -> groups.groupOf(c.songId) in playedGroups && groups.groupOf(c.songId) !in rowGroups }.take(3)
            if (poolPicks.isEmpty()) continue
            database.transactionNow {
                poolPicks.forEach { c ->
                    val g = picks[groups.groupOf(c.songId)]!!.maxOf { Signals.engagement(it, songs[it.songId]?.likedAt) }
                    val diff = DoubleArray(Features.COUNT) { i -> c.features[i] - mean[i] }
                    insertImpressions(listOf(Impression(
                        buildId = b.id, songId = c.songId, slot = -1, lane = c.lane.ordinal + 1, team = 1, sampled = false, p = null,
                        features = diff.joinToString(",") { String.format(java.util.Locale.ROOT, "%.4f", it) }, reasons = c.reasons.joinToString(","),
                        visibleAt = b.builtAt, outcome = Outcome.PLAYED, y = g.toFloat(), u = 0.5f, gradedAt = now,
                    )))
                }
            }
            Log.d(TAG, "build ${b.id}: $hits of ${picks.size} picks in the row, ${poolPicks.size} pool picks")
        }
    }

    private suspend fun grade(now: Long) {
        val pending = database.pendingImpressions()
        if (pending.isEmpty()) return
        val rows = pending.map { i ->
            ImpressionRow(i.id, i.songId, i.slot, i.lane.takeIf { it in 1..4 }?.let { Lane.entries[it - 1] }, Grading.parseFeatures(i.features), i.p?.toDouble(), i.visibleAt ?: 0L, i.tappedAt)
        }
        val listens = database.engineListens().map { if (it.endReason == EndReason.OPEN) it.copy(endedAt = now) else it }
        val oldest = pending.minOf { it.visibleAt ?: now }
        val recent = listens.filter { it.startedAt >= oldest - 86_400_000L }
        val ids = (pending.map { it.songId } + recent.map { it.songId }).toSet().toList()
        val songs = ids.chunked(900).flatMap { chunk -> database.songsByIds(chunk).first() }
            .associate { it.id to SongRow(it.id, it.song.title, it.artists.firstOrNull()?.id, it.artists.firstOrNull()?.name, it.song.liked, it.song.likedDate?.let { d -> storedLocalToInstant(Converters().dateToTimestamp(d)!!) }?.takeIf { _ -> it.song.liked }) }
        val groups = VersionGroups(songs.values, database.engineVersionLinks().map { VersionLink(it.songId, it.versionId) })
        val graded = Grading.grade(rows, recent, songs, groups, now)
        if (graded.isEmpty()) return
        database.transactionNow {
            graded.forEach { markGraded(it.impressionId, it.outcome, it.y.toFloat(), it.u.toFloat(), now) }
        }
        Log.d(TAG, "graded ${graded.size} of ${pending.size} pending impressions")
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
