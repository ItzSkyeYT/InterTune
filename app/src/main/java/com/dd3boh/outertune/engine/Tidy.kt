/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.utils.SongVersions

/** A song the listener has just had: enough of it in the last day, or any of it this session. */
data class PlayedSong(val id: String, val title: String, val artist: String?)

/**
 * The key two cards share when they are the same song to a listener: the title with its version
 * marks stripped, and the first artist. "Africa" and "Africa (Live)" by Toto share one; "Africa"
 * by someone else does not, because that is a different song with the same name.
 */
fun versionKey(title: String, artist: String?): String =
    SongVersions.baseTitle(title) + "|" + artist.orEmpty().trim().lowercase()

/**
 * One pass over Home's rows in screen order. A row is handed over with its songs picked out by
 * three lookups (anything that is not a song passes through untouched), and what the pass keeps
 * it remembers, so no later row shows a song, or a version of a song, that an earlier row already
 * has. The Quick picks row is also asked to be fresh: nothing just played, and no version of it.
 */
class TidyPass(justPlayed: Collection<PlayedSong>) {
    private val playedIds = justPlayed.mapTo(HashSet()) { it.id }
    private val playedKeys = justPlayed.mapTo(HashSet()) { versionKey(it.title, it.artist) }
    private val seen = HashSet<String>()

    fun <T> row(
        items: List<T>,
        freshOnly: Boolean = false,
        id: (T) -> String?,
        title: (T) -> String?,
        artist: (T) -> String?,
    ): List<T> = items.filter { item ->
        val itemId = id(item) ?: return@filter true
        val itemTitle = title(item) ?: return@filter true
        val key = versionKey(itemTitle, artist(item))
        if (freshOnly && (itemId in playedIds || key in playedKeys)) return@filter false
        seen.add(key)
    }
}
