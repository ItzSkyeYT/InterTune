/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.models

import com.dd3boh.outertune.engine.versionKey
import com.zionhuang.innertube.models.SongItem

/**
 * A song the listener chose not to play, from a song's menu (Don't play this song), as the
 * database keeps it: a row of Exclusions with ExclusionsViewModel.REASON_DONT_PLAY.
 *
 * @param title the song's title, as the library has it, or the row's label.
 * @param artist its first artist's name, null when the library does not have the song.
 */
data class DontPlaySong(val id: String, val title: String, val artist: String?)

/**
 * The songs the listener chose not to play (Unreleased.ARTIST_BLOCK), the song half of Block
 * artist (ArtistBlock), asked for in #78: never brought by a radio or autoplay, passed over where
 * the player comes to one by itself, and still played when the listener picks it.
 *
 * A song is known by its id, and its other uploads by its title and first artist (versionKey), as
 * Not this song covers them on Home: a radio brings the video of a song as readily as the song.
 * A song the library had no artist for is known by its id alone.
 */
data class DontPlay(val songs: List<DontPlaySong> = emptyList()) {
    private val ids: Set<String> = songs.mapTo(HashSet()) { it.id }

    /** Only where the library knows the artist, and the title is more than its brackets. */
    private val keys: Set<String> = songs.mapNotNullTo(HashSet()) { key(it.title, it.artist) }

    val isEmpty: Boolean get() = songs.isEmpty()

    /** Whether the listener chose not to play this song, by this upload or another of the same song. */
    fun covers(id: String, title: String?, artist: String?): Boolean =
        !isEmpty && (id in ids || (keys.isNotEmpty() && title != null && key(title, artist) in keys))

    fun covers(song: SongItem): Boolean = covers(song.id, song.title, song.artists.firstOrNull()?.name)

    fun covers(song: MediaMetadata): Boolean = covers(song.id, song.title, song.artists.firstOrNull()?.name)

    /** Whether this very upload is one the listener chose not to play, which its menu can lift. */
    fun has(id: String): Boolean = id in ids

    companion object {
        val NONE = DontPlay()

        private fun key(title: String, artist: String?): String? {
            if (artist.isNullOrBlank()) return null
            return versionKey(title, artist).takeUnless { it.startsWith("|") }
        }
    }
}
