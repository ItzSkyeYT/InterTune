/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.db.SchemaDb
import com.dd3boh.outertune.db.entities.Listen
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** The backfill over a plain JDBC connection, with the same SQL the Room implementation runs. */
class JdbcBackfillIo(private val db: Connection) : BackfillIo {
    override fun pendingEvents(afterId: Long, limit: Int): List<LegacyEventRow> =
        db.prepareStatement(
            """SELECT e.id, e.songId, e.timestamp, e.playTime, s.duration FROM event e JOIN song s ON s.id = e.songId
               WHERE e.id > ? AND NOT EXISTS (SELECT 1 FROM listen l WHERE l.sourceEventId = e.id) ORDER BY e.id LIMIT ?"""
        ).use { ps ->
            ps.setLong(1, afterId); ps.setInt(2, limit)
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(LegacyEventRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getInt(5))) }
            }
        }

    override fun lastBackfilled(): BackfillCursor? =
        db.createStatement().use { st ->
            st.executeQuery("SELECT endedAt, sessionId FROM listen WHERE sourceEventId IS NOT NULL ORDER BY sourceEventId DESC LIMIT 1").use { rs ->
                if (rs.next()) BackfillCursor(rs.getLong(1), rs.getLong(2)) else null
            }
        }

    override fun insertLegacyListens(rows: List<Listen>) {
        db.autoCommit = false
        db.prepareStatement(
            """INSERT OR IGNORE INTO listen(songId, startedAt, endedAt, tzOffsetMin, playedMs, durationMs, ratio, endReason, origin, originSlot,
               queueId, autoplayDepth, sessionId, counted, learn, runId, endPositionMs, contextChip, sourceEventId)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,1,0,-1,0,?)"""
        ).use { ps ->
            rows.forEach { l ->
                ps.setString(1, l.songId); ps.setLong(2, l.startedAt); ps.setLong(3, l.endedAt); ps.setInt(4, l.tzOffsetMin)
                ps.setLong(5, l.playedMs); ps.setLong(6, l.durationMs); ps.setFloat(7, l.ratio); ps.setInt(8, l.endReason)
                ps.setInt(9, l.origin); ps.setInt(10, l.originSlot); ps.setLong(11, l.queueId); ps.setInt(12, l.autoplayDepth)
                ps.setLong(13, l.sessionId); ps.setInt(14, if (l.counted) 1 else 0); ps.setLong(15, l.sourceEventId!!)
                ps.addBatch()
            }
            ps.executeBatch()
        }
        db.commit(); db.autoCommit = true
    }

    override fun tidyLegacyEdges() {
        db.createStatement().use { st ->
            st.execute(DATE_LEGACY_EDGES)
            st.execute(DROP_DUPLICATE_EDGES)
        }
    }

    companion object {
        const val DATE_LEGACY_EDGES = """UPDATE related_song_map SET fetchedAt = (SELECT MIN(l.startedAt) FROM listen l WHERE l.songId = related_song_map.songId)
            WHERE fetchedAt = 0 AND EXISTS (SELECT 1 FROM listen l WHERE l.songId = related_song_map.songId)"""
        const val DROP_DUPLICATE_EDGES = """DELETE FROM related_song_map WHERE id NOT IN (SELECT MIN(id) FROM related_song_map GROUP BY songId, relatedSongId)"""
    }
}

class LegacyBackfillTest {
    private lateinit var db: Connection
    private val paris = ZoneId.of("Europe/Paris")

