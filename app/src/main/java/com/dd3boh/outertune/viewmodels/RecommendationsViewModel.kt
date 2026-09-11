/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import kotlinx.coroutines.withContext
import com.dd3boh.outertune.engine.EngineLearning
import kotlinx.coroutines.launch
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
    fun rebuildWeights() = viewModelScope.launch(Dispatchers.IO) { runCatching { learning.rebuild() } }
    val recent = database.recentListenRows(30)
}
