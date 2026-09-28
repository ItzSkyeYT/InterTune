/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.db

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.ResultSet

/**
 * The Stats page's reads, run on the JVM against the schema Room exported, the same text the DAO
 * runs. A wrong read here still draws a page full of plausible numbers, so the rows are pinned.
 */
class StatsSqlTest {
    private lateinit var db: Connection
    private val t = 1_790_000_000_000L
    private val minute = 60_000L

    @Before
    fun open() {
        db = SchemaDb.open()
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.execute(sql) }

    /** A song with its artists credited in the order given, inserted last first to prove the order is read from position. */
    private fun song(id: String, vararg artists: String) {
        exec("INSERT INTO song(id, title, duration, liked) VALUES ('$id', '$id', 200, 0)")
        artists.withIndex().reversed().forEach { (i, a) ->
            exec("INSERT OR IGNORE INTO artist(id, name, lastUpdateTime) VALUES ('$a', '$a', 0)")
            exec("INSERT INTO song_artist_map(songId, artistId, position) VALUES ('$id', '$a', $i)")
        }
    }

    private fun listen(
        song: String,
        startedAt: Long,
        playedMs: Long = 3 * minute,
        endReason: Int = 1,
        counted: Boolean = true,
        endedAt: Long = startedAt + playedMs,
        continues: Long? = null,
    ): Long {
        exec(
            """INSERT INTO listen(songId, startedAt, endedAt, tzOffsetMin, playedMs, durationMs, ratio, endReason, origin,
                originSlot, queueId, autoplayDepth, sessionId, counted, continuesListenId)
            VALUES ('$song', $startedAt, $endedAt, 120, $playedMs, 200000, 0.9, $endReason, 2, -1, 0, 0, $startedAt,
                ${if (counted) 1 else 0}, ${continues ?: "NULL"})"""
        )
        return db.createStatement().use { st -> st.executeQuery("SELECT last_insert_rowid()").use { it.next(); it.getLong(1) } }
    }

    /** A counted play in the event table, which the Most played lists read. */
    private fun event(song: String, at: Long, playTime: Long = 3 * minute) =
        exec("INSERT INTO event(songId, timestamp, playTime) VALUES ('$song', $at, $playTime)")

    private fun mostPlayedArtists(from: Long, limit: Long = 6): List<String> =
        query(StatsSql.MOST_PLAYED_ARTISTS, from, limit) { it.getString("id") }

