/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.ui.player

import com.dd3boh.outertune.models.MediaMetadata

/**
 * The rows of the queue's list, each under a name of its own that it keeps.
 *
 * A list tells its rows apart by a key, and here that is made of the song's id and a number that
 * says which place in the queue is meant (MediaMetadata.composeUidWorkaround: a queue can hold one
 * song twice). That number used to be the row's position. So a song removed, moved or
 * added gave every row after it a new key, the list took them all for rows it had never seen and
 * drew them from nothing, which was the flash, and nothing could be animated into its new place.
 *
 * A song in a queue already carries a number made when it was put there, and it keeps it for as
 * long as it stays. That is the row's name now. Should two entries carry the same one (the same
 * object queued twice), the later ones are told apart by how many came before, which is stable
 * for as long as their order is.
 */
internal object QueueRows {
    fun of(songs: List<MediaMetadata>): List<MediaMetadata> {
        val met = HashMap<Double, Int>()
        return songs.map { song ->
            val before = met[song.composeUidWorkaround] ?: 0
            met[song.composeUidWorkaround] = before + 1
            if (before == 0) song else song.copy(composeUidWorkaround = song.composeUidWorkaround + before)
        }
    }

    /**
     * A row's key: which song, and which of its places in the queue. Nothing else of the song is
     * in it, so a row is still itself when the song is liked or its details arrive.
     */
    fun key(song: MediaMetadata): Int = 31 * song.id.hashCode() + song.composeUidWorkaround.hashCode()

    /** Whether [shown] is what the list [has] already: the same rows in the same order. */
    fun same(shown: List<MediaMetadata>, has: List<MediaMetadata>): Boolean =
        shown.size == has.size && shown.indices.all { shown[it] == has[it] }
}
