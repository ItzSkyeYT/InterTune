/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.entities.HistoryPiece
import com.dd3boh.outertune.db.entities.HistoryPlay
import com.dd3boh.outertune.history.HistoryRemovalIo
import java.sql.Connection
import java.sql.ResultSet

/**
 * History's reads and Remove from history's steps over JDBC, through the same SQL the DAO runs,
 * for HistorySqlTest and HistoryTrial.
 */
class JdbcHistory(private val db: Connection) : HistoryRemovalIo {
    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
    private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

    fun plays(): List<HistoryPlay> = db.prepareStatement(HistorySql.PLAYS).use { ps ->
        ps.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(
                    HistoryPlay(
                        listenId = rs.longOrNull("listenId"),
                        eventId = rs.longOrNull("eventId"),
                        songId = rs.getString("songId"),
                        startedAt = rs.longOrNull("startedAt"),
                        endedAt = rs.longOrNull("endedAt"),
                        tzOffsetMin = rs.intOrNull("tzOffsetMin"),
                        timestamp = rs.longOrNull("timestamp"),
                        playedMs = rs.getLong("playedMs"),
                        counted = rs.getBoolean("counted"),
                        headPlayedMs = rs.getLong("headPlayedMs"),
                        sortAt = rs.getLong("sortAt"),
                    )
                )
            }
        }
    }

    /** The pieces PLAYS puts under each first piece, by PLAYS' own walk, whether or not the play is long enough to list. */
    fun groups(): Map<Long, Set<Long>> =
        db.prepareStatement("${HistorySql.UP} SELECT up.id AS id, up.cur AS head FROM up WHERE ${HistorySql.UP_AT_HEAD}").use { ps ->
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getLong("head") to rs.getLong("id")) }
            }
        }.groupBy({ it.first }, { it.second }).mapValues { it.value.toSet() }

    override fun chain(head: Long): List<HistoryPiece> = db.prepareStatement(HistorySql.CHAIN).use { ps ->
        ps.setLong(1, head)
        ps.executeQuery().use { rs ->
            buildList { while (rs.next()) add(HistoryPiece(rs.getLong("id"), rs.getString("songId"), rs.longOrNull("sourceEventId"))) }
        }
    }

    override fun markRemoved(piece: HistoryPiece, at: Long) {
        db.prepareStatement(HistorySql.MARK_REMOVED).use { ps ->
            ps.setLong(1, piece.id)
            ps.setString(2, piece.songId)
            ps.setLong(3, at)
            ps.executeUpdate()
        }
    }

    override fun deleteEvent(id: Long) {
        db.prepareStatement(HistorySql.DELETE_EVENT).use { ps ->
            ps.setLong(1, id)
            ps.executeUpdate()
        }
    }

    /** [block] in one transaction, rolled back if it throws, as Room's runInTransaction does. */
    fun transaction(block: () -> Unit) {
        db.autoCommit = false
        try {
            block()
            db.commit()
        } catch (e: Throwable) {
            db.rollback()
            throw e
        } finally {
            db.autoCommit = true
        }
    }
}
