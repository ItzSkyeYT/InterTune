/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

/**
 * Puts the songs of albums stored under a made-up id back into their real albums, once, for the
 * libraries that already hold such rows. The migration to schema 25 runs it: see Migration24To25.
 *
 * Up to 0.10.9.6 a song's album was looked up by title alone, and one not found was stored under a
 * random genre id, LG and 8 letters, instead of its own (AlbumRows.storedAlbumFor keeps the real
 * id now). Library listed these with no artist, opening one asked YouTube for the made-up id, and
 * every later song with the same album title went into the first row of that title, whatever album
 * it was from. A library copied on 24 Sep 2026 had 30628 of its 30647 album rows made this way,
 * and 2358 of them held songs of several albums.
 *
 * The real id was never lost: the song row keeps the album id its metadata named, which is what
 * the lookup ignored. So each song in such a row goes to that album, made from the row when it is
 * not stored yet: the row's title and bookmark, the album's own first song's thumbnail, and counts
 * of what it then holds. A row holding several albums becomes several, an album spread over
 * several rows becomes one, and a song whose album is already stored joins it, counted there
 * unless it was in it already. Year, theme colour and playlist id are left for the album's page to
 * fill when it is opened, as the song path never set them on these rows.
 *
 * Left alone: files on the phone, whose album id is a new one from every scan, so the id they carry
 * names no album (their album is found by title, and the row works as it is), and a song with no
 * album id at all. A row keeping such songs stays, its counts made to match what is left; one left
 * holding only files becomes local, so it opens from the phone and later files find it by title.
 * A row with no songs left is deleted, since nothing can open, fill or refetch it. Genres, the only
 * rightful owners of LG ids, are not touched.
 *
 * Plain statements, one per execSQL, none starting with WITH, SELECT or PRAGMA, which the driver
 * would run as a query. Only what SQLite 3.9 has, the version on Android 7, the oldest this runs
 * on: no window functions, UPSERT, UPDATE FROM or row values. Running it again, or on a library
 * with none of these rows, changes nothing.
 */
object LgAlbumRepair {

    /** An id from GenreEntity.generateGenreId, matched whole: a video id can start with LG too. */
    private const val LG_ID = "'LG[A-Za-z][A-Za-z][A-Za-z][A-Za-z][A-Za-z][A-Za-z][A-Za-z][A-Za-z]'"

