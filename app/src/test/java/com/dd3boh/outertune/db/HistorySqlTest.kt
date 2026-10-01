/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import com.dd3boh.outertune.db.daos.SongsDao
import com.dd3boh.outertune.db.entities.HistoryPiece
import com.dd3boh.outertune.db.entities.HistoryPlay
import com.dd3boh.outertune.history.HistoryRemoval
import com.dd3boh.outertune.history.HistoryRemovalIo
import com.dd3boh.outertune.history.HistoryRule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.ResultSet

/**
 * What History lists and what Remove from history does, run on the JVM against the schema Room
 * exported, with the same text the DAO runs.
 */
class HistorySqlTest {
    private lateinit var db: Connection
    private val t = 1_790_000_000_000L
    private val second = 1_000L
    private val minute = 60_000L

    @Before
    fun open() {
        db = SchemaDb.open()
        for (id in listOf("a", "b", "c", "x", "y")) exec("INSERT INTO song(id, title, duration, liked) VALUES ('$id', '$id', 200, 0)")
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    private fun lastId(): Long = db.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }

    private fun count(sql: String): Long = db.createStatement().use { st -> st.executeQuery(sql).use { it.next(); it.getLong(1) } }

    /** A closed listen; ENDED (1) unless said otherwise, a three minute song of known length. */
    private fun listen(
        song: String,
        startedAt: Long,
        playedMs: Long,
        counted: Boolean = false,
        endReason: Int = 1,
        continues: Long? = null,
        sourceEventId: Long? = null,
        durationMs: Long = 200_000,
    ): Long {
        val ratio = if (durationMs > 0) playedMs.toFloat() / durationMs else -1f
        exec(
            """INSERT INTO listen(songId, startedAt, endedAt, tzOffsetMin, playedMs, durationMs, ratio, endReason, origin,
                originSlot, queueId, autoplayDepth, sessionId, counted, continuesListenId, sourceEventId)
            VALUES ('$song', $startedAt, ${startedAt + playedMs}, 120, $playedMs, $durationMs, $ratio, $endReason, 2, -1, 0, 0,
                $startedAt, ${if (counted) 1 else 0}, ${continues ?: "NULL"}, ${sourceEventId ?: "NULL"})"""
        )
        return lastId()
    }

    /** A counted play the old way, as the service writes it beside its listen. */
    private fun event(song: String, timestamp: Long, playTime: Long): Long {
        exec("INSERT INTO event(songId, timestamp, playTime) VALUES ('$song', $timestamp, $playTime)")
        return lastId()
    }

