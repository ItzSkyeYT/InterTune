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
     * Every song the app knows of by an artist that has been bookmarked.
     *
     * EXISTS rather than a JOIN, deliberately. A join against song_artist_map multiplies a row by
     * the number of its bookmarked artists, so a track by two bookmarked artists would be returned
     * twice and land in the mix twice. EXISTS asks only whether at least one such artist is
     * attached, which is the actual question, and returns each song once however many bookmarks
     * point at it.
     *
     * No restriction to the library, and that is the part worth explaining, because the obvious
     * instinct is to add one. This was first written as inLibrary IS NOT NULL, on the reasoning
     * that browsing an artist once should not enlist their whole catalogue. Measured against a
     * real device it was wrong by an order of magnitude. The song table holds everything the app
     * has ever seen, 46804 rows on that phone, while inLibrary is a much narrower "added" marker
     * with 398. Of the 279 songs by the ten bookmarked artists there, inLibrary kept 13, and four
     * of the ten artists contributed nothing at all: one of them had 68 songs known and none of
     * them marked. Liked and downloaded barely move it, to 14. A favourites mix of thirteen songs
     * is not a mix.
     *
     * So the filter is the bookmark and nothing else. The bookmark is already the person choosing
     * that artist deliberately, which is a stronger statement than whether any particular track
     * got an inLibrary flag, and it is the only reading that fills a queue. It does mean a song
     * seen once in a search result can turn up, and it means the mix needs the network, since
     * these are not downloads. Both are the price of it containing anything.
     *
     * No ORDER BY: the caller shuffles across artists instead. See interleaveByArtist for why
     * sorting or flat-shuffling these rows does not produce a mix.
     */
    const val BY_BOOKMARKED_ARTISTS = """
        SELECT * FROM song
        WHERE EXISTS (
            SELECT 1 FROM song_artist_map sam
                JOIN artist ON artist.id = sam.artistId
            WHERE sam.songId = song.id AND artist.bookmarkedAt IS NOT NULL
        )
    """
}
