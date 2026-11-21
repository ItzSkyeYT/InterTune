/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.utils.SongVersions

/** A song the listener knows, as [EngineSql.KNOWN_SONGS] reads it: its id, title and first artist. */
data class KnownSong(val id: String, val title: String?, val artistName: String?)

/**
 * The songs a listener already knows, for everything that promises songs never heard: the Discover
 * chip, the Discover something new row, the "New to you" caption and the queue of a Discover pick.
 *
 * A song is known when it is in the listen history (a play of any length), liked, in the library, in
 * any playlist, local or synced, downloaded, or a local file. [EngineSql.KNOWN_SONGS] reads all of
 * them in one query, once for a row or a queue, never song by song.
 *
 * Another upload of a known song by the same artist is known too, by the title with its version
 * marks stripped and the first artist, the key a ban uses ([versionKey]): the official video of a
 * song heard as audio is a song heard. Another artist's song of the same name is not, and a title
 * that is nothing but brackets matches nothing but itself.
 */
class HeardSet private constructor(private val ids: Set<String>, private val keys: Set<String>) {
    /** How many songs it holds. */
    val size: Int get() = ids.size

    fun knows(id: String, title: String?, artist: String?): Boolean =
        id in ids || (title != null && versionKey(title, artist) in keys)

    /** As [knows], with the candidate's [versionKey] already worked out. */
    fun knowsKey(id: String, key: String?): Boolean = id in ids || (key != null && key in keys)

    /**
     * This set with [more] in it. Songs it already holds by id are passed over without reading
     * their titles, so a set read from the database costs next to nothing to complete with what
     * the engine's own input says.
     */
    fun plus(more: Iterable<KnownSong>): HeardSet {
        var newIds: HashSet<String>? = null
        var newKeys: HashSet<String>? = null
        for (s in more) {
            if (s.id in ids || newIds?.contains(s.id) == true) continue
            if (newIds == null) { newIds = HashSet(ids); newKeys = HashSet(keys) }
            newIds += s.id
            keyOf(s)?.let { newKeys!! += it }
        }
        return if (newIds == null) this else HeardSet(newIds, newKeys!!)
    }

    companion object {
        val EMPTY = HeardSet(emptySet(), emptySet())

        fun of(songs: Iterable<KnownSong>): HeardSet = EMPTY.plus(songs)

        /**
         * The key another upload of [song] shares with it, [versionKey]'s own, or none when its
         * title has nothing to compare.
         */
        private fun keyOf(song: KnownSong): String? {
            val base = SongVersions.baseTitle(song.title ?: return null, song.artistName)
            return if (base.isEmpty()) null else base + "|" + song.artistName.orEmpty().trim().lowercase()
        }
    }
}
