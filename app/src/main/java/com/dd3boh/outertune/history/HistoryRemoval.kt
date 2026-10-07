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
 * A skip heard for two seconds has no event, play count, YouTube or Last.fm entry to undo, so
 * removing one only takes it out of History. Leaving its listen for the engine keeps one rule for
 * every row: History's own control never changes what recommendations learn from, which is what
 * Forget the last session and Forget today's listening, under Recommendations, are for.
 */
object HistoryRemoval {
    /**
     * How long one transaction may run before the next play waits for a new one. While it runs no
     * other write gets in, the listen log's among them: when a song stops, MusicService waits two
     * seconds for the row it opened at the start, and writes the play again whole if it is not there.
     */
    const val TRANSACTION_BUDGET_MS = 100L

    /**
     * Removes [plays] in short transactions, as many plays in each as fit in [budgetMs] and at
     * least one, so a play is never half removed and Select all on a large history never holds up
     * the writes of the song playing. [transaction] runs its block in one transaction. A failure
     * rolls back the transaction it happens in and ends the removal there, with the plays before
     * it removed.
     */
    fun remove(
        io: HistoryRemovalIo,
        plays: List<HistoryPlay>,
        at: Long,
        transaction: (block: () -> Unit) -> Unit,
        budgetMs: Long = TRANSACTION_BUDGET_MS,
        nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
    ) {
        var next = 0
        while (next < plays.size) {
            transaction {
                val started = nowMs()
                do removePlay(io, plays[next++], at) while (next < plays.size && nowMs() - started < budgetMs)
            }
        }
    }

    /** One play: a mark on each piece and its event deleted, or the event of a row that is one. */
    private fun removePlay(io: HistoryRemovalIo, play: HistoryPlay, at: Long) {
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

class DatabaseHistoryIo(private val database: MusicDatabase) : HistoryRemovalIo {
    override fun chain(head: Long): List<HistoryPiece> = database.historyChain(head)
    override fun markRemoved(piece: HistoryPiece, at: Long) = database.markRemovedFromHistory(piece.id, piece.songId, at)
    override fun deleteEvent(id: Long) = database.deleteEvent(id)
}
