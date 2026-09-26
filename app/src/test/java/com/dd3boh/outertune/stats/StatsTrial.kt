/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.stats

import com.dd3boh.outertune.constants.StatPeriod
import com.dd3boh.outertune.db.StatsSql
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * The Stats page's reads and findings over a copy of a real library, every period, as of the
 * copy's last listen. Gated on `STATS_REPLAY=/path/to/song.db`, so the ordinary run never depends
 * on private data, and the copy is copied again before it is opened. Prints what each period would
 * say, by song and artist id only, and how long each read took: the median of five.
 */
class StatsTrial {
    @Test
    fun `every period of a real log`() {
        val source = System.getenv("STATS_REPLAY"); assumeTrue("set STATS_REPLAY to run", !source.isNullOrBlank())
        val copy = File.createTempFile("stats-trial", ".db").apply { deleteOnExit() }
        File(source!!).copyTo(copy, overwrite = true)
        DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { db ->
            val (now, offset) = db.createStatement().use { st ->
                st.executeQuery("SELECT endedAt, tzOffsetMin FROM listen WHERE endReason != 6 ORDER BY endedAt DESC LIMIT 1")
                    .use { it.next(); it.getLong(1) to it.getInt(2) }
            }
            val zoned = ZonedDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneOffset.ofTotalSeconds(offset * 60))
            for (period in StatPeriod.entries) {
                val start = period.startMillis(zoned)
                val listens = timed("listens") { read(db, StatsSql.LISTENS, start) { it.listen() } }
                val before = if (start > 0) timed("before") { read(db, StatsSql.SONGS_BEFORE, start) { it.before() } } else emptyList()
                val artists = timed("artists") { read(db, StatsSql.SONG_ARTISTS) { it.getString("songId") to it.getString("artistId") }.toMap() }
                val previous = if (start > 0) timed("totals") { read(db, StatsSql.TOTALS, start - (now - start), start) { it.totals() }.single() } else null
                val bounds = timed("bounds") { read(db, StatsSql.BOUNDS) { StatsBounds(it.longOrNull("firstAt"), it.longOrNull("firstLiveAt")) }.single() }
                val t0 = System.nanoTime()
                val stats = ListeningInsights.compute(StatsInput(now, offset, start, listens, before, artists, previous, bounds))
                val computeMs = (System.nanoTime() - t0) / 1e6
                println("== $period: ${listens.size} listens, ${before.size} songs before; compute %.1f ms".format(computeMs))
                println("   ${stats.summary}")
                println("   peak hour ${stats.hours?.peakHour}")
                stats.insights.forEach { println("   $it") }
            }
        }
    }

    private fun <T> timed(label: String, read: () -> T): T {
        var result: T? = null
        val times = (0 until 5).map {
            val t0 = System.nanoTime()
            result = read()
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        println("   $label: %.1f ms".format(times[2]))
        return result!!
    }

    private fun <T> read(db: Connection, sql: String, vararg args: Long, row: (ResultSet) -> T): List<T> = db.prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, v -> ps.setLong(i + 1, v) }
        ps.executeQuery().use { rs -> buildList { while (rs.next()) add(row(rs)) } }
    }

    private fun ResultSet.longOrNull(column: String): Long? = getLong(column).takeUnless { wasNull() }

    private fun ResultSet.listen() = StatsListen(
        id = getLong("id"), songId = getString("songId"),
        startedAt = getLong("startedAt"), endedAt = getLong("endedAt"), tzOffsetMin = getInt("tzOffsetMin"),
        playedMs = getLong("playedMs"), endReason = getInt("endReason"), origin = getInt("origin"),
        sessionId = getLong("sessionId"), counted = getBoolean("counted"), continuesListenId = longOrNull("continuesListenId"),
    )

    private fun ResultSet.before() = StatsSongBefore(
        songId = getString("songId"), firstAt = getLong("firstAt"),
        lastPlayAt = longOrNull("lastPlayAt"), playsBefore = getInt("playsBefore"),
    )

    private fun ResultSet.totals() = StatsTotals(getInt("listens"), getLong("playedMs"), getLong("countedMs"), getInt("legacyListens"))
}
