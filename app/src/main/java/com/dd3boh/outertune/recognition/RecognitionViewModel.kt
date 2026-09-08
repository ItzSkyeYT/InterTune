/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.content.Context
import androidx.lifecycle.ViewModel
import com.dd3boh.outertune.db.entities.Playlist
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * A window onto [RecognitionEngine], and nothing more.
 *
 * The loop deliberately does not live here. A run has to survive the sheet being dismissed and the
 * screen going off, and a view model survives neither, so this only starts and stops the service
 * and passes the engine's state through to the sheet.
 */
@HiltViewModel
class RecognitionViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val engine: RecognitionEngine,
) : ViewModel() {

    val state = engine.state
    val added = engine.added
    val continuous = engine.continuous
    val running = engine.running
    val skipped = engine.skipped
    val startedAt = engine.startedAt
    val nowPlaying = engine.nowPlaying

    fun start(playlist: Playlist) {
        // The service first. From Android 12 the microphone may only be opened while the process
        // holds foreground importance, so starting the loop before the service is up gets the
        // recording killed a moment later for no visible reason.
        RecognitionService.start(context)
        engine.start(playlist, continuous.value)
    }

    /** Picked by hand from the candidate list. */
    fun accept(song: SongItem, playlist: Playlist) {
        engine.add(song)
        if (continuous.value) engine.start(playlist, true) else stop()
    }

    fun setContinuous(value: Boolean) {
        engine.continuous.value = value
    }

    fun stop() {
        engine.stop()
        RecognitionService.stop(context)
    }

    fun reset() {
        engine.reset()
        RecognitionService.stop(context)
    }
}
