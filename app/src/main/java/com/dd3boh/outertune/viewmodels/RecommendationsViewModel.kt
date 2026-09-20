/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import kotlinx.coroutines.withContext
import com.dd3boh.outertune.engine.EngineLearning
import kotlinx.coroutines.launch
import android.net.Uri
import com.dd3boh.outertune.db.entities.EngineWeight
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.floatOrNull
import kotlinx.coroutines.Dispatchers
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import androidx.lifecycle.ViewModel
import com.dd3boh.outertune.db.MusicDatabase
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
    val calibration = database.engineCalibration()
    val weights = database.engineWeightsFlow()
    val buildScores = database.buildScores()
    private val learning by lazy { EngineLearning(context, database) }

    fun resetWeights() = viewModelScope.launch(Dispatchers.IO) { runCatching { learning.reset() } }

    /** The latest session stops teaching: its listens are marked, its examples skipped, the weights rebuilt without them. */
    fun forgetLastSession() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val session = database.openListens().firstOrNull()?.sessionId ?: database.lastListen()?.sessionId ?: return@launch
            database.transactionNow { forgetSession(session); dropForgottenExamples() }
            learning.rebuild()
        }
    }

    /** Everything since local midnight stops teaching. */
    fun forgetToday() = viewModelScope.launch(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val off = java.util.TimeZone.getDefault().getOffset(now)
            val midnight = Math.floorDiv(now + off, 86_400_000L) * 86_400_000L - off
            database.transactionNow { forgetBetween(midnight, now + 1); dropForgottenExamples() }
            learning.rebuild()
        }
    }

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
            val rows = root["weights"]?.jsonObject.orEmpty().mapNotNull { (name, value) ->
                val o = value.jsonObject
                val existing = known[name] ?: return@mapNotNull null
                EngineWeight(
                    name = name,
                    value = o["value"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null,
                    prior = o["prior"]?.jsonPrimitive?.floatOrNull ?: existing.prior,
                    lo = o["lo"]?.jsonPrimitive?.floatOrNull ?: existing.lo,
                    hi = o["hi"]?.jsonPrimitive?.floatOrNull ?: existing.hi,
                    updates = existing.updates,
                )
            }
            if (rows.isNotEmpty()) database.upsertEngineWeights(rows)
            rows.size
        }.getOrDefault(0)
        withContext(Dispatchers.Main) { onDone(count) }
    }

    fun rebuildWeights() = viewModelScope.launch(Dispatchers.IO) { runCatching { learning.rebuild() } }
    val recent = database.recentListenRows(30)
}
