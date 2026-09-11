/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.viewmodels

import androidx.lifecycle.ViewModel
import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.RecommendationExclusion
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

/**
 * What the recommendation rows must never suggest: songs and artists the listener banned or
 * snoozed, and the automatic rests, each with its expiry, to be lifted early or undone.
 */
@HiltViewModel
class ExclusionsViewModel @Inject constructor(
    private val database: MusicDatabase,
) : ViewModel() {
    val exclusions = database.exclusions()

    fun lift(exclusion: RecommendationExclusion) {
        database.query { runCatching { deleteExclusion(exclusion.id) } }
    }

    /** Puts one back after an Undo, with the same expiry it had. */
    fun restore(exclusion: RecommendationExclusion) {
        database.query { runCatching { insertExclusion(exclusion.copy(id = 0)) } }
    }

    companion object {
        const val KIND_SONG = 1
        const val KIND_ARTIST = 2
        const val REASON_BAN = 1
        const val REASON_SNOOZE = 2
        const val REASON_REST = 3
        const val SNOOZE_MS = 30L * 24 * 60 * 60 * 1000
        const val REST_MS = 7L * 24 * 60 * 60 * 1000
    }
}
