/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import kotlinx.coroutines.withContext
import com.dd3boh.outertune.engine.EngineLearning
import com.dd3boh.outertune.engine.SourceMix
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import android.net.Uri
import com.dd3boh.outertune.db.entities.EngineWeight
import com.dd3boh.outertune.engine.Features
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import androidx.lifecycle.ViewModel
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.engine.TrendWindows
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** What the engine has to work with, read straight from the tables it learns from. */
@HiltViewModel
class RecommendationsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MusicDatabase,
) : ViewModel() {
    val listens = database.listenCount()
    val counted = database.countedListenCount()
    val sessions = database.sessionCount()
    val byEndReason = database.listensByEndReason()
    val byOrigin = database.listensByOrigin()
    val impressions = database.impressionCount()
    val rowBuilds = database.rowBuildCount()
    val signals = database.signalCount()
    val taps = database.tapCount()
    val activeExclusions = database.activeExclusionCount(System.currentTimeMillis())
    val gradedByTeam = database.gradedByTeam()
    /** Where the Last.fm share stands and on what evidence, worked out again whenever a card is graded. */
    val sourceMix = database.gradedByTeam().map {
        val now = System.currentTimeMillis()
        SourceMix.share(database.sourceEvidence(now - SourceMix.WINDOW_MS), java.util.TimeZone.getDefault().getOffset(now) / 60_000)
    }.flowOn(Dispatchers.IO)
    val calibration = database.engineCalibration()
    val weights = database.engineWeightsFlow()
    val buildScores = database.buildScores()
    /** The engine's cards in the two fortnights before yesterday, for the trend on How it's doing. */
    val cardTrend = TrendWindows.at(System.currentTimeMillis()).let { database.engineCardTrend(it.from, it.mid, it.to) }
    private val learning by lazy { EngineLearning(context, database) }

    /**
     * Runs [work] past the page that asked for it, and hands its result to [onDone] on the main
     * thread, or null if it failed. Forgetting marks the listens and then rebuilds, and the rebuild
     * can wait on the engine's lock while Home's learning run holds it: in the page's own scope,
     * backing out at that moment cancelled the rebuild after the listens were already marked.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun <T> pastThePage(work: suspend () -> T, onDone: (T?) -> Unit) = GlobalScope.launch(Dispatchers.IO) {
        val result = runCatching { work() }.getOrNull()
        withContext(Dispatchers.Main) { onDone(result) }
    }

    /** [onDone] gets whether it worked. */
    fun resetWeights(onDone: (Boolean) -> Unit) = pastThePage({ learning.reset() }) { onDone(it != null) }

    /**
     * The latest session stops teaching: its listens are marked, its examples skipped, the weights
     * rebuilt without them. [onDone] gets how many listens it marked, 0 with no session at all.
     */
    fun forgetLastSession(onDone: (Int?) -> Unit) = pastThePage<Int>({
        val session = database.openListens().firstOrNull()?.sessionId ?: database.lastListen()?.sessionId
        if (session == null) 0 else {
            val marked = database.transactionNow<Int> { forgetSession(session).also { dropForgottenExamples() } }
            learning.rebuild()
            marked
        }
    }, onDone)

    /** Everything since local midnight stops teaching. [onDone] gets how many listens it marked. */
    fun forgetToday(onDone: (Int?) -> Unit) = pastThePage<Int>({
        val now = System.currentTimeMillis()
        val off = java.util.TimeZone.getDefault().getOffset(now)
        val midnight = Math.floorDiv(now + off, 86_400_000L) * 86_400_000L - off
        val marked = database.transactionNow<Int> { forgetBetween(midnight, now + 1).also { dropForgottenExamples() } }
        learning.rebuild()
        marked
    }, onDone)

    /** Everything the engine has learned, as JSON, built off the main thread and handed back on it for the share sheet. */
    fun export(onReady: (String) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val json = runCatching { exportJson() }.getOrNull() ?: return@launch
        withContext(Dispatchers.Main) { onReady(json) }
    }

    private fun exportJson(): String {
        val weights = database.engineWeights()
        val counts = database.engineWeights().maxOfOrNull { it.updates } ?: 0
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val w = weights.joinToString(",") { "${q(it.name)}:{\"value\":${it.value},\"prior\":${it.prior},\"lo\":${it.lo},\"hi\":${it.hi}}" }
        return "{\"exportedAt\":${System.currentTimeMillis()},\"updates\":$counts,\"weights\":{$w},\"params\":${q(com.dd3boh.outertune.engine.EngineParams.DEFAULT.toString())}}"
    }
    /**
     * Write what the engine has learned to a file the listener chose.
     *
     * A file rather than a share sheet full of text, because the point of having this is moving it
     * to another device or keeping it before a reset, and neither is served by pasting several
     * kilobytes of JSON into a chat.
     */
    fun exportTo(uri: Uri, onDone: (Boolean) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val ok = runCatching {
            val json = exportJson()
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                ?: error("no stream")
        }.isSuccess
        withContext(Dispatchers.Main) { onDone(ok) }
    }

    /**
     * Take back weights written by [exportTo].
     *
     * Only the weights. The priors, bounds and update counts come with them because a weight
     * without its bounds is meaningless, but nothing about listening history is touched: this
     * restores what the engine concluded, not what it concluded it from.
     *
     * Unknown names are skipped rather than rejected. A file from an older build will not have
     * every feature this one has, and the sensible reading of that is "use what you recognise"
     * rather than refusing the lot.
     */
    fun importFrom(uri: Uri, onDone: (Int) -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        val count = runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use {
                it.readBytes().decodeToString()
            } ?: error("no stream")
            val root = Json.parseToJsonElement(text).jsonObject
            val known = database.engineWeights().associateBy { it.name }
            // Checked against this build's own list of weights, not the table: the table is empty
            // until the loop first applies an example and again after Reset, which are exactly the
            // two moments an import is for, and it then took nothing. The value is kept inside
            // the weight's own bounds, and one that is not a number is skipped, since a NaN or a
            // 1e40 from a hand-edited file would poison every build until the next reset.
            val rows = root["weights"]?.jsonObject.orEmpty().mapNotNull { (name, value) ->
                val prior = Features.priors[name] ?: return@mapNotNull null
                val v = value.jsonObject["value"]?.jsonPrimitive?.floatOrNull?.takeIf { it.isFinite() } ?: return@mapNotNull null
                EngineWeight(
                    name = name,
                    value = v.coerceIn(prior.lo.toFloat(), prior.hi.toFloat()),
                    prior = prior.value.toFloat(),
                    lo = prior.lo.toFloat(),
                    hi = prior.hi.toFloat(),
                    updates = known[name]?.updates ?: 0,
                )
            }
            if (rows.isNotEmpty()) database.upsertEngineWeights(rows)
            rows.size
        }.getOrDefault(0)
        withContext(Dispatchers.Main) { onDone(count) }
    }

    /** [onDone] gets how many cards the weights were rebuilt from: the update count the rebuild stored. */
    fun rebuildWeights(onDone: (Int?) -> Unit) = pastThePage<Int>({
        learning.rebuild()
        database.engineWeights().maxOfOrNull { it.updates } ?: 0
    }, onDone)
    val recent = database.recentListenRows(30)
}