    private fun <T> query(sql: String, vararg args: Long, row: (ResultSet) -> T): List<T> = db.prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, v -> ps.setLong(i + 1, v) }
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
    }

    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

    @Test
    fun `each song in the log with its first credited artist`() {
        song("s", "first", "second")
        song("lonely")
        song("never played", "someone")
        listen("s", t)
        listen("lonely", t)
        val rows = query(StatsSql.SONG_ARTISTS) { rs -> rs.getString("songId") to rs.getString("artistId") }
        assertEquals(listOf("s" to "first"), rows)
    }

    @Test
    fun `listens since a moment, and no open rows`() {
        song("s", "first", "second")
        song("lonely")
        listen("s", t - 10 * minute)
        val paused = listen("s", t + minute, endReason = 4)
        listen("s", t + 10 * minute, continues = paused)
        listen("lonely", t + 20 * minute, counted = false, endReason = 2)
        listen("s", t + 30 * minute, playedMs = 0, endReason = 6, endedAt = 0)

        val rows = query(StatsSql.LISTENS, t) { rs ->
            listOf(rs.getString("songId"), rs.getLong("endReason"), rs.getBoolean("counted"), rs.longOrNull("continuesListenId"))
        }
        assertEquals(
            setOf(
                listOf("s", 4L, true, null),
                listOf("s", 1L, true, paused),
                listOf("lonely", 2L, false, null),
            ),
            rows.toSet(),
        )
        assertEquals(3, rows.size)
        // All: from 0, and still without the open row.
        assertEquals(4, query(StatsSql.LISTENS, 0) { it.getLong("endReason") }.size)
    }

    @Test
    fun `each song before a moment, once, with its first listen and last counted play`() {
        song("s", "artist")
        listen("s", t - 30 * minute, playedMs = 20_000, counted = false, endReason = 2)
        listen("s", t - 20 * minute)
        listen("s", t - 10 * minute)
        listen("s", t + minute)

        song("other", "artist")
        listen("other", t - 5 * minute)
        val rows = query(StatsSql.SONGS_BEFORE, t) { rs ->
            listOf(rs.getString("songId"), rs.getLong("firstAt"), rs.longOrNull("lastPlayAt"), rs.getLong("playsBefore"))
        }
        assertEquals(
            setOf(listOf("s", t - 30 * minute, t - 10 * minute, 2L), listOf("other", t - 5 * minute, t - 5 * minute, 1L)),
            rows.toSet(),
        )
        assertEquals(2, rows.size)
    }

    @Test
    fun `a song only ever skipped has no last play`() {
        song("s", "artist")
        listen("s", t - 30 * minute, playedMs = 20_000, counted = false, endReason = 2)
        assertNull(query(StatsSql.SONGS_BEFORE, t) { it.longOrNull("lastPlayAt") }.single())
    }

    @Test
    fun `a row with no start is dated back from its end`() {
        song("s", "artist")
        listen("s", startedAt = 0, playedMs = 90_000, endedAt = t - 10 * minute, endReason = 2)
        assertEquals(t - 10 * minute - 90_000, query(StatsSql.SONGS_BEFORE, t) { it.getLong("firstAt") }.single())
        assertEquals(t - 10 * minute - 90_000, query(StatsSql.BOUNDS) { it.getLong("firstAt") }.single())
    }

    @Test
    fun `totals of a stretch, counted and backfilled apart`() {
        song("s", "artist")
        listen("s", t - 90 * minute, endReason = 0)
        listen("s", t - 60 * minute, playedMs = 30_000, counted = false, endReason = 2)
        listen("s", t - 30 * minute)
        listen("s", t + minute)

        val totals = query(StatsSql.TOTALS, t - 2 * 60 * minute, t) { rs ->
            listOf(rs.getLong("listens"), rs.getLong("playedMs"), rs.getLong("countedMs"), rs.getLong("legacyListens"))
        }.single()
        assertEquals(listOf(3L, 6 * minute + 30_000, 6 * minute, 1L), totals)
        // Nothing in the stretch is zeros, not nulls.
        assertEquals(listOf(0L, 0L, 0L, 0L), query(StatsSql.TOTALS, 0, 1) { rs ->
            listOf(rs.getLong("listens"), rs.getLong("playedMs"), rs.getLong("countedMs"), rs.getLong("legacyListens"))
        }.single())
    }

    @Test
    fun `the log begins at the first listen, and the live log at the first one it wrote`() {
        song("s", "artist")
        assertEquals(listOf(null, null), query(StatsSql.BOUNDS) { listOf(it.longOrNull("firstAt"), it.longOrNull("firstLiveAt")) }.single())
        listen("s", t - 100 * minute, endReason = 0)
        listen("s", t - 50 * minute, endReason = 0)
        listen("s", t - 10 * minute, endReason = 2, counted = false)
        listen("s", t + 10 * minute, endReason = 6, endedAt = 0, playedMs = 0)
        assertEquals(
            listOf(t - 100 * minute, t - 10 * minute),
            query(StatsSql.BOUNDS) { listOf(it.longOrNull("firstAt"), it.longOrNull("firstLiveAt")) }.single(),
        )
    }

    @Test
    fun `most played artists count only the plays inside the period`() {
        // YouTube-shaped ids: the row only holds artists with a page to open, so these
        // fixtures need one to be counted at all (see the filter tests further down).
        val day = 24 * 60 * minute
        song("this week", "UCweekly")
        song("earlier this month", "UCmonthly")
        song("together", "UCmain", "UCfeatured")
        song("long ago", "UCgone")
        repeat(3) { event("this week", t - (it + 1) * day) }
        repeat(10) { event("earlier this month", t - 20 * day) }
        event("earlier this month", t - day)
        repeat(2) { event("together", t - 2 * day) }
        repeat(5) { event("long ago", t - 40 * day) }

        // A week holds one of monthly's eleven plays, and gone, not played in it, does not fill a
        // place. Both artists of a song get its plays; alike in everything, they go by id.
        assertEquals(listOf("UCweekly", "UCfeatured", "UCmain", "UCmonthly"), mostPlayedArtists(t - 7 * day))
        // All of it, and then only the top two.
        assertEquals(listOf("UCmonthly", "UCgone", "UCweekly", "UCfeatured", "UCmain"), mostPlayedArtists(0))
        assertEquals(listOf("UCmonthly", "UCgone"), mostPlayedArtists(0, limit = 2))
    }

    @Test
    fun `most played artists count every play, and under each the songs played and downloaded`() {
        val day = 24 * 60 * minute
        song("saved, never played", "UCartist")
        song("downloaded", "UCartist")
        song("heard on the radio", "UCartist")
        song("played last month", "UCartist")
        song("radio only", "UCstranger")
        exec("UPDATE song SET inLibrary = $t WHERE id = 'saved, never played'")
        exec("UPDATE song SET dateDownload = $t WHERE id = 'downloaded'")
        event("downloaded", t)
        repeat(2) { event("heard on the radio", t) }
        event("played last month", t - 30 * day)
        repeat(4) { event("radio only", t) }

        // None of the songs played is in the library, and they all count: four plays of one song
        // for stranger, three of two songs for artist, one of them downloaded. The saved song
        // nobody played and last month's play are not in the week.
        val rows = query(StatsSql.MOST_PLAYED_ARTISTS, t - 7 * day, 6) { rs ->
            listOf(rs.getString("id"), rs.getLong("songCount"), rs.getLong("downloadCount"))
        }
        assertEquals(listOf(listOf("UCstranger", 1L, 0L), listOf("UCartist", 2L, 1L)), rows)
    }

    @Test
    fun `the same number of plays goes to the artist listened to for longer`() {
        song("long", "UCpatient")
        song("short", "UChasty")
        event("long", t, playTime = 5 * minute)
        event("short", t, playTime = minute)
        assertEquals(listOf("UCpatient", "UChasty"), mostPlayedArtists(0))
    }

    /** A local artist's id, the shape ArtistEntity.generateArtistId() writes. */
    private fun localArtistId(seed: String) = "LA$seed"

    @Test
    fun `a local artist never fills a place, even ahead of every YouTube artist`() {
        song("local one", localArtistId("a"))
        song("local two", localArtistId("b"))
        song("youtube one", "UCyoutube")
        event("local one", t, playTime = 10 * minute)
        event("local two", t, playTime = 9 * minute)
        event("youtube one", t, playTime = minute)
        // The two local artists outplay the YouTube one by far, but only the YouTube artist has
        // a page to show, so it is the only one that belongs in the row.
        assertEquals(listOf("UCyoutube"), mostPlayedArtists(0))
    }

    @Test
    fun `filtering out local artists does not shrink the row below the limit`() {
        // Six local artists ahead of six YouTube ones: filtering after LIMIT 6 would leave the
        // row empty, although six played YouTube artists exist.
        repeat(6) { i ->
            val id = localArtistId(i.toString())
            song("local-$i", id)
            event("local-$i", t, playTime = (100 - i) * minute)
        }
        repeat(6) { i ->
            val id = "UC$i"
            song("yt-$i", id)
            event("yt-$i", t, playTime = (10 - i) * minute)
        }
        assertEquals(listOf("UC0", "UC1", "UC2", "UC3", "UC4", "UC5"), mostPlayedArtists(0))
    }

    @Test
    fun `a privately owned library artist counts as a YouTube artist too`() {
        song("owned", "FEmusic_library_privately_owned_artist_x")
        song("local", localArtistId("z"))
        event("owned", t, playTime = minute)
        event("local", t, playTime = 5 * minute)
        assertEquals(listOf("FEmusic_library_privately_owned_artist_x"), mostPlayedArtists(0))
    }
}
