/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.db.RecommendationSql
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.util.TimeZone
import kotlin.random.Random

/**
 * The engine against a real history, session by session. Gated on `ENGINE_REPLAY=/path/to/song.db`
 * (a version 21 database, with the listen log backfilled) so nothing private is ever needed for
 * the ordinary run.
 *
 * At each session start `t` the engine sees only what existed before `t`, builds a row at its
 * priors, and the shipped classic query is run with `:now = t`. Each is scored on the session's
 * picks: hit@20 is the share of picks whose version group was in the row, split into songs heard
 * before and songs new to the listener; engaged hit sums their engagement. Two baselines go with
 * them, most recent and personal top frequency. Version collisions must be zero.
 *
 * A pre-0.11 export holds only backfilled legacy listens, with no origin and no end reason, so a
 * pick is taken by proxy: a counted listen that begins a session or follows a gap longer than the
 * previous song's length. The result is stated to favour the classic query, since whichever row
 * was on screen while that history accumulated caused some of the plays it is scored against.
 */
class EngineReplayTest {
    private class Row(val songId: String, val startedAt: Long, val endedAt: Long, val playedMs: Long, val durationMs: Long, val endReason: Int, val origin: Int, val depth: Int, val sessionId: Long, val tz: Int)

    private fun Connection.rows(sql: String, vararg args: Any): List<Map<String, Any?>> = prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeQuery().use { rs ->
            val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
            buildList { while (rs.next()) add(cols.associateWith { rs.getObject(it) }) }
        }
    }

    @Test
    fun `replay a real history`() {
        val path = System.getenv("ENGINE_REPLAY"); assumeTrue("set ENGINE_REPLAY to run", !path.isNullOrBlank())
        val copy = File.createTempFile("replay", ".db").apply { deleteOnExit() }; File(path!!).copyTo(copy, overwrite = true)
        DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { db ->
            val tz = TimeZone.getDefault()
            val songs = db.rows("""SELECT s.id, s.title, s.liked, s.likedDate, s.inLibrary, s.isLocal,
                (SELECT m.artistId FROM song_artist_map m WHERE m.songId = s.id ORDER BY m.position LIMIT 1) AS artistId,
                (SELECT a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId WHERE m.songId = s.id ORDER BY m.position LIMIT 1) AS artistName FROM song s""")
                .associate { r ->
                    val id = r["id"] as String
                    val likedDate = (r["likedDate"] as Number?)?.toLong()
                    id to SongRow(id, r["title"] as String, r["artistId"] as String?, r["artistName"] as String?, (r["liked"] as Number).toInt() != 0,
                        likedDate?.let { storedLocalToInstant(it, tz.toZoneId()) }, r["inLibrary"] != null, (r["isLocal"] as Number).toInt() != 0)
                }
            val edges = db.rows("SELECT songId, relatedSongId FROM related_song_map").map { Edge(it["songId"] as String, it["relatedSongId"] as String) }
            val links = db.rows("SELECT songId, versionId FROM song_version_map").map { VersionLink(it["songId"] as String, it["versionId"] as String) }
            val all = db.rows("SELECT songId, startedAt, endedAt, playedMs, durationMs, endReason, origin, autoplayDepth, sessionId, tzOffsetMin FROM listen WHERE endReason != 6 ORDER BY startedAt")
                .map { Row(it["songId"] as String, (it["startedAt"] as Number).toLong(), (it["endedAt"] as Number).toLong(), (it["playedMs"] as Number).toLong(), (it["durationMs"] as Number).toLong(),
                    (it["endReason"] as Number).toInt(), (it["origin"] as Number).toInt(), (it["autoplayDepth"] as Number).toInt(), (it["sessionId"] as Number).toLong(), (it["tzOffsetMin"] as Number).toInt()) }
            fun toListen(r: Row) = ListenRow(r.songId, r.startedAt, r.endedAt, r.playedMs, r.durationMs, r.endReason, r.origin, r.depth, r.sessionId, r.tz)
            val groups = VersionGroups(songs.values, links)
            val sessions = all.groupBy { it.sessionId }.values.sortedBy { it.first().startedAt }
            // Picks by proxy: the first listen of a session, or one after a gap longer than the previous song.
            fun picks(session: List<Row>): List<Row> = session.filterIndexed { i, r ->
                if (i == 0) true else { val prev = session[i - 1]; r.startedAt - prev.endedAt > maxOf(prev.durationMs, 60_000L) }
            }
            data class Score(var picks: Int = 0, var hits: Int = 0, var repeatHits: Int = 0, var newHits: Int = 0, var engaged: Double = 0.0, var artists: Int = 0, var collisions: Int = 0, var sessions: Int = 0)
            val engine = Score(); val familiar = Score(); val fresh1 = Score(); val fresh1cap = Score(); val classic = Score(); val recent = Score(); val frequent = Score()
            // Warm start: fit the weights on the first six tenths of the sessions, then judge the last four tenths with them against the priors.
            val warmed = Score(); val priorsOnHeldOut = Score()
            val split = (sessions.size * 0.6).toInt()
            val trainListens = sessions.take(split).flatten().map(::toListen)
            val trainInput = EngineInput(sessions[split].first().startedAt, songs, trainListens, edges, links, tzOffsetMin = sessions.first().first().tz)
            val warm = WarmStart.run(trainInput, maxSessions = 60)
            val warmWeights = Weights(warm.weights)
            val warmPos = WarmStart.run(trainInput, maxSessions = 60, ignoredWeight = 0.0)
            val warmPosWeights = Weights(warmPos.weights)
            val warmedPositive = Score()
            println("warm start, plays only: moved: " + warmPos.weights.filter { (n, v) -> Math.abs(v - Features.priors[n]!!.value) > 0.01 }.entries.joinToString { "%s %.2f".format(it.key, it.value) })
            println("warm start: ${warm.sessions} sessions, ${warm.examples} examples; moved: " + warm.weights.filter { (n, v) -> Math.abs(v - Features.priors[n]!!.value) > 0.01 }.entries.joinToString { "%s %.2f".format(it.key, it.value) })
            // Variants, for information: half the rest of the row to Again; a one-hour freshness rule; that plus Again free of the artist cap.
            val familiarParams = EngineParams.DEFAULT.withFamiliarity(0.5)
            val fresh1Params = EngineParams.DEFAULT.withFamiliarity(0.5).copy(engineFreshHours = 1)
            val fresh1capParams = fresh1Params.copy(againIgnoresArtistCap = true)
            var considered = 0
            val random = Random(1)
            val skipFirst = 5   // nothing to learn from before a few sessions exist
            for ((i, session) in sessions.withIndex()) {
                if (i < skipFirst) continue
                val t = session.first().startedAt
                val before = all.filter { it.endedAt <= t }
                if (before.isEmpty()) continue
                val sessionPicks = picks(session).filter { Signals.engagement(toListen(it), null) >= 0.5 }
                if (sessionPicks.isEmpty()) continue
                considered++
                val heard = before.map { it.songId }.toSet()
                val input = EngineInput(t, songs, before.map(::toListen), edges, links, bucket = dayPartBucket(t, session.first().tz), tzOffsetMin = session.first().tz)
                val row = EngineRow.build(input, random = random)
                val engineGroups = row.cards.map { groups.groupOf(it.songId) }
                val familiarRow = EngineRow.build(input, p = familiarParams, random = Random(i.toLong()))
                val fresh1Row = EngineRow.build(input, p = fresh1Params, random = Random(i.toLong()))
                val fresh1capRow = EngineRow.build(input, p = fresh1capParams, random = Random(i.toLong()))
                val heldOut = i >= split
                val warmRow = if (heldOut) EngineRow.build(input, weights = warmWeights, random = Random(i.toLong())) else null
                val priorRow = if (heldOut) EngineRow.build(input, random = Random(i.toLong())) else null
                val warmPosRow = if (heldOut) EngineRow.build(input, weights = warmPosWeights, random = Random(i.toLong())) else null
                val classicIds = db.rows(RecommendationSql.QUICK_PICKS.replace(":now", t.toString())).map { it["id"] as String }.take(20)
                val recentIds = before.sortedByDescending { it.endedAt }.map { it.songId }.distinct().take(20)
                val frequentIds = before.groupingBy { it.songId }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(20)
                fun score(sc: Score, ids: List<String>, groupIds: List<String>) {
                    sc.sessions++
                    sc.collisions += groupIds.size - groupIds.toSet().size
                    sc.artists += ids.mapNotNull { songs[it]?.artistId }.toSet().size
                    for (pk in sessionPicks) {
                        sc.picks++
                        if (groups.groupOf(pk.songId) in groupIds) {
                            sc.hits++; sc.engaged += Signals.engagement(toListen(pk), null)
                            if (pk.songId in heard) sc.repeatHits++ else sc.newHits++
                        }
                    }
                }
                score(engine, row.cards.map { it.songId }, engineGroups)
                score(familiar, familiarRow.cards.map { it.songId }, familiarRow.cards.map { groups.groupOf(it.songId) })
                score(fresh1, fresh1Row.cards.map { it.songId }, fresh1Row.cards.map { groups.groupOf(it.songId) })
                score(fresh1cap, fresh1capRow.cards.map { it.songId }, fresh1capRow.cards.map { groups.groupOf(it.songId) })
                if (warmRow != null && priorRow != null) {
                    score(warmed, warmRow.cards.map { it.songId }, warmRow.cards.map { groups.groupOf(it.songId) })
                    score(priorsOnHeldOut, priorRow.cards.map { it.songId }, priorRow.cards.map { groups.groupOf(it.songId) })
                    warmPosRow?.let { score(warmedPositive, it.cards.map { c -> c.songId }, it.cards.map { c -> groups.groupOf(c.songId) }) }
                }
                score(classic, classicIds, classicIds.map { groups.groupOf(it) })
                score(recent, recentIds, recentIds.map { groups.groupOf(it) })
                score(frequent, frequentIds, frequentIds.map { groups.groupOf(it) })
            }
            fun line(name: String, s: Score) = println("  %-10s hit@20 %.3f (repeat %d, new %d of %d picks), engaged %.1f, artists/row %.1f, collisions %d".format(
                name, s.hits.toDouble() / maxOf(1, s.picks), s.repeatHits, s.newHits, s.picks, s.engaged, s.artists.toDouble() / maxOf(1, s.sessions), s.collisions))
            println("engine replay: ${sessions.size} sessions, $considered scored, ${all.size} listens, ${songs.size} songs, ${edges.size} edges (legacy column: proxy picks, favours the classic query)")
            line("engine", engine); line("familiar", familiar); line("fresh1h", fresh1); line("fresh1h+cap", fresh1cap); line("classic", classic); line("recent", recent); line("frequent", frequent)
            println("  held-out sessions (last 40%): priors vs warm start"); line("priors", priorsOnHeldOut); line("warmed", warmed); line("warmed+", warmedPositive)
            assertEquals(0, engine.collisions)
        }
    }
}
