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
 * snoozed, and the automatic rests, each with its expiry, to be lifted early or undone. And the
 * songs the listener chose not to play from a song's menu, which radios and autoplay leave out
 * as well (DontPlay), until lifted.
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

        /** YouTube no longer serves the song and no copy of it played: see GoneSongs. */
        const val REASON_GONE = 4

        /**
         * Don't play this song, from a song's menu (DontPlay, Unreleased.ARTIST_BLOCK): out of the
         * recommendation rows as Not this song is, and of radios and autoplay too, so Not this
         * song keeps to the rows. Until lifted.
         */
        const val REASON_DONT_PLAY = 5

        const val SNOOZE_MS = 30L * 24 * 60 * 60 * 1000
        const val REST_MS = 7L * 24 * 60 * 60 * 1000

        /** Songs come back: a block is lifted, a video restored. After a month the song may be offered once more. */
        const val GONE_MS = 30L * 24 * 60 * 60 * 1000

        /**
         * Whether an exclusion for [reason] may take the place of the one there, of [there]: never
         * of Don't play this song, which does more than any other, but by itself again. A song has
         * one row in all, so Not this song from a card would have let it into radios again.
         */
        fun mayReplace(there: Int?, reason: Int): Boolean = there != REASON_DONT_PLAY || reason == REASON_DONT_PLAY

        /** The row Don't play this song writes for the song [id] called [title] at [at]: in force until lifted. */
        fun dontPlay(id: String, title: String, at: Long) =
            RecommendationExclusion(kind = KIND_SONG, targetId = id, label = title, reason = REASON_DONT_PLAY, createdAt = at)
    }
}
