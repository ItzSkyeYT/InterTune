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

    /**
     * Songs YouTube lists beside the favourites' songs, by artists that are not favourites: what
     * the mix takes its guests from when it is not kept to the favourites (see guestQueue).
     *
     * Nothing is fetched for this. Every song that gets played has YouTube's related list stored
     * for it (related_song_map, source 0, written by MusicService.recoverSong), and the songs on
     * that list are in the song table with their artists. So for anybody who plays their
     * favourites, the artists YouTube thinks are like them are already on the phone, along with
     * the very songs it named. Asking YouTube.artist for each favourite would cost a request an
     * artist to learn less: that page names similar artists and none of their songs.
     *
     * refs is how many of the favourites' songs list this one, and it is what the guests are
     * ranked by. DISTINCT because the table can hold the same pair twice, and because a song by
     * two favourites is reached once through each of them.
     *
     * The inner query starts from the bookmarked artists and walks out to their songs and those
     * songs' lists, and the CROSS JOINs are what hold it to that order, as in
     * StatsSql.MOST_PLAYED_ARTISTS. With plain joins SQLite chose to walk every edge there is, in
     * the order of the GROUP BY, and look up the artists of each one. Against a real library,
     * 49,833 songs and 97,955 edges with ten artists bookmarked, that took 1.4 seconds on a desktop
     * and this takes 12 milliseconds for the same 427 rows. The table grows by a list for each new
     * song played, so the slow way only gets slower.
     *
     * Three things are kept out. A song with any bookmarked artist on it, wherever they are
     * billed, since that is a favourite's song and the query above already hands it over. Last.fm's
     * edges (source 1), because what was asked for is similar artists from YouTube. And anything
     * the recommendation rows have been told not to suggest, a banned, snoozed or rested song or
     * artist (kind 1 and 2): a guest is a suggestion, and one that was refused on Home has no
     * business arriving through this door.
     *
     * The strongest first and no more than :limit of them, so the read stays the same size however
     * much has been played. The order is only there to make the cut the same every time.
     */
    const val SIMILAR_TO_BOOKMARKED_ARTISTS = """
        SELECT song.*, rel.refs AS refs
        FROM song
        JOIN (
            SELECT r.relatedSongId AS id, COUNT(DISTINCT r.songId) AS refs
            FROM artist fave
                CROSS JOIN song_artist_map theirs ON theirs.artistId = fave.id
                CROSS JOIN related_song_map r ON r.songId = theirs.songId
            WHERE fave.bookmarkedAt IS NOT NULL AND r.source = 0
            GROUP BY r.relatedSongId
        ) rel ON rel.id = song.id
        WHERE NOT EXISTS (
            SELECT 1 FROM song_artist_map sam
                JOIN artist ON artist.id = sam.artistId
            WHERE sam.songId = song.id AND artist.bookmarkedAt IS NOT NULL
        )
        AND NOT EXISTS (
            SELECT 1 FROM recommendation_exclusion x
            WHERE (x.expiresAt IS NULL OR x.expiresAt > :now)
              AND ((x.kind = 1 AND x.targetId = song.id)
                OR (x.kind = 2 AND x.targetId IN
                    (SELECT sam.artistId FROM song_artist_map sam WHERE sam.songId = song.id)))
        )
        ORDER BY rel.refs DESC, song.id
        LIMIT :limit
    """
}