    @Before fun open() { db = SchemaDb.open() }
    @After fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }
    private fun count(sql: String): Long = db.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }
    private fun stored(local: LocalDateTime) = local.toInstant(ZoneOffset.UTC).toEpochMilli()
    private fun song(id: String, duration: Int = 200) = exec("INSERT INTO song(id, title, duration, liked) VALUES ('$id', '$id', $duration, 0)")
    private fun event(song: String, endedLocal: LocalDateTime, playTime: Long = 180_000) =
        exec("INSERT INTO event(songId, timestamp, playTime) VALUES ('$song', ${stored(endedLocal)}, $playTime)")

    private fun listens(): List<Map<String, Any?>> = db.createStatement().use { st ->
        st.executeQuery("SELECT * FROM listen ORDER BY sourceEventId").use { rs ->
            val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
            // JDBC hands back Integer or Long depending on the value's size; one width keeps the asserts honest.
            buildList { while (rs.next()) add(cols.associateWith { col -> rs.getObject(col).let { if (it is Int || it is Long) (it as Number).toLong() else it } }) }
        }
    }

    @Test
    fun `an event becomes a counted listen with its clock corrected`() {
        song("a")
        val ended = LocalDateTime.of(2026, 7, 14, 15, 30)   // summer: stored two hours ahead of the instant
        event("a", ended, playTime = 150_000)
        assertEquals(1, LegacyBackfill(JdbcBackfillIo(db), paris).run())
        val row = listens().single()
        val instant = ended.atZone(paris).toInstant().toEpochMilli()
        assertEquals(instant, row["endedAt"])
        assertEquals(instant - 150_000, row["startedAt"])
        assertEquals(120L, row["tzOffsetMin"])
        assertEquals(150_000L, row["playedMs"])
        assertEquals(200_000L, row["durationMs"])
        assertEquals(0.75f, (row["ratio"] as Number).toFloat(), 1e-6f)
        assertEquals(1L, row["counted"])
        assertEquals(0L, row["endReason"])
        assertEquals(1L, row["sourceEventId"])
    }

    @Test
    fun `sessions are cut at thirty minutes of silence`() {
        song("a"); song("b"); song("c")
        val t = LocalDateTime.of(2026, 1, 10, 20, 0)
        event("a", t, 180_000)
        event("b", t.plusMinutes(4), 180_000)          // 1 minute after a ended: same session
        event("c", t.plusMinutes(45), 180_000)         // 38 minutes of silence: new session
        LegacyBackfill(JdbcBackfillIo(db), paris).run()
        val sessions = listens().map { it["sessionId"] as Long }
        assertEquals(sessions[0], sessions[1])
        assertTrue(sessions[2] != sessions[1])
        assertEquals(listens()[2]["startedAt"], sessions[2])
    }

    @Test
    fun `a second run writes nothing and a resumed run continues the session`() {
        song("a"); song("b")
        val t = LocalDateTime.of(2026, 1, 10, 20, 0)
        event("a", t); event("b", t.plusMinutes(4))
        val io = JdbcBackfillIo(db)
        assertEquals(2, LegacyBackfill(io, paris, batch = 1).run())   // two events, one per batch
        assertEquals(2, count("SELECT COUNT(*) FROM listen"))
        assertEquals(0, LegacyBackfill(io, paris).run())
        assertEquals(2, count("SELECT COUNT(*) FROM listen"))
        // A third event arriving later joins the session the cursor remembers.
        song("c"); event("c", t.plusMinutes(8))
        assertEquals(1, LegacyBackfill(io, paris).run())
        assertEquals(1, count("SELECT COUNT(DISTINCT sessionId) FROM listen"))
    }

    @Test
    fun `an event already linked to a live listen is skipped`() {
        song("a")
        event("a", LocalDateTime.of(2026, 1, 10, 20, 0))
        exec("""INSERT INTO listen(songId, startedAt, endedAt, tzOffsetMin, playedMs, durationMs, ratio, endReason, origin, originSlot, queueId,
            autoplayDepth, sessionId, counted, sourceEventId) VALUES ('a', 1, 2, 60, 180000, 200000, 0.9, 1, 1, -1, 0, 0, 1, 1, 1)""")
        assertEquals(0, LegacyBackfill(JdbcBackfillIo(db), paris).run())
        assertEquals(1, count("SELECT COUNT(*) FROM listen"))
    }

    @Test
    fun `unknown lengths give no ratio`() {
        song("a", duration = -1)
        event("a", LocalDateTime.of(2026, 1, 10, 20, 0))
        LegacyBackfill(JdbcBackfillIo(db), paris).run()
        assertEquals(-1L, listens().single()["durationMs"])
        assertEquals(-1f, (listens().single()["ratio"] as Number).toFloat(), 0f)
    }

    @Test
    fun `legacy edges are dated by their seed's first listen and duplicates dropped`() {
        song("seed"); song("x"); song("y"); song("never")
        val t = LocalDateTime.of(2026, 1, 10, 20, 0)
        event("seed", t, 100_000); event("seed", t.plusDays(1), 100_000)
        exec("INSERT INTO related_song_map(songId, relatedSongId) VALUES ('seed', 'x'), ('seed', 'x'), ('seed', 'y'), ('never', 'x')")
        LegacyBackfill(JdbcBackfillIo(db), paris).run()
        val firstStart = t.atZone(paris).toInstant().toEpochMilli() - 100_000
        assertEquals(2, count("SELECT COUNT(*) FROM related_song_map WHERE songId = 'seed'"))
        assertEquals(2, count("SELECT COUNT(*) FROM related_song_map WHERE songId = 'seed' AND fetchedAt = $firstStart"))
        assertEquals(1, count("SELECT COUNT(*) FROM related_song_map WHERE songId = 'never' AND fetchedAt = 0"))
        // Running it again changes nothing.
        LegacyBackfill(JdbcBackfillIo(db), paris).run()
        assertEquals(3, count("SELECT COUNT(*) FROM related_song_map"))
    }
}
