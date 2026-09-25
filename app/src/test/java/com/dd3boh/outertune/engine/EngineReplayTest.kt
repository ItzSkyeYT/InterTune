/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.db.RecommendationSql
import com.dd3boh.outertune.db.RelatedSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
            // Through the app's own query: ENGINE_REPLAY_SOURCE=1 for Last.fm (with YouTube's
            // where it has nothing), 0 or unset for YouTube. "all" reads every row whatever its
            // source, for the 23 Sep trial's databases, which stored their edge sets that way.
            val edgeSql = when (val src = System.getenv("ENGINE_REPLAY_SOURCE")) {
                "all" -> "SELECT songId, relatedSongId FROM related_song_map"
                else -> com.dd3boh.outertune.db.RelatedSql.ENGINE_EDGES.replace(":source", "${src?.toIntOrNull() ?: 0}")
            }
            val edges = db.rows(edgeSql).map { Edge(it["songId"] as String, it["relatedSongId"] as String) }
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

            // ENGINE_REPLAY_MIX, with QUICK: the similar-songs sources as arms over the same sessions
            // and the same dice, instead of the one engine row. Unset, nothing below changes.
            val mixArms = System.getenv("ENGINE_REPLAY_MIX")
            if (System.getenv("ENGINE_REPLAY_QUICK") == "1" && !mixArms.isNullOrBlank()) {
                mixReplay(db, System.getenv("ENGINE_REPLAY_LABEL") ?: File(path).name, mixArms, songs, links, groups, all, sessions, ::picks, ::toListen)
                return@use
            }

            // ENGINE_REPLAY_QUICK=1: the engine row alone, plus the three cheap baselines, for comparing
            // two databases that differ only in their related edges. The full run builds nine rows a
            // session and fits warm start twice, which is eleven minutes; this is one row a session.
            // The row is seeded per session rather than from one shared generator, so the same session
            // draws the same way under both databases and a difference is the edges, not the dice.
            // Hits are also split by lane, because what a second edge source could change is whether
            // Related finds more, and a whole-row number would bury that under the other four lanes.
            if (System.getenv("ENGINE_REPLAY_QUICK") == "1") {
                val label = System.getenv("ENGINE_REPLAY_LABEL") ?: File(path).name
                val seeded = Score(); val qClassic = Score(); val qRecent = Score(); val qFrequent = Score()
                val byLane = HashMap<Lane, IntArray>()   // cards, hits, new hits
                var scored = 0
                for ((i, session) in sessions.withIndex()) {
                    if (i < 5) continue
                    val t = session.first().startedAt
                    val before = all.filter { it.endedAt <= t }
                    if (before.isEmpty()) continue
                    val sessionPicks = picks(session).filter { Signals.engagement(toListen(it), null) >= 0.5 }
                    if (sessionPicks.isEmpty()) continue
                    scored++
                    val heard = before.map { it.songId }.toSet()
                    val input = EngineInput(t, songs, before.map(::toListen), edges, links, bucket = dayPartBucket(t, session.first().tz), tzOffsetMin = session.first().tz)
                    fun score(sc: Score, ids: List<String>) {
                        val g = ids.map { groups.groupOf(it) }
                        sc.sessions++; sc.collisions += g.size - g.toSet().size
                        sc.artists += ids.mapNotNull { songs[it]?.artistId }.toSet().size
                        for (pk in sessionPicks) {
                            sc.picks++
                            if (groups.groupOf(pk.songId) in g) {
                                sc.hits++; sc.engaged += Signals.engagement(toListen(pk), null)
                                if (pk.songId in heard) sc.repeatHits++ else sc.newHits++
                            }
                        }
                    }
                    // ENGINE_REPLAY_SEEDS repeats each session with different draws and sums them. The
                    // picks are fixed, so this cannot add evidence about the listener, but it does take
                    // the luck of one sampled row out of a comparison between two sets of edges.
                    val pickGroups = sessionPicks.associateBy { groups.groupOf(it.songId) }
                    for (rep in 0 until (System.getenv("ENGINE_REPLAY_SEEDS")?.toIntOrNull() ?: 1)) {
                        val row = EngineRow.build(input, random = Random(i.toLong() + rep * 100_003L))
                        score(seeded, row.cards.map { it.songId })
                        for (c in row.cards) {
                            val tally = byLane.getOrPut(c.lane) { IntArray(3) }
                            tally[0]++
                            val pk = pickGroups[groups.groupOf(c.songId)] ?: continue
                            tally[1]++
                            if (pk.songId !in heard) tally[2]++
                        }
                    }
                    val classicIds = db.rows(RecommendationSql.QUICK_PICKS.replace(":now", t.toString())).map { it["id"] as String }.take(20)
                    score(qClassic, classicIds)
                    score(qRecent, before.sortedByDescending { it.endedAt }.map { it.songId }.distinct().take(20))
                    score(qFrequent, before.groupingBy { it.songId }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(20))
                }
                fun line(name: String, s: Score) = println("  %-10s hit@20 %.3f (repeat %d, new %d of %d picks), engaged %.1f, artists/row %.1f, collisions %d".format(
                    name, s.hits.toDouble() / maxOf(1, s.picks), s.repeatHits, s.newHits, s.picks, s.engaged, s.artists.toDouble() / maxOf(1, s.sessions), s.collisions))
                println("quick replay [$label]: ${sessions.size} sessions, $scored scored, ${edges.size} edges")
                line("engine", seeded); line("classic", qClassic); line("recent", qRecent); line("frequent", qFrequent)
                for (lane in Lane.values()) byLane[lane]?.let { (cards, hits, fresh) ->
                    println("  lane %-10s %5d cards, %3d hits (%d new), %.2f%% of its cards hit".format(lane.name.lowercase(), cards, hits, fresh, 100.0 * hits / maxOf(1, cards)))
                }
                assertEquals(0, seeded.collisions)
                return@use
            }

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
            val dial32 = Score(); val dial75 = Score()
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
                // The adventurousness slider, at the value this library is set to against a much
                // higher one. Same seed per session so the only difference is the dial.
                val dial32Row = EngineRow.build(input, dial = 0.32, random = Random(i.toLong()))
                val dial75Row = EngineRow.build(input, dial = 0.75, random = Random(i.toLong()))
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
                score(dial32, dial32Row.cards.map { it.songId }, dial32Row.cards.map { groups.groupOf(it.songId) })
                score(dial75, dial75Row.cards.map { it.songId }, dial75Row.cards.map { groups.groupOf(it.songId) })
                score(classic, classicIds, classicIds.map { groups.groupOf(it) })
                score(recent, recentIds, recentIds.map { groups.groupOf(it) })
                score(frequent, frequentIds, frequentIds.map { groups.groupOf(it) })
            }
            fun line(name: String, s: Score) = println("  %-10s hit@20 %.3f (repeat %d, new %d of %d picks), engaged %.1f, artists/row %.1f, collisions %d".format(
                name, s.hits.toDouble() / maxOf(1, s.picks), s.repeatHits, s.newHits, s.picks, s.engaged, s.artists.toDouble() / maxOf(1, s.sessions), s.collisions))
            println("engine replay: ${sessions.size} sessions, $considered scored, ${all.size} listens, ${songs.size} songs, ${edges.size} edges (legacy column: proxy picks, favours the classic query)")
            line("engine", engine); line("familiar", familiar); line("fresh1h", fresh1); line("fresh1h+cap", fresh1cap); line("classic", classic); line("recent", recent); line("frequent", frequent)
            println("  adventurousness: 32 (this library) vs 75"); line("dial 32", dial32); line("dial 75", dial75)
            println("  held-out sessions (last 40%): priors vs warm start"); line("priors", priorsOnHeldOut); line("warmed", warmed); line("warmed+", warmedPositive)
            assertEquals(0, engine.collisions)
        }
    }

    /**
     * The similar-songs mix against each source alone. Arms, from ENGINE_REPLAY_MIX ("all" or a
     * comma list): youtube (the app's ENGINE_EDGES with source 0), lastfm (source 1, with YouTube's
     * list for a seed Last.fm has none for), half and fixed:<s> (both lists merged, a fixed split),
     * and adaptive (both lists, the split learned from how this replay's own rows fared). Every
     * session and draw uses the same dice in every arm, so a difference is the arm, not the luck.
     *
     * Every arm goes through MixRow, the test-only copy of the engine with the ledger, and the two
     * single-source arms are checked card for card against EngineRow.build on each session's first
     * draw, so the copy cannot quietly drift from the engine it stands in for.
     *
     * The adaptive arm grades its own cards the way the app would grade impressions, but only the
     * cards the app counts as evidence: a card whose version group the session went on to pick is
     * PLAYED at that pick's engagement, any other IGNORED. Picks are a proxy for taps, and the
     * history was shaped by YouTube's rows, so this shows that the mechanism works and what it
     * costs or gains here, not where his phone would settle.
     */
    private fun mixReplay(
        db: Connection, label: String, arms: String, songs: Map<String, SongRow>, links: List<VersionLink>, groups: VersionGroups,
        all: List<Row>, sessions: List<List<Row>>, picks: (List<Row>) -> List<Row>, toListen: (Row) -> ListenRow,
    ) {
        val names = if (arms == "all") listOf("youtube", "lastfm", "half", "adaptive") else arms.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        // The app's query with its WHERE untouched, reading each edge's source as a bit.
        val single = RelatedSql.ENGINE_EDGES.replace("SELECT songId, relatedSongId FROM", "SELECT songId, relatedSongId, CASE r.source WHEN 1 THEN 2 ELSE 1 END AS sources FROM")
        check(single != RelatedSql.ENGINE_EDGES) { "ENGINE_EDGES changed shape, so the replay no longer reads what the app reads" }
        // Both lists merged: a pair both sources list is one edge carrying both bits, so it counts
        // once as a referrer, as it would in the app.
        val union = """SELECT songId, relatedSongId,
            MAX(CASE source WHEN 0 THEN 1 ELSE 0 END) + MAX(CASE source WHEN 1 THEN 2 ELSE 0 END) AS sources
            FROM related_song_map WHERE source IN (0, 1) GROUP BY songId, relatedSongId"""
        val loaded = HashMap<String, Pair<List<Edge>, Map<Edge, Int>>>()
        fun edges(kind: String) = loaded.getOrPut(kind) {
            val sql = when (kind) { "youtube" -> single.replace(":source", "0"); "lastfm" -> single.replace(":source", "1"); else -> union }
            val rows = db.rows(sql).map { Edge(it["songId"] as String, it["relatedSongId"] as String) to (it["sources"] as Number).toInt() }
            rows.map { it.first } to rows.toMap()
        }
        class Arm(val name: String, val edges: List<Edge>, val bits: Map<Edge, Int>, val fixed: Double?, val adaptive: Boolean) {
            var picks = 0; var hits = 0; var repeat = 0; var fresh = 0; var engaged = 0.0; var collisions = 0
            val lanes = HashMap<Lane, IntArray>()   // cards, hits, new hits
            val kinds = Array(4) { IntArray(2) }    // youtube only, lastfm only, both, none: cards, hits
            var edgeCards = 0; var lastFmOnly = 0; var ledgerPlaces = 0; var ledgerLastFm = 0
            val perSession = ArrayList<Int>()
        }
        val armList = names.map { n ->
            when {
                n == "youtube" || n == "lastfm" -> edges(n).let { Arm(n, it.first, it.second, null, false) }
                n == "half" -> edges("union").let { Arm(n, it.first, it.second, 0.5, false) }
                n.startsWith("fixed:") -> edges("union").let { Arm(n, it.first, it.second, n.removePrefix("fixed:").toDouble(), false) }
                n == "adaptive" -> edges("union").let { Arm(n, it.first, it.second, null, true) }
                else -> error("unknown arm $n; use youtube, lastfm, half, fixed:<s> or adaptive")
            }
        }
        val reps = System.getenv("ENGINE_REPLAY_SEEDS")?.toIntOrNull() ?: 1
        val evidence = Array(reps) { ArrayList<MixEvidence>() }
        val shares = ArrayList<DoubleArray>()   // per scored session, the adaptive share of each draw
        val finalStates = arrayOfNulls<MixShare.State>(reps)
        var scored = 0; var checked = 0
        val t0 = System.nanoTime()
        for ((i, session) in sessions.withIndex()) {
            if (i < 5) continue
            val t = session.first().startedAt
            val before = all.filter { it.endedAt <= t }
            if (before.isEmpty()) continue
            val sessionPicks = picks(session).filter { Signals.engagement(toListen(it), null) >= 0.5 }
            if (sessionPicks.isEmpty()) continue
            scored++
            val heard = before.map { it.songId }.toSet()
            val tz = session.first().tz
            val base = EngineInput(t, songs, before.map(toListen), emptyList(), links, bucket = dayPartBucket(t, tz), tzOffsetMin = tz)
            val pickGroups = sessionPicks.associateBy { groups.groupOf(it.songId) }
            val sessionShares = DoubleArray(reps)
            for (arm in armList) {
                val input = base.copy(edges = arm.edges)
                var sessionHits = 0
                for (rep in 0 until reps) {
                    val seed = i.toLong() + rep * 100_003L
                    val share = if (arm.adaptive) {
                        val state = MixShare.share(evidence[rep].filter { it.visibleAt >= t - MixShare.WINDOW_MS }, tz)
                        finalStates[rep] = state; sessionShares[rep] = state.share
                        state.share
                    } else arm.fixed
                    val built = MixRow.build(input, arm.bits, random = Random(seed), lastFmShare = share)
                    if (rep == 0 && share == null) {
                        val plain = EngineRow.build(input, random = Random(seed))
                        assertEquals("${arm.name}, session $i: the test copy drew differently from EngineRow.build",
                            plain.cards.map { it.songId to it.lane }, built.row.cards.map { it.songId to it.lane })
                        checked++
                    }
                    val g = built.row.cards.map { groups.groupOf(it.songId) }
                    arm.collisions += g.size - g.toSet().size
                    for (pk in sessionPicks) {
                        arm.picks++
                        if (groups.groupOf(pk.songId) in g) {
                            arm.hits++; sessionHits++; arm.engaged += Signals.engagement(toListen(pk), null)
                            if (pk.songId in heard) arm.repeat++ else arm.fresh++
                        }
                    }
                    for ((k, c) in built.row.cards.withIndex()) {
                        val bits = built.bits[k]
                        val pk = pickGroups[groups.groupOf(c.songId)]
                        val lane = arm.lanes.getOrPut(c.lane) { IntArray(3) }
                        lane[0]++
                        if (pk != null) { lane[1]++; if (pk.songId !in heard) lane[2]++ }
                        val kind = when (bits and MixBits.SOURCES) { MixBits.YOUTUBE -> 0; MixBits.LASTFM -> 1; MixBits.SOURCES -> 2; else -> 3 }
                        arm.kinds[kind][0]++; if (pk != null) arm.kinds[kind][1]++
                        val edgePlace = (c.lane == Lane.RELATED || c.lane == Lane.EXPLORE) && bits and MixBits.SOURCES != 0
                        if (edgePlace) { arm.edgeCards++; if (bits and MixBits.SOURCES == MixBits.LASTFM) arm.lastFmOnly++ }
                        if (built.credited[k] != 0) { arm.ledgerPlaces++; if (built.credited[k] == MixBits.LASTFM) arm.ledgerLastFm++ }
                        if (arm.adaptive && edgePlace && MixBits.isEvidence(bits)) {
                            val y = pk?.let { Signals.engagement(toListen(it), null) }
                            evidence[rep] += MixEvidence(c.songId, 1, c.lane.ordinal + 1, bits, t, if (y != null) Outcome.PLAYED else Outcome.IGNORED, (y ?: 0.0).toFloat())
                        }
                    }
                }
                arm.perSession += sessionHits
            }
            if (armList.any { it.adaptive }) shares += sessionShares
        }
        val seconds = (System.nanoTime() - t0) / 1e9
        println("mix replay [$label]: ${sessions.size} sessions, $scored scored, $reps draws each, ${seconds.toInt()} s; $checked single-source rows matched EngineRow.build card for card")
        for (arm in armList) {
            println("  %-10s %6d edges, hit@20 %.4f (repeat %d, new %d of %d picks), engaged %.1f, collisions %d".format(
                arm.name, arm.edges.size, arm.hits.toDouble() / maxOf(1, arm.picks), arm.repeat, arm.fresh, arm.picks, arm.engaged, arm.collisions))
            println("    lanes: " + Lane.entries.mapNotNull { l -> arm.lanes[l]?.let { (c, h, f) -> "%s %d/%d (%d new)".format(l.name.lowercase(), h, c, f) } }.joinToString(", "))
            println("    cards by source: " + listOf("youtube only", "lastfm only", "both", "none").withIndex().joinToString(", ") { (k, n) -> "%s %d/%d".format(n, arm.kinds[k][1], arm.kinds[k][0]) })
            val ledger = if (arm.ledgerPlaces > 0) ", ledger gave Last.fm %.1f%% of %d split places".format(100.0 * arm.ledgerLastFm / arm.ledgerPlaces, arm.ledgerPlaces) else ""
            println("    Last.fm-only cards %.1f%% of %d edge cards".format(100.0 * arm.lastFmOnly / maxOf(1, arm.edgeCards), arm.edgeCards) + ledger)
        }
        if (shares.isNotEmpty()) {
            val means = shares.map { it.average() }
            println("  adaptive share, mean over draws: " + listOf(0.25, 0.5, 0.75, 1.0).joinToString(", ") { q ->
                val k = maxOf(0, kotlin.math.ceil(q * means.size).toInt() - 1); "%d%% of sessions %.3f".format((q * 100).toInt(), means[k])
            } + "; range over all sessions and draws %.3f to %.3f".format(shares.minOf { it.min() }, shares.maxOf { it.max() }))
            for ((rep, st) in finalStates.withIndex()) st?.let {
                println("    draw %d final: share %.3f, Last.fm %.1f heard per 100 of %.1f cards, YouTube %.1f per 100 of %.1f, %d days".format(
                    rep, it.share, it.lastFm.per100, it.lastFm.cards, it.youTube.per100, it.youTube.cards, it.days))
            }
            for (s in shares) for (v in s) assertTrue("adaptive share $v outside [0.2, 0.8]", v in 0.2..0.8)
        }
        armList.firstOrNull { it.adaptive }?.let { a ->
            for (b in armList) if (b !== a && !b.adaptive) {
                val d = a.perSession.indices.map { (a.perSession[it] - b.perSession[it]).toDouble() }
                val mean = d.average(); val sd = kotlin.math.sqrt(d.sumOf { (it - mean) * (it - mean) } / maxOf(1, d.size - 1))
                println("  adaptive - %-8s %+.3f +- %.3f hits per session (summed over %d draws, %d sessions)".format(b.name, mean, 1.96 * sd / kotlin.math.sqrt(d.size.toDouble()), reps, d.size))
            }
        }
        for (arm in armList) assertEquals("${arm.name} collisions", 0, arm.collisions)
    }
}
