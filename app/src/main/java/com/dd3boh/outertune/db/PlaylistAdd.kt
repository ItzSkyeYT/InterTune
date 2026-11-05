/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

/**
 * Which of the songs asked for go into a playlist, and at which positions.
 *
 * A playlist's map row has a foreign key on the playlist and one on the song, and SQLite's OR
 * IGNORE does not cover a foreign key: the insert throws, and thrown on a coroutine with no
 * handler that ended the app (#62, 0.12.5). So the add looks first, in the transaction it writes
 * in, at whether the playlist is there and which of the ids are rows in the song table, and writes
 * only those. Pure, so that a test can say what it does; PlaylistsDao.addSongToPlaylist reads the
 * database and hands the answers to [plan].
 */
object PlaylistAdd {

    /** One map row to write: [songId] at [position]. */
    data class Row(val songId: String, val position: Int)

    /** What to write, in order, and how many of the songs asked for are left out. */
    class Plan(val rows: List<Row>, val skipped: Int)

    /**
     * [songIds] in the order given, with repeats kept (Add anyway puts a song in twice), each at
     * the next position from [firstPosition]. A song that is not in [inTable] is left out and
     * takes no position, so leaving one out leaves no gap in the playlist. A playlist that is not
     * there takes nothing.
     */
    fun plan(songIds: List<String>, playlistExists: Boolean, inTable: Set<String>, firstPosition: Int): Plan {
        if (!playlistExists) return Plan(emptyList(), songIds.size)
        var position = firstPosition
        val rows = songIds.filter { it in inTable }.map { Row(it, position++) }
        return Plan(rows, songIds.size - rows.size)
    }
}
