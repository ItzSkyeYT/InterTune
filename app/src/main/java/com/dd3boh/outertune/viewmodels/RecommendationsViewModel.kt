/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import com.dd3boh.outertune.engine.EngineLearning
import com.dd3boh.outertune.engine.SourceMix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
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
import com.dd3boh.outertune.constants.EngineCopyLoadedKey
import com.dd3boh.outertune.utils.dataStore
import com.dd3boh.outertune.utils.get
import androidx.datastore.preferences.core.edit
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
    val byEnd = database.listensByEnd()
    val startsByOrigin = database.startsByOrigin()
    val cardsSeen = database.cardsSeen()
    val signals = database.signalCount()
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

    /** The change on Your data waiting for a yes. Here, not on the page, so a rotation keeps the dialog. */
    private val pending = PendingAsk()
    val asking: StateFlow<DataAsk?> = pending.asking

    /**
     * What each button on Your data did, in place of its description. Held here rather than in the
     * page, so a rotation does not lose it, and a result that lands while the page is being rebuilt
     * still shows.
     */
    private val _dataResults = MutableStateFlow<Map<DataAction, DataResult>>(emptyMap())
    val dataResults: StateFlow<Map<DataAction, DataResult>> = _dataResults.asStateFlow()

    private fun tell(action: DataAction, result: DataResult) = _dataResults.update { afterResult(it, action, result) }

    /**
     * Runs [work] past the page that asked for it, and hands its result to [onDone], or null if it
     * failed. Forgetting marks the listens and then rebuilds, and the rebuild can wait on the
     * engine's lock while Home's learning run holds it: in the page's own scope, backing out at that
     * moment cancelled the rebuild after the listens were already marked.
     *
     * GlobalScope, because the app has no application scope of its own to inject: the other work
     * that has to outlive a screen (App, the widget's setup, the login page) does the same.
     */
    @OptIn(DelicateCoroutinesApi::class)
    private fun <T> pastThePage(work: suspend () -> T, onDone: (T?) -> Unit) = GlobalScope.launch(Dispatchers.IO) {
        onDone(runCatching { work() }.getOrNull())
    }

    /** The session Forget the last session takes: the one playing now, or the latest. */
    private fun lastSessionId(): Long? = database.openListens().firstOrNull()?.sessionId ?: database.lastListen()?.sessionId

    private fun todayNow(): LongRange {
        val now = System.currentTimeMillis()
        return today(now, java.util.TimeZone.getDefault().getOffset(now))
    }

    /**
     * A tap on a button that changes what it has learned: count what it would change, then ask, or
     * say at once that there is nothing to do. Saving a copy does not come here; it changes nothing.
     */
    fun ask(action: DataAction) = viewModelScope.launch(Dispatchers.IO) {
        _dataResults.update { clearedFor(it, action) }
        val step = runCatching {
            val stored = database.engineWeights().isNotEmpty()
            val copy = copyLoaded(context.dataStore.get(EngineCopyLoadedKey, false), stored)
            when (action) {
                DataAction.FORGET_SESSION -> {
                    val session = lastSessionId()
                    forgetSessionStep(
                        session,
                        session?.let { database.forgettableInSession(it) } ?: 0,
                        session?.let { database.sessionStart(it) } ?: 0L,
                        copy,
                    )
                }
                DataAction.FORGET_TODAY -> todayNow().let { forgetTodayStep(database.forgettableBetween(it.first, it.last + 1), it, copy) }
                DataAction.RESET -> resetStep(stored, database.appliedCardCount(), copy)
                DataAction.REBUILD -> rebuildStep(database.appliedCardCount(), copy)
                DataAction.LOAD -> loadStep()
                DataAction.SAVE -> error("Saving a copy does not ask")
            }
        }.getOrElse { DataStep.Tell(DataResult.Failed) }
        when (step) {
            is DataStep.Ask -> pending.put(step.ask)
            is DataStep.Tell -> tell(action, step.result)
        }
    }

    /** No, or Back, or a tap outside the dialog: nothing changes. */
    fun dismissAsk() = pending.clear()

    /**
     * Yes. Loading only opens the file picker from here, which the page does when this returns
     * true; the rest runs now. False for a yes to a question no longer waiting, such as the second
     * of two quick taps, which could otherwise run a forget twice or open two pickers.
     */
    fun confirm(ask: DataAsk): Boolean {
        if (!pending.take(ask)) return false
        if (ask is DataAsk.Load) return true
        tell(ask.action, DataResult.Working)
        when (ask) {
            is DataAsk.ForgetSession -> pastThePage({
                val marked = database.transactionNow<Int> { forgetSession(ask.sessionId).also { dropForgottenExamples() } }
                learning.rebuild()
                marked
            }) { tell(DataAction.FORGET_SESSION, forgotResult(it)) }
            // The day the dialog counted, not today worked out again: a yes given after midnight
            // would forget the new day, and one a few songs later more than it had said.
            is DataAsk.ForgetToday -> pastThePage({
                val marked = database.transactionNow<Int> { forgetBetween(ask.from, ask.to).also { dropForgottenExamples() } }
                learning.rebuild()
                marked
            }) { tell(DataAction.FORGET_TODAY, forgotResult(it)) }
            is DataAsk.Reset -> pastThePage({ learning.reset() }) {
                tell(DataAction.RESET, if (it != null) DataResult.ResetDone else DataResult.Failed)
            }
            // Counted as cards, as the dialog was: the learner's own count takes in pool picks too.
            is DataAsk.Rebuild -> pastThePage({
                learning.rebuild()
                database.appliedCardCount()
            }) { tell(DataAction.REBUILD, it?.let { n -> DataResult.Rebuilt(n) } ?: DataResult.Failed) }
            DataAsk.Load -> Unit
        }
        return true
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
    fun exportTo(uri: Uri) = viewModelScope.launch(Dispatchers.IO) {
        val ok = runCatching {
            val json = exportJson()
            context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                ?: error("no stream")
        }.isSuccess
        tell(DataAction.SAVE, if (ok) DataResult.Saved else DataResult.FileFailed)
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
     *
     * Past the page, as every other change to what it has learned is: in the page's own scope,
     * leaving Your data while a copy loaded could cancel it between writing the weights and
     * marking them as a loaded copy, and a later forget, reset or rebuild then said nothing of it.
     */
    fun importFrom(uri: Uri) {
        tell(DataAction.LOAD, DataResult.Working)
        pastThePage({
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
            if (rows.isNotEmpty()) {
                database.upsertEngineWeights(rows)
                // So a forget, reset or rebuild after this says the copy goes.
                context.dataStore.edit { it[EngineCopyLoadedKey] = true }
            }
            rows.size
        }) { count -> tell(DataAction.LOAD, if ((count ?: 0) > 0) DataResult.Loaded else DataResult.FileFailed) }
    }

    val recent = database.recentListenRows(30)
}
