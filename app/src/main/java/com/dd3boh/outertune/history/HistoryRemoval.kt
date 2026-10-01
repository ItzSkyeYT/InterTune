/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.history

import com.dd3boh.outertune.db.MusicDatabase
import com.dd3boh.outertune.db.entities.HistoryPiece
import com.dd3boh.outertune.db.entities.HistoryPlay

/** What removal needs from the database, so the same steps run against Room and against a JDBC copy in tests. */
interface HistoryRemovalIo {
    /** The pieces of the play starting at listen [head], see HistorySql.CHAIN. */
    fun chain(head: Long): List<HistoryPiece>
    fun markRemoved(piece: HistoryPiece, at: Long)
    fun deleteEvent(id: Long)
}

/**
 * Remove from history, for one play or many.
 *
 * Every piece of the play gets a removal mark, which takes it out of History, and the event of
 * any piece that counted is deleted, as Remove from history always did: that is the play coming
 * off Most played, Keep listening and the other lists that read events. The listen rows stay,
 * because the engine learns from them, as it did when History was the event table and removing a
 * play left its listen alone.
 *
 * A skip heard for five seconds has no event, play count, YouTube or Last.fm entry to undo, so
 * removing one only takes it out of History. Leaving its listen for the engine keeps one rule for
 * every row: History's own control never changes what recommendations learn from, which is what
 * Forget the last session and Forget today's listening, under Recommendations, are for.
 */
object HistoryRemoval {
    fun remove(io: HistoryRemovalIo, plays: List<HistoryPlay>, at: Long) {
        for (play in plays) {
            val head = play.listenId
            if (head != null) {
                for (piece in io.chain(head)) {
                    io.markRemoved(piece, at)
                    piece.sourceEventId?.let(io::deleteEvent)
                }
            } else {
                play.eventId?.let(io::deleteEvent)
            }
        }
    }
}

class DatabaseHistoryIo(private val database: MusicDatabase) : HistoryRemovalIo {
    override fun chain(head: Long): List<HistoryPiece> = database.historyChain(head)
    override fun markRemoved(piece: HistoryPiece, at: Long) = database.markRemovedFromHistory(piece.id, piece.songId, at)
    override fun deleteEvent(id: Long) = database.deleteEvent(id)
}
