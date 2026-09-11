/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

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
    database: MusicDatabase,
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
    private val learning by lazy { EngineLearning(context, database) }

    fun resetWeights() = viewModelScope.launch(Dispatchers.IO) { runCatching { learning.reset() } }
    fun rebuildWeights() = viewModelScope.launch(Dispatchers.IO) { runCatching { learning.rebuild() } }
    val recent = database.recentListenRows(30)
}
