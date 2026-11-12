/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db.entities

import androidx.compose.runtime.Immutable
import androidx.room.Embedded

/*
 * The rows of the Stats page's Most played lists: the song, artist or album with what the period
 * holds of it, counted in the same read that ranks it (db/StatsSql). A play is a row of the event
 * table, the counted plays, which is what the lists are ranked from.
 */

/** A most played song and its plays in the period: [com.dd3boh.outertune.db.StatsSql.MOST_PLAYED_SONGS]. */
@Immutable
data class PlayedSong(
    @Embedded val song: Song,
    val plays: Int,
)

/**
 * A most played artist: [com.dd3boh.outertune.db.StatsSql.MOST_PLAYED_ARTISTS]. The plays of the
 * songs they are credited on in the period, and the time those plays lasted, in milliseconds. A
 * song with two artists gives its plays and its time to both.
 */
@Immutable
data class PlayedArtist(
    @Embedded val artist: Artist,
    val plays: Int,
    val playTime: Long,
)

/** A most played album and the plays of its songs in the period: [com.dd3boh.outertune.db.StatsSql.MOST_PLAYED_ALBUMS]. */
@Immutable
data class PlayedAlbum(
    @Embedded val album: Album,
    val plays: Int,
)