    val STEPS: List<String> = listOf(
        // TEMP tables, seen only by the connection running this. Dropped at the end, and first as
        // well so a run never starts from another's leftovers.
        "DROP TABLE IF EXISTS temp.lg_move",
        "DROP TABLE IF EXISTS temp.lg_new",
        "DROP TABLE IF EXISTS temp.lg_order",

        // One row per song in an LG album that names an album of its own. seq is the mapping's
        // rowid: the order the songs were filed in, and how the mapping is found again below. Only
        // mappings to a row that is there: one left pointing at nothing has no row to make from.
        // isLocal <> 1 rather than = 0: on Android before 11 a row older than the column reads the
        // text 'false' from its DEFAULT false, and that is a song from YouTube.
        """
        CREATE TEMP TABLE lg_move (
            seq INTEGER PRIMARY KEY, songId TEXT NOT NULL, lgId TEXT NOT NULL, realId TEXT NOT NULL,
            duration INTEGER NOT NULL, alreadyIn INTEGER NOT NULL, realExists INTEGER NOT NULL, slot INTEGER
        )
        """,
        """
        INSERT INTO lg_move (seq, songId, lgId, realId, duration, alreadyIn, realExists)
        SELECT m.rowid, m.songId, m.albumId, s.albumId, max(s.duration, 0),
            EXISTS (SELECT 1 FROM song_album_map x WHERE x.songId = m.songId AND x.albumId = s.albumId),
            EXISTS (SELECT 1 FROM album r WHERE r.id = s.albumId)
        FROM song_album_map m JOIN album lg ON lg.id = m.albumId JOIN song s ON s.id = m.songId
        WHERE m.albumId GLOB $LG_ID AND s.isLocal <> 1
            AND trim(s.albumId) <> '' AND s.albumId NOT GLOB $LG_ID
        """,
        "CREATE INDEX temp.lg_move_real ON lg_move (realId, seq)",
        "CREATE INDEX temp.lg_move_lg ON lg_move (lgId)",

        // Where each song goes in its album: after the songs a stored album has, in the order they
        // were filed, as the song path places them. An album's page puts them in its own order when
        // it is opened.
        """
        UPDATE lg_move SET slot =
            (CASE WHEN realExists THEN (SELECT r.songCount FROM album r WHERE r.id = lg_move.realId) ELSE 0 END)
            + (SELECT count(*) FROM lg_move x WHERE x.realId = lg_move.realId AND x.seq < lg_move.seq AND NOT x.alreadyIn)
        WHERE NOT alreadyIn
        """,

        // The albums to make. Each takes the place, title and details of the first LG row it was
        // in, and is counted by distinct song: one song can sit in two of these rows.
        """
        CREATE TEMP TABLE lg_new (
            realId TEXT PRIMARY KEY, fromRowid INTEGER NOT NULL, firstSeq INTEGER NOT NULL,
            songCount INTEGER NOT NULL, duration INTEGER NOT NULL, bookmarkedAt INTEGER
        )
        """,
        """
        INSERT INTO lg_new (realId, fromRowid, firstSeq, songCount, duration, bookmarkedAt)
        SELECT realId, min(fromRowid), min(firstSeq), count(*), sum(duration), min(bookmarkedAt)
        FROM (
            SELECT m.realId AS realId, min(a.rowid) AS fromRowid, min(m.seq) AS firstSeq,
                max(m.duration) AS duration, min(a.bookmarkedAt) AS bookmarkedAt
            FROM lg_move m JOIN album a ON a.id = m.lgId
            WHERE NOT m.realExists
            GROUP BY m.realId, m.songId
        )
        GROUP BY realId
        """,

        // The thumbnail is the album's own first song's, which for the row's first album is the
        // row's own: the song path took it from the song that made the row.
        """
        INSERT OR IGNORE INTO album (id, playlistId, title, year, thumbnailUrl, themeColor, songCount, duration, lastUpdateTime, bookmarkedAt, isLocal)
        SELECT n.realId, NULL, lg.title, NULL,
            coalesce((SELECT s.thumbnailUrl FROM song_album_map m JOIN song s ON s.id = m.songId WHERE m.rowid = n.firstSeq), lg.thumbnailUrl),
            NULL, n.songCount, n.duration, lg.lastUpdateTime, n.bookmarkedAt, 0
        FROM lg_new n JOIN album lg ON lg.rowid = n.fromRowid
        """,

        // A stored album counts the songs new to it, and is saved if a row its songs came from
        // was: that row was the album as far as anyone could see.
        """
        UPDATE album SET
            songCount = songCount + (SELECT count(*) FROM song s WHERE s.id IN
                (SELECT m.songId FROM lg_move m WHERE m.realId = album.id AND NOT m.alreadyIn)),
            duration = duration + (SELECT coalesce(sum(max(s.duration, 0)), 0) FROM song s WHERE s.id IN
                (SELECT m.songId FROM lg_move m WHERE m.realId = album.id AND NOT m.alreadyIn)),
            bookmarkedAt = coalesce(bookmarkedAt,
                (SELECT min(a.bookmarkedAt) FROM lg_move m JOIN album a ON a.id = m.lgId WHERE m.realId = album.id))
        WHERE id IN (SELECT realId FROM lg_move WHERE realExists)
        """,

        // Moved in place, keeping the mapping's rowid. IGNORE for a song filed in two rows of the
        // same album: its second mapping is deleted below, with those of songs already in theirs.
        """
        UPDATE OR IGNORE song_album_map SET
            albumId = (SELECT m.realId FROM lg_move m WHERE m.seq = song_album_map.rowid),
            `index` = (SELECT m.slot FROM lg_move m WHERE m.seq = song_album_map.rowid)
        WHERE rowid IN (SELECT seq FROM lg_move WHERE NOT alreadyIn)
        """,
        "DELETE FROM song_album_map WHERE rowid IN (SELECT seq FROM lg_move) AND albumId GLOB $LG_ID",

        // A row that lost songs but keeps some is counted again, and becomes local when what it
        // keeps is only files on the phone. A local album is opened from the phone, and only a local
        // one is found by title for the next file.
        """
        UPDATE album SET
            songCount = (SELECT count(*) FROM song_album_map x WHERE x.albumId = album.id),
            duration = (SELECT coalesce(sum(max(s.duration, 0)), 0) FROM song_album_map x JOIN song s ON s.id = x.songId WHERE x.albumId = album.id),
            isLocal = CASE WHEN EXISTS (SELECT 1 FROM song_album_map x JOIN song s ON s.id = x.songId
                WHERE x.albumId = album.id AND s.isLocal <> 1) THEN isLocal ELSE 1 END
        WHERE id IN (SELECT lgId FROM lg_move) AND EXISTS (SELECT 1 FROM song_album_map x WHERE x.albumId = album.id)
        """,

        // Foreign keys are off during a migration, so nothing cascades: the artist rows go by hand.
        // The song path never gave these rows an artist, but nothing may point at a deleted album.
        "DELETE FROM album_artist_map WHERE albumId GLOB $LG_ID AND NOT EXISTS (SELECT 1 FROM song_album_map x WHERE x.albumId = album_artist_map.albumId)",
        "DELETE FROM album WHERE id GLOB $LG_ID AND NOT EXISTS (SELECT 1 FROM song_album_map x WHERE x.albumId = album.id)",

        // Library sorts albums by rowid for date added, newest first by default. The new rows were
        // added last, so every album is numbered again in the order it had, each new one in the
        // place of the row it came from, the albums of one row in the order their songs came.
        // Numbering starts past the highest rowid in use, so no two rows ever share one, and it
        // only happens when an album was made.
        "CREATE TEMP TABLE lg_order (seq INTEGER PRIMARY KEY, id TEXT UNIQUE)",
        "INSERT INTO lg_order (seq, id) SELECT max(rowid), NULL FROM album",
        """
        INSERT INTO lg_order (id)
        SELECT a.id FROM album a LEFT JOIN lg_new n ON n.realId = a.id
        WHERE EXISTS (SELECT 1 FROM lg_new)
        ORDER BY coalesce(n.fromRowid, a.rowid), coalesce(n.firstSeq, 0), a.id
        """,
        "UPDATE album SET rowid = (SELECT o.seq FROM lg_order o WHERE o.id = album.id) WHERE id IN (SELECT id FROM lg_order)",

        "DROP TABLE IF EXISTS temp.lg_order",
        "DROP TABLE IF EXISTS temp.lg_new",
        "DROP TABLE IF EXISTS temp.lg_move",
    )
}
