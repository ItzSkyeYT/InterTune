/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import com.dd3boh.outertune.db.MusicDatabase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/** What the engine has to work with, read straight from the tables it learns from. */
@HiltViewModel
class RecommendationsViewModel @Inject constructor(
    database: MusicDatabase,
) : ViewModel() {
    val listens = database.listenCount()
    val counted = database.countedListenCount()
    val sessions = database.sessionCount()
    val byEndReason = database.listensByEndReason()
    val byOrigin = database.listensByOrigin()
    val impressions = database.impressionCount()
    val rowBuilds = database.rowBuildCount()
    val recent = database.recentListenRows(30)
}
