/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

/**
 * The favourite-artists query, kept as a constant so the DAO and FavouritesSqlTest run the same
 * text, the way [RecommendationSql] is.
 *
 * Worth pinning for the same reason: a mix drawn from slightly the wrong set of songs still plays,
 * still sounds like a mix, and gives nobody any reason to look at it. The failures that matter here
 * are silent ones, like a song appearing twice because two of its artists are bookmarked, or the
 * whole of an artist's catalogue arriving because a browse cached it without it ever being added.
 */
object FavouritesSql {

    /**
     * Every library song by an artist that has been bookmarked.
     *
     * EXISTS rather than a JOIN, deliberately. A join against song_artist_map multiplies a row by
     * the number of its bookmarked artists, so a track by two bookmarked artists would be returned
     * twice and land in the mix twice. EXISTS asks only whether at least one such artist is
     * attached, which is the actual question, and returns each song once however many bookmarks
     * point at it.
     *
     * song.inLibrary IS NOT NULL is what keeps this to things the person actually added. Playing an
     * artist's whole catalogue because browsing them once cached it is a different feature, and not
     * one anybody asked for.
     *
     * No ORDER BY: the caller shuffles across artists instead. See interleaveByArtist for why
     * sorting or flat-shuffling these rows does not produce a mix.
     */
    const val BY_BOOKMARKED_ARTISTS = """
        SELECT * FROM song
        WHERE song.inLibrary IS NOT NULL
          AND EXISTS (
            SELECT 1 FROM song_artist_map sam
                JOIN artist ON artist.id = sam.artistId
            WHERE sam.songId = song.id AND artist.bookmarkedAt IS NOT NULL
          )
    """
}
