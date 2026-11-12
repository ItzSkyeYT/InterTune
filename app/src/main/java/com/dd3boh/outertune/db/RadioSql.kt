/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

/**
 * What a radio is made of when YouTube gives none (LocalRadio, Unreleased.RADIO_FALLBACK): the
 * songs the phone already knows around one song, each list its own query so LocalRadio can mix
 * them. Kept as constants so the DAO and RadioSqlTest run the same text, as RelatedSql is.
 *
 * Every list is of songs the song table holds, which is everything the app has seen (46,804 rows
 * on his phone on 22 Sep 2026), so they can all be asked for by id; LocalRadio leaves out what may
 * not or cannot play.
 */
object RadioSql {

    /**
     * One source's list for a song (RelatedSongMap: 0 YouTube's related shelf, 1 Last.fm's similar
     * tracks), each song once, in the order it was written, which is the source's own.
     */
    const val LISTED_FOR = """
        SELECT song.* FROM song
        JOIN (SELECT relatedSongId, MIN(id) AS firstEdge FROM related_song_map
              WHERE songId = :songId AND source = :source GROUP BY relatedSongId) edge
          ON song.id = edge.relatedSongId
        ORDER BY edge.firstEdge
        LIMIT :limit
    """

    /**
     * The songs whose own lists name the song, from either source: it is in their neighbourhood,
     * so they are in its. The first written first.
     */
    const val LISTING = """
        SELECT song.* FROM song
        JOIN (SELECT songId, MIN(id) AS firstEdge FROM related_song_map
              WHERE relatedSongId = :songId GROUP BY songId) edge
          ON song.id = edge.songId
        ORDER BY edge.firstEdge
        LIMIT :limit
    """

    /**
     * One step further out: what the song's own neighbours list, the songs most of them list first.
     * Never the song itself, which its neighbours often list back.
     */
    const val NEIGHBOURS_LIST = """
        SELECT song.* FROM song
        JOIN (SELECT r2.relatedSongId AS id, COUNT(DISTINCT r2.songId) AS votes, MIN(r2.id) AS firstEdge
              FROM related_song_map r1 JOIN related_song_map r2 ON r2.songId = r1.relatedSongId
              WHERE r1.songId = :songId AND r2.relatedSongId != :songId
              GROUP BY r2.relatedSongId) hop
          ON song.id = hop.id
        ORDER BY hop.votes DESC, hop.firstEdge
        LIMIT :limit
    """

    /**
     * The listener's own: the songs heard for half a minute or more in the listening sessions the
     * song was heard in, those that shared the most sessions with it first, then the latest.
     */
    const val HEARD_WITH = """
        SELECT song.* FROM song
        JOIN (SELECT other.songId AS id, COUNT(DISTINCT other.sessionId) AS sessions, MAX(other.startedAt) AS lastAt
              FROM listen seed JOIN listen other ON other.sessionId = seed.sessionId
              WHERE seed.songId = :songId AND other.songId != :songId AND other.playedMs >= 30000
              GROUP BY other.songId) heard
          ON song.id = heard.id
        ORDER BY heard.sessions DESC, heard.lastAt DESC
        LIMIT :limit
    """

    /**
     * The songs of the song's own artists, those the listener heard most first (half a minute or
     * more counts), then those liked, then the rest.
     */
    const val BY_ITS_ARTISTS = """
        SELECT song.* FROM song
        JOIN (SELECT DISTINCT m.songId AS id FROM song_artist_map m
              WHERE m.artistId IN (SELECT artistId FROM song_artist_map WHERE songId = :songId)
                AND m.songId != :songId) theirs
          ON song.id = theirs.id
        LEFT JOIN (SELECT songId, COUNT(*) AS plays FROM listen WHERE playedMs >= 30000 GROUP BY songId) heard
          ON heard.songId = song.id
        ORDER BY COALESCE(heard.plays, 0) DESC, song.liked DESC, song.id
        LIMIT :limit
    """

    /** What keeps a song or an artist out of what the app picks, while in force (RecommendationExclusion). */
    const val EXCLUSIONS_IN_FORCE =
        "SELECT kind, targetId, label, reason FROM recommendation_exclusion WHERE expiresAt IS NULL OR expiresAt > :now"
}
