/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.recognition

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dd3boh.outertune.db.entities.Playlist
import com.zionhuang.innertube.models.SongItem
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.launch
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
    private val history: RecognitionHistory,
) : ViewModel() {

    val state = engine.state
    val added = engine.added
    val continuous = engine.continuous
    val running = engine.running
    val skipped = engine.skipped
    val startedAt = engine.startedAt
    val nowPlaying = engine.nowPlaying

    /** Everything ever heard, across restarts, newest first. */
    val heard = history.entries

    /**
     * The one guarded way in.
     *
     * The check lives here rather than at each screen so that no caller can start the microphone
     * without it, and so the two entry points cannot drift apart on the one thing that must not.
     * Refusing quietly is right: both screens ask for the permission themselves and only reach
     * this once it has been given, so arriving here without it means something else went wrong.
     */
    fun start(playlist: Playlist?) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) return

        // The service first. From Android 12 the microphone may only be opened while the process
        // holds foreground importance, so starting the loop before the service is up gets the
        // recording killed a moment later for no visible reason.
        RecognitionService.start(context)
        engine.start(playlist, continuous.value)
    }

    /** Picked by hand from the candidate list. */
    fun accept(song: SongItem, playlist: Playlist?) {
        engine.add(song)
        // Through the guarded entry point, not straight to the engine, so picking a candidate
        // by hand cannot reopen the microphone on a permission that has since been revoked.
        if (continuous.value) start(playlist) else stop()
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

    /**
     * Separate from [reset], which only empties this run.
     *
     * Clearing what the app remembers hearing is a different act from clearing the current
     * session, and the settings entry that says it clears history should do the one it says.
     */
    fun clearHistory() {
        viewModelScope.launch { history.clear() }
    }
}