    /** A counted play as the service records it today: the listen, its event, and the link between them. */
    private fun countedPlay(song: String, startedAt: Long, playedMs: Long = 3 * minute, continues: Long? = null, endReason: Int = 1): Long {
        val eventId = event(song, startedAt + playedMs + 2 * 60 * minute, playedMs)
        return listen(song, startedAt, playedMs, counted = true, continues = continues, sourceEventId = eventId, endReason = endReason)
    }

    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }
    private fun ResultSet.intOrNull(column: String): Int? = getInt(column).takeUnless { wasNull() }

    private fun plays(): List<HistoryPlay> = db.prepareStatement(HistorySql.PLAYS).use { ps ->
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
                        sortAt = rs.getLong("sortAt"),
                    )
                )
            }
        }
    }

    private fun songsInHistory() = plays().map { it.songId }

    /** The same steps the DAO runs, through the same SQL. */
    private val io = object : HistoryRemovalIo {
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
    }

    private fun remove(vararg plays: HistoryPlay) = HistoryRemoval.remove(io, plays.toList(), t + 100 * minute)

    private fun mostPlayed(): List<String> = db.prepareStatement(SongsDao.MOST_PLAYED_SONGS).use { ps ->
        ps.setLong(1, -1L)
        ps.setLong(2, 10)
        ps.setLong(3, 0)
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
    }

    @Test
    fun `a skip heard for five seconds is in History, a shorter one is not`() {
        listen("a", t, playedMs = 5 * second, endReason = 2)
        listen("b", t + minute, playedMs = 5 * second - 1, endReason = 2)
        listen("c", t + 2 * minute, playedMs = 0, endReason = 2)
        assertEquals(listOf("a"), songsInHistory())
    }

    @Test
    fun `the SQL keeps exactly the plays HistoryRule keeps`() {
        val cases = listOf(0L, 1L, 4_999L, 5_000L, 5_001L, 3 * minute).flatMap { ms -> listOf(ms to false, ms to true) }
        val ids = cases.mapIndexed { i, (ms, counted) -> listen("a", t + i * minute, playedMs = ms, counted = counted, endReason = 2) }
        val shown = plays().mapNotNull { it.listenId }.toSet()
        cases.forEachIndexed { i, (ms, counted) ->
            assertEquals("$ms ms, counted $counted", HistoryRule.shows(ms, counted), ids[i] in shown)
        }
    }

    @Test
    fun `a play of unknown length is judged by the time heard alone`() {
        listen("a", t, playedMs = 6 * second, durationMs = -1)
        assertEquals(listOf("a"), songsInHistory())
    }

    @Test
    fun `a counted play under five seconds stays in History`() {
        countedPlay("a", t, playedMs = 3 * second)
        assertEquals(listOf("a"), songsInHistory())
    }

    @Test
    fun `a song still playing is not in History yet`() {
        listen("a", t, playedMs = 2 * minute, endReason = 6)
        assertEquals(emptyList<String>(), songsInHistory())
    }

    @Test
    fun `a counted play is listed once, from its listen, not again from its event`() {
        val id = countedPlay("a", t)
        val play = plays().single()
        assertEquals(id, play.listenId)
        assertEquals(null, play.eventId)
        assertTrue(play.counted)
    }

    @Test
    fun `newest first`() {
        listen("a", t, playedMs = minute)
        listen("b", t + 10 * minute, playedMs = minute)
        countedPlay("c", t + 5 * minute)
        assertEquals(listOf("b", "c", "a"), songsInHistory())
    }

    @Test
    fun `a song resumed where it stopped is one play, dated from its first piece`() {
        val first = listen("a", t, playedMs = 20 * second, endReason = 4)
        val middle = listen("a", t + 30 * minute, playedMs = 40 * second, endReason = 4, continues = first)
        countedPlay("a", t + 50 * minute, playedMs = 2 * minute, continues = middle)
        val play = plays().single()
        assertEquals(first, play.listenId)
        assertEquals(t, play.startedAt)
        assertEquals(20 * second + 40 * second + 2 * minute, play.playedMs)
        assertTrue(play.counted)
    }

    @Test
    fun `pieces too short alone are one play long enough together`() {
        val first = listen("a", t, playedMs = 3 * second, endReason = 4)
        listen("a", t + minute, playedMs = 3 * second, endReason = 2, continues = first)
        assertEquals(listOf(first to 6 * second), plays().map { it.listenId to it.playedMs })
    }

    @Test
    fun `a resume still playing leaves the play as it stood`() {
        val first = listen("a", t, playedMs = 20 * second, endReason = 4)
        listen("a", t + minute, playedMs = minute, endReason = 6, continues = first)
        assertEquals(listOf(first to 20 * second), plays().map { it.listenId to it.playedMs })
    }

    @Test
    fun `an event the backfill has not reached is shown from the event`() {
        val id = event("a", t, playTime = 3 * minute)
        val play = plays().single()
        assertEquals(id, play.eventId)
        assertEquals(null, play.listenId)
        assertEquals(t, play.timestamp)
        assertEquals(3 * minute, play.playedMs)
    }

    @Test
    fun `a play removed from History before this stays out`() {
        // The old History deleted the event; the listen it was linked to stayed for the engine.
        val eventId = event("a", t, playTime = 3 * minute)
        listen("a", t, playedMs = 3 * minute, counted = true, sourceEventId = eventId)
        exec("DELETE FROM event WHERE id = $eventId")
        assertEquals(emptyList<String>(), songsInHistory())
    }

    @Test
    fun `remove from history takes the play out and its events with it, and keeps every listen`() {
        val first = listen("a", t, playedMs = 20 * second, endReason = 4)
        countedPlay("a", t + 30 * minute, playedMs = 2 * minute, continues = first)
        val skip = listen("b", t + 40 * minute, playedMs = 10 * second, endReason = 2)
        event("c", t + 50 * minute, playTime = 3 * minute)
        countedPlay("y", t + 60 * minute)
        assertEquals(listOf("y", "c", "b", "a"), songsInHistory())
        assertEquals(setOf("a", "c", "y"), mostPlayed().toSet())

        remove(*plays().filter { it.songId != "y" }.toTypedArray())

        assertEquals(listOf("y"), songsInHistory())
        // Off Most played, as removing a play always took it off.
        assertEquals(listOf("y"), mostPlayed())
        // Every listen is still there for the engine, with what it learns from untouched.
        assertEquals(4L, count("SELECT COUNT(*) FROM listen"))
        assertEquals(4L, count("SELECT COUNT(*) FROM listen WHERE learn = 1"))
        assertEquals(1L, count("SELECT COUNT(*) FROM listen_signal WHERE listenId = $skip"))
    }

    @Test
    fun `removing a skip touches nothing but History`() {
        val skip = listen("b", t, playedMs = 10 * second, endReason = 2)
        countedPlay("a", t + minute)
        remove(plays().single { it.songId == "b" })
        assertEquals(listOf("a"), songsInHistory())
        assertEquals(listOf("a"), mostPlayed())
        assertEquals(1L, count("SELECT COUNT(*) FROM event"))
        assertEquals(1L, count("SELECT COUNT(*) FROM listen WHERE id = $skip AND learn = 1"))
    }

    @Test
    fun `removing a play also covers the resume still playing`() {
        val first = listen("a", t, playedMs = 20 * second, endReason = 4)
        val open = listen("a", t + minute, playedMs = minute, endReason = 6, continues = first)
        remove(plays().single())
        // It closes as the service closes every row; it must not come back as a play of its own.
        exec("UPDATE listen SET endReason = 1, counted = 1 WHERE id = $open")
        assertEquals(emptyList<String>(), songsInHistory())
    }

    @Test
    fun `a resume after a removal is a new play`() {
        val first = listen("a", t, playedMs = 20 * second, endReason = 4)
        remove(plays().single())
        val later = listen("a", t + 60 * minute, playedMs = minute, endReason = 2, continues = first)
        assertEquals(listOf(later to minute), plays().map { it.listenId to it.playedMs })
    }

    @Test
    fun `removing a play leaves the play after an earlier removal alone`() {
        val a = listen("a", t, playedMs = 20 * second, endReason = 4)
        val b = countedPlay("a", t + minute, playedMs = 2 * minute, continues = a, endReason = 4)
        val c = listen("a", t + 4 * minute, playedMs = 20 * second, endReason = 2, continues = b)
        // The old History listed b alone, and it was removed there, which deleted its event.
        exec("DELETE FROM event")
        // a and c are then two plays.
        assertEquals(setOf(a, c), plays().map { it.listenId }.toSet())

        remove(plays().single { it.listenId == a })
        assertEquals(listOf(c), plays().map { it.listenId })
    }

    @Test
    fun `ten quick skips are ten rows in History and no plays at all`() {
        repeat(10) { listen("x", t + it * minute, playedMs = 6 * second, endReason = 2) }
        countedPlay("y", t + 20 * minute)
        assertEquals(11, songsInHistory().size)
        assertEquals(listOf("y"), mostPlayed())
    }

    @Test
    fun `clear listen history empties History`() {
        countedPlay("a", t)
        listen("b", t + minute, playedMs = 10 * second, endReason = 2)
        event("c", t + 2 * minute, playTime = minute)
        // DatabaseDao.clearListenHistory's deletes for the tables History reads.
        exec("DELETE FROM event")
        exec("DELETE FROM listen_signal")
        exec("DELETE FROM listen")
        assertEquals(emptyList<String>(), songsInHistory())
    }
}
