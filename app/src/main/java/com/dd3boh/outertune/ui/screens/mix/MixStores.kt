/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.screens.mix

import android.content.Context
import android.util.Log
import com.dd3boh.outertune.mix.edit.BeatFixStore
import com.dd3boh.outertune.mix.edit.JsonFileBeatFixStore
import com.dd3boh.outertune.mix.edit.JsonFileTransitionStore
import com.dd3boh.outertune.mix.edit.TransitionStore

/**
 * The saved transitions and the beat fixes, one of each for the whole process. Each file store
 * reads its file once and writes it whole, so two of them on one file would each write over the
 * other's changes: whoever else reads or writes these files (the playlist's pills, playback) takes
 * them from here.
 */
object MixStores {
    @Volatile private var transitions: TransitionStore? = null
    @Volatile private var fixes: BeatFixStore? = null

    private val problem: (String, Throwable?) -> Unit = { message, cause -> Log.w("MixStores", message, cause) }

    fun transitions(context: Context): TransitionStore = transitions ?: synchronized(this) {
        transitions ?: JsonFileTransitionStore.at(context.applicationContext.filesDir, problem).also { transitions = it }
    }

    fun beatFixes(context: Context): BeatFixStore = fixes ?: synchronized(this) {
        fixes ?: JsonFileBeatFixStore.at(context.applicationContext.filesDir, problem).also { fixes = it }
    }
}
