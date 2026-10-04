/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.ZoneId
import kotlin.random.Random

/**
 * Quick picks leans toward, against Auto, over a real history. Gated on `ENGINE_REPLAY=<a copy of
 * song.db>` and `ENGINE_REPLAY_LEAN=A|B|B2|C`, so nothing private is ever needed for the ordinary
 * run; the database is copied again before it is opened.
 *
 * The points: A every session start, B the 4th listen of every session of eight or more, B2 the
 * 2nd, C the 8th. At each point the engine sees only listens that ended before it, every arm is
 * built with the same dice, and what is counted is what would be on screen: the row through the
 * tidy pass (the 24-hour just-played list, two an artist, previews, the Again exemption) and,
 * under a lean, LeanRow.compose, exactly as Home does it. Whether a card is what its label says is
 * worked out apart from the engine's lanes.
 *
 * Optional: `ENGINE_REPLAY_LEAN_LAST=80` keeps the last 80 points, `ENGINE_REPLAY_LEAN_ARMS` picks
 * the arms (a lean, or a lean and a chip or NEWONLY joined by +, such as NEW+DISCOVER), and
 * `ENGINE_REPLAY_LEAN_FLAGS` adds `learn` (a learning simulation from the copy's own weights, each
 * lean three ways: unweighted, weighted as shipped, and weighted by the lead cards placed), `pull`
 * (a pull to refresh after each build) and `ff` (the Forgotten favourites row under it).
 *
 * The checks that hold on any library are asserted: no version collisions, and every lead card
 * true to its label. The figures are printed.
 */
class LeanReplayTrial {
    private class Row(val id: Long, val songId: String, val startedAt: Long, val endedAt: Long, val playedMs: Long, val durationMs: Long, val endReason: Int, val origin: Int, val depth: Int, val sessionId: Long, val tz: Int, val learn: Boolean, val chip: Int)

    private class Arm(val name: String) {
        val lean: Lean = Lean.valueOf(name.substringBefore('+'))
        val chip: Int = when (name.substringAfter('+', "")) {
            "DISCOVER" -> ContextChip.DISCOVER; "FAVOURITES" -> ContextChip.FAVOURITES; "CHILL" -> ContextChip.CHILL
            "FOCUS" -> ContextChip.FOCUS; "PARTY" -> ContextChip.PARTY; else -> ContextChip.AUTO
        }
        val newOnly: Boolean = name.endsWith("+NEWONLY")
    }

    private class Tally {
        var builds = 0; var screen = 0; var leadScreen = 0; var leadFirst = 0; var leadQuota = 0; var shortOnScreen = 0; var noneBuilds = 0
        var leadPlaced = 0; var leadPool = 0L; var targets = 0; var hits = 0; var collisions = 0; var nanos = 0L
        val truthRow = IntArray(4); val truthFirst = IntArray(4); val laneCards = IntArray(5)
        var newArtistLead = 0; var newArtistFirst = 0; var returningLead = 0; var returningFirst = 0; var knownLead = 0; var startedRecentlyLead = 0
        var ownCaption = 0; var neverStartedLead = 0; var dropped = 0; var capSum = 0; var engineShort = 0; var tryBothLead = 0; var tryBothFirst = 0
        var headingShort = 0; var headingNothing = 0
        var pullLeadAll = 0; var pullLeadExempt = 0; var pullBuilds = 0
        var ffRow = 0; var ffOverlap = 0; var ffPoints = 0
    }

    private fun Connection.rows(sql: String): List<Map<String, Any?>> = prepareStatement(sql).use { ps ->
        ps.executeQuery().use { rs ->
            val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
            buildList { while (rs.next()) add(cols.associateWith { rs.getObject(it) }) }
        }
    }

    @Test
    fun `each lean against Auto on a real history`() {
        val path = System.getenv("ENGINE_REPLAY"); val mode = System.getenv("ENGINE_REPLAY_LEAN")
        assumeTrue("set ENGINE_REPLAY and ENGINE_REPLAY_LEAN to run", !path.isNullOrBlank() && !mode.isNullOrBlank())
        val lastN = System.getenv("ENGINE_REPLAY_LEAN_LAST")?.toIntOrNull() ?: Int.MAX_VALUE
        val arms = (System.getenv("ENGINE_REPLAY_LEAN_ARMS") ?: "AUTO,NEW,ARTIST,FORGOTTEN,SIMILAR").split(',').map { Arm(it.trim()) }
        val flags = System.getenv("ENGINE_REPLAY_LEAN_FLAGS").orEmpty().split(',').map { it.trim() }.toSet()
        // The phone's zone, for the like dates as Converters stores them.
        val zone = ZoneId.of(System.getenv("ENGINE_REPLAY_ZONE") ?: "Europe/Paris")
        val copy = File.createTempFile("lean", ".db").apply { deleteOnExit() }; File(path!!).copyTo(copy, overwrite = true)
        DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { db -> replay(db, mode!!, lastN, arms, flags, zone) }
    }

    private fun replay(db: Connection, mode: String, lastN: Int, arms: List<Arm>, flags: Set<String>, zone: ZoneId) {
        // The songs as EngineReplayTest reads them, in the table's own order; YouTube's edges and
        // the version links through the app's queries. The order matters only where two songs
        // score exactly alike, but it is what makes two runs over one copy agree to the card.
        val songs = db.rows("""SELECT s.id, s.title, s.liked, s.likedDate, s.inLibrary, s.isLocal,
            (SELECT m.artistId FROM song_artist_map m WHERE m.songId = s.id ORDER BY m.position LIMIT 1) AS artistId,
            (SELECT a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId WHERE m.songId = s.id ORDER BY m.position LIMIT 1) AS artistName FROM song s""")
            .associate { r ->
                val id = r["id"] as String
                val liked = (r["liked"] as Number).toInt() != 0
                id to SongRow(id, r["title"] as String, r["artistId"] as String?, r["artistName"] as String?, liked,
                    (r["likedDate"] as Number?)?.toLong()?.takeIf { liked }?.let { storedLocalToInstant(it, zone) }, r["inLibrary"] != null, (r["isLocal"] as Number).toInt() != 0)
            }
        val edges = JdbcEngineInput.edges(db)
        val links = JdbcEngineInput.links(db)
        // Open rows have no end yet, and failed plays are left out as the app's loader leaves them out (EngineListens).
        val all = db.rows("SELECT id, songId, startedAt, endedAt, playedMs, durationMs, endReason, origin, autoplayDepth, sessionId, tzOffsetMin, learn, contextChip FROM listen WHERE endReason NOT IN (5, 6) AND startedAt > 0 ORDER BY startedAt")
            .map { Row((it["id"] as Number).toLong(), it["songId"] as String, (it["startedAt"] as Number).toLong(), (it["endedAt"] as Number).toLong(), (it["playedMs"] as Number).toLong(), (it["durationMs"] as Number).toLong(),
                (it["endReason"] as Number).toInt(), (it["origin"] as Number).toInt(), (it["autoplayDepth"] as Number).toInt(), (it["sessionId"] as Number).toLong(), (it["tzOffsetMin"] as Number).toInt(), (it["learn"] as Number).toInt() != 0, (it["contextChip"] as Number).toInt()) }
        val startWeights = db.rows("SELECT name, value FROM engine_weight").associate { (it["name"] as String) to (it["value"] as Number).toDouble() }
        fun toListen(r: Row) = ListenRow(r.songId, r.startedAt, r.endedAt, r.playedMs, r.durationMs, r.endReason, r.origin, r.depth, r.sessionId, r.tz, r.learn, id = r.id, contextChip = r.chip)
        val groups = VersionGroups(songs.values, links)
        val edgesBySeed = edges.groupBy { it.seedId }
        val sessions = all.groupBy { it.sessionId }.values.sortedBy { it.first().startedAt }
        fun g(r: Row) = Signals.engagement(toListen(r), songs[r.songId]?.likedAt)
        // Picks by proxy, as EngineReplayTest takes them: a session's first listen, or one after a gap.
        fun picks(session: List<Row>): List<Row> = session.filterIndexed { i, r ->
            if (i == 0) true else { val prev = session[i - 1]; r.startedAt - prev.endedAt > maxOf(prev.durationMs, 60_000L) }
        }
        val p = EngineParams.DEFAULT.withFamiliarity(0.25)
        val dial = 0.5
        val day = 86_400_000L

        val tallies = arms.associate { it.name to Tally() }
        var shared = 0; var sharedLead = 0; var sharedFirst = 0; var sharedPoints = 0
        val learners = LinkedHashMap<String, Learner>()
        val laneU = HashMap<String, DoubleArray>()
        if ("learn" in flags) for (a in arms) {
            if (a.lean == Lean.AUTO) { learners[a.name] = Learner(startWeights); laneU[a.name] = DoubleArray(5) }
            else for (v in listOf(" unweighted", " weighted", " by placed")) { learners[a.name + v] = Learner(startWeights); laneU[a.name + v] = DoubleArray(5) }
        }

        data class Point(val t: Long, val targets: List<Row>, val idx: Int)
        val points = ArrayList<Point>()
        for ((i, session) in sessions.withIndex()) {
            if (i < 5) continue
            if (mode == "A") {
                val pk = picks(session).filter { g(it) >= 0.5 }
                if (pk.isNotEmpty()) points += Point(session.first().startedAt, pk, i)
            } else {
                val at = when (mode) { "C" -> 7; "B2" -> 1; else -> 3 }
                if (session.size < at + 5) continue
                val later = session.drop(at).filter { g(it) >= 0.5 }
                if (later.isNotEmpty()) points += Point(session[at].startedAt, later, i)
            }
        }
        val chosen = points.takeLast(lastN)
        var scored = 0
        val t0 = System.nanoTime()
        for (pt in chosen) {
            val t = pt.t
            val before = all.filter { it.endedAt <= t }
            if (before.isEmpty()) continue
            scored++
            val heardBefore = before.mapTo(HashSet()) { it.songId }
            val tz = before.last().tz
            val base = EngineInput(t, songs, before.map(::toListen), edges, links, bucket = dayPartBucket(t, tz), tzOffsetMin = tz)
            val stats = LibraryStats(base, p)
            val returning = EngineRow.returningArtists(base, stats, p)
            val lastGood = HashMap<String, Long>(); val lastStart = HashMap<String, Long>()
            for (r in before) { lastStart.merge(r.songId, r.startedAt, ::maxOf); if (g(r) >= 0.5) lastGood.merge(r.songId, r.startedAt, ::maxOf) }
            val latestSession = stats.latestSessionId
            val currentSession = currentSessionId(base.listens)
            val nowSongs = before.filter { it.sessionId == latestSession && g(it) >= 0.5 }.mapTo(HashSet()) { it.songId }
            val nowRefs = HashSet<String>(); nowSongs.forEach { s -> edgesBySeed[s]?.forEach { nowRefs += it.songId } }
            val sessionSongs = before.filter { it.sessionId == latestSession || it.sessionId == currentSession }.mapTo(HashSet()) { it.songId }
            // Never heard, Your artists, Loved and forgotten, Fits the session: each judged on its own.
            fun truth(k: Int, id: String): Boolean = when (k) {
                0 -> id !in heardBefore && songs[id]?.liked != true && songs[id]?.inLibrary != true
                1 -> songs[id]?.artistId in returning
                2 -> (songs[id]?.liked == true || (stats.songs[id]?.goodListens ?: 0) >= p.leanLovedListens) && (lastGood[id] ?: 0L) < t - p.dormantAfterDays * day && (stats.songs[id]?.oldActivation ?: 0.0) > 0
                else -> id in nowRefs
            }
            // The just-played list as ListenDao.justPlayed reads it: heard well in the last day, or started this session.
            val last = before.maxByOrNull { it.endedAt }!!
            val sessionNow = if (t - last.endedAt <= p.sessionGapMs) last.sessionId else -1L
            val played = before.filter { r ->
                (r.startedAt >= t - day && r.playedMs >= 30_000 && ((r.durationMs > 0 && r.playedMs * 20 >= r.durationMs * 9) || (r.durationMs <= 0 && r.playedMs >= 120_000))) || r.sessionId == sessionNow
            }.map { r -> songs[r.songId].let { PlayedSong(r.songId, it?.title ?: "", it?.artistName) } }
            val targetGroups = pt.targets.associateBy { groups.groupOf(it.songId) }
            val screens = HashMap<String, List<Card>>()
            for (arm in arms) {
                val tally = tallies[arm.name]!!
                val input = base.copy(chip = arm.chip)
                val n0 = System.nanoTime()
                val row = EngineRow.build(input, p = p, dial = dial, newOnly = arm.newOnly, random = Random(pt.idx.toLong()), stored = arm.lean)
                tally.nanos += System.nanoTime() - n0
                val lead = row.lean.lane
                val screen = onScreen(row, played, songs)
                screens[arm.name] = screen
                val ids = screen.map { it.songId }
                val gs = ids.map { groups.groupOf(it) }
                tally.builds++; tally.screen += ids.size; tally.collisions += gs.size - gs.toSet().size
                tally.dropped += row.cards.count { c -> screen.none { it.songId == c.songId } }
                tally.capSum += row.leadArtistCap
                if (lead != null) {
                    val q = row.quotas[lead] ?: 0
                    val leadCards = screen.filter { it.lane == lead }
                    tally.leadQuota += q; tally.leadScreen += leadCards.size; tally.leadPlaced += row.leanPlaced
                    if (leadCards.size < q) tally.shortOnScreen++
                    if (row.leanPlaced < q) tally.engineShort++
                    if (leadCards.isEmpty()) tally.noneBuilds++
                    when (LeanRow.heading(row.lean, q, row.leanPlaced, leadCards.size)) { LeanHeadingKind.SHORT -> tally.headingShort++; LeanHeadingKind.NOTHING -> tally.headingNothing++; else -> {} }
                    tally.tryBothLead += row.cards.take(10).count { it.lane == lead }; tally.tryBothFirst += row.cards.take(2).count { it.lane == lead }
                    tally.leadFirst += screen.take(4).count { it.lane == lead }
                    tally.leadPool += (row.cards + row.pool).count { it.lane == lead }
                    val own = when (lead) { Lane.ARTIST -> "x_art"; Lane.REDISCOVER -> "x_dorm"; Lane.RELATED -> "x_seed"; Lane.EXPLORE -> "new_to_you"; else -> "" }
                    tally.ownCaption += leadCards.count { it.reasons.firstOrNull() == own }
                    tally.neverStartedLead += leadCards.count { it.songId !in heardBefore }
                    tally.newArtistLead += leadCards.count { it.features[Features.NOVEL] >= 1.0 }
                    tally.newArtistFirst += screen.take(4).count { it.lane == lead && it.features[Features.NOVEL] >= 1.0 }
                    tally.returningLead += leadCards.count { songs[it.songId]?.artistId in returning }
                    tally.returningFirst += screen.take(4).count { it.lane == lead && songs[it.songId]?.artistId in returning }
                    tally.knownLead += leadCards.count { songs[it.songId]?.liked == true || songs[it.songId]?.inLibrary == true }
                    tally.startedRecentlyLead += leadCards.count { (lastStart[it.songId] ?: 0L) >= t - 14 * day }
                    // What holds on any library: each lead card is what its label says.
                    for (c in row.cards.filter { it.lane == lead }) when (row.lean) {
                        Lean.NEW -> assertTrue("${arm.name}: ${c.songId} is not new", truth(0, c.songId))
                        Lean.ARTIST -> assertTrue("${arm.name}: ${c.songId} is not by a returning artist", truth(1, c.songId))
                        Lean.FORGOTTEN -> assertTrue("${arm.name}: ${c.songId} is not loved and quiet", truth(2, c.songId) && (lastStart[c.songId] ?: 0L) < t - p.artistLaneQuietDays * day)
                        Lean.SIMILAR -> assertTrue("${arm.name}: ${c.songId} is not from the session", c.seedId in sessionSongs)
                        else -> {}
                    }
                } else {
                    tally.newArtistLead += screen.count { it.features[Features.NOVEL] >= 1.0 }
                    tally.returningLead += screen.count { songs[it.songId]?.artistId in returning }
                    // A lean that had too little: the row is Auto's, none of its cards the lean's, under the "nothing yet" heading.
                    if (row.gaveWay != null) {
                        tally.leadQuota += EngineRow.shape(p, dial, arm.newOnly, false, arm.chip, arm.lean).let { s -> s.lead?.let { s.quotas[it] } ?: 0 }
                        tally.shortOnScreen++; tally.engineShort++; tally.noneBuilds++; tally.headingNothing++
                    }
                }
                for (k in 0 until 4) { tally.truthRow[k] += ids.count { truth(k, it) }; tally.truthFirst[k] += ids.take(4).count { truth(k, it) } }
                for (c in screen) tally.laneCards[c.lane.ordinal]++
                for ((gid, _) in targetGroups) { tally.targets++; if (gs.indexOf(gid) >= 0) tally.hits++ }

                if ("pull" in flags && lead != null) {
                    // A pull: the whole row sits the next build out, except Playing now's lead cards past the first column.
                    val damped = input.pastSeeds + PastSeeds(t, row.seeds)
                    val whole = EngineRow.build(input.copy(banned = row.cards.mapTo(HashSet()) { it.songId }, pastSeeds = damped), p = p, dial = dial, newOnly = arm.newOnly, random = Random(pt.idx.toLong() + 7), stored = arm.lean)
                    val exempt = EngineRow.build(input.copy(banned = LeanRow.pullBanned(row.cards, row.lean, p.columns), pastSeeds = damped), p = p, dial = dial, newOnly = arm.newOnly, random = Random(pt.idx.toLong() + 7), stored = arm.lean)
                    tally.pullBuilds++; tally.pullLeadAll += whole.leanPlaced; tally.pullLeadExempt += exempt.leanPlaced
                }

                if ("ff" in flags) {
                    // Forgotten favourites, the row under Quick picks, as RecommendationSql reads it at t.
                    val ff = db.rows("""SELECT old.eid AS id FROM (SELECT songId AS eid, SUM(playTime) AS oldPlayTime FROM event WHERE timestamp < ($t - 86400000 * 30) GROUP BY songId HAVING COUNT(*) >= 3) AS old
                        LEFT JOIN (SELECT songId, SUM(playTime) AS newPlayTime FROM event WHERE timestamp > ($t - 86400000 * 30) AND timestamp <= $t GROUP BY songId) AS recent ON recent.songId = old.eid
                        WHERE 0.2 * old.oldPlayTime > COALESCE(recent.newPlayTime, 0) ORDER BY old.oldPlayTime DESC LIMIT 100""").map { it["id"] as String }
                    val ffSet = ff.toHashSet()
                    tally.ffOverlap += screen.count { it.songId in ffSet }
                    // Quick picks claims first, then the row draws its forty and shows what is left.
                    val pass = TidyPass(played)
                    pass.row(screen, true, { it.songId }, { songs[it.songId]?.title }, { songs[it.songId]?.artistName })
                    tally.ffRow += pass.row(ff.shuffled(Random(pt.idx.toLong())).take(40), false, { it }, { songs[it]?.title }, { songs[it]?.artistName }).take(20).size
                    tally.ffPoints++
                }

                if ("learn" in flags) {
                    // Every card on screen seen; played when the session went on to hear it well, else ignored.
                    // "weighted" is the shipped weight, from the lean's quota; "by placed" is the
                    // same odds rule from the lead cards the engine actually placed, for comparison.
                    val byPlaced = lead?.let { LeanWeighting.leadWeight(EngineRow.shape(p, dial, arm.newOnly, false, arm.chip, arm.lean).autoQuotas[it] ?: 0, row.leanPlaced, p.rowSize) } ?: 1.0
                    fun apply(key: String, weighted: Boolean, placed: Boolean = false) {
                        val l = learners[key] ?: return
                        val u = laneU[key]!!
                        screen.forEachIndexed { slot, c ->
                            val tg = targetGroups[groups.groupOf(c.songId)]
                            val (y, u0) = if (tg != null) g(tg) to 1.0 else 0.0 to 0.3
                            val w = if (placed) LeanWeighting.of(c.lane, lead, byPlaced) else if (weighted) LeanWeighting.of(c.lane, lead, row.leadWeight) else 1.0
                            l.apply(Example(c.features, c.lane, slot, y, u0 * w))
                            u[c.lane.ordinal] += u0 * w
                        }
                    }
                    if (arm.lean == Lean.AUTO) apply(arm.name, false) else { apply(arm.name + " unweighted", false); apply(arm.name + " weighted", true); apply(arm.name + " by placed", true, placed = true) }
                }
            }
            val a = screens["NEW"]; val b = screens["ARTIST"]
            if (a != null && b != null) {
                sharedPoints++
                shared += (a.map { it.songId }.toSet() intersect b.map { it.songId }.toSet()).size
                sharedLead += (a.filter { it.lane == Lane.EXPLORE }.map { it.songId }.toSet() intersect b.filter { it.lane == Lane.ARTIST }.map { it.songId }.toSet()).size
                sharedFirst += (a.take(4).map { it.songId }.toSet() intersect b.take(4).map { it.songId }.toSet()).size
            }
        }
        val secs = (System.nanoTime() - t0) / 1e9
        val lines = ArrayList<String>()
        fun out(s: String) { println(s); lines += s }
        out("lean replay $mode: $scored points${if (lastN < Int.MAX_VALUE) " (last $lastN)" else ""}, ${"%.0f".format(secs)} s, ${songs.size} songs, ${edges.size} edges, ${all.size} listens; figures are on screen, after the tidy pass")
        val labels = listOf("never heard", "returning artist", "loved+forgotten", "fits session")
        for (arm in arms) {
            val t = tallies[arm.name]!!
            val b = maxOf(1, t.builds).toDouble()
            out("== ${arm.name}: on screen %.1f, collisions %d, engine cards dropped by tidy %.2f, build %.0f ms".format(t.screen / b, t.collisions, t.dropped / b, t.nanos / 1e6 / b))
            if (t.leadQuota > 0) {
                out("   lead: quota %.1f, placed by engine %.1f, on screen %.1f, first column %.2f, short on screen %d of %d, none %d, in row and pool %.0f, own caption %.1f, cap %.2f".format(
                    t.leadQuota / b, t.leadPlaced / b, t.leadScreen / b, t.leadFirst / b, t.shortOnScreen, t.builds, t.noneBuilds, t.leadPool / b, t.ownCaption / b, t.capSum / b))
                out("   engine short of its quota in %d builds (heading: short %d, nothing %d); in Try both its first 10 cards hold %.1f lead (the 2 in the first column %.2f)".format(
                    t.engineShort, t.headingShort, t.headingNothing, t.tryBothLead / b, t.tryBothFirst / b))
                out("   lead cards: never started %.1f, new artist %.2f (first column %.2f), returning artist %.2f (first column %.2f), liked or in library %.2f, started in 14 days %.2f".format(
                    t.neverStartedLead / b, t.newArtistLead / b, t.newArtistFirst / b, t.returningLead / b, t.returningFirst / b, t.knownLead / b, t.startedRecentlyLead / b))
            } else out("   row: new-artist cards %.2f, returning-artist cards %.2f".format(t.newArtistLead / b, t.returningLead / b))
            out("   labels on screen (first column): " + labels.indices.joinToString(", ") { k -> "%s %.1f (%.2f)".format(labels[k], t.truthRow[k] / b, t.truthFirst[k] / b) })
            out("   lanes on screen: " + Lane.entries.joinToString(", ") { "%s %.1f".format(it.name.lowercase(), t.laneCards[it.ordinal] / b) })
            out("   hits on screen: %d of %d, %.3f".format(t.hits, t.targets, t.hits.toDouble() / maxOf(1, t.targets)))
            if (t.pullBuilds > 0) out("   after a pull: lead placed %.1f with the whole row banned, %.1f with the lead past the first column let back".format(t.pullLeadAll.toDouble() / t.pullBuilds, t.pullLeadExempt.toDouble() / t.pullBuilds))
            if (t.ffPoints > 0) out("   Forgotten favourites row after Quick picks: %.1f cards; Quick picks cards in its top 100: %.2f".format(t.ffRow.toDouble() / t.ffPoints, t.ffOverlap.toDouble() / t.ffPoints))
        }
        if (sharedPoints > 0) out("NEW and ARTIST on screen, same dice: shared cards %.2f a row, lead with lead %.2f, first column %.2f".format(shared.toDouble() / sharedPoints, sharedLead.toDouble() / sharedPoints, sharedFirst.toDouble() / sharedPoints))
        if (learners.isNotEmpty()) {
            out("learning from the copy's weights, every on-screen card seen, no daily budget:")
            val names = listOf("x_novel", "x_art", "x_seed", "x_dorm", "x_act", "w_pos", "b_explore", "b_related", "b_artist", "b_rediscover", "b_again")
            // As wide as the longest arm, so an arm with a chip keeps its column.
            val width = learners.keys.maxOf { it.length } + 1
            out("   " + "start".padEnd(width) + names.joinToString(" ") { "%s %.3f".format(it, startWeights[it] ?: Features.priors[it]!!.value) })
            for ((k, l) in learners) {
                val w = l.asMap(); val u = laneU[k]!!; val tot = u.sum()
                out("   " + k.padEnd(width) + names.joinToString(" ") { "%s %.3f".format(it, w[it]) } + "  | evidence: " + Lane.entries.joinToString(" ") { "%s %.2f".format(it.name.lowercase(), u[it.ordinal] / tot) })
            }
        }
        System.getenv("ENGINE_REPLAY_LEAN_OUT")?.let { File(it).writeText(lines.joinToString("\n") + "\n") }
        for (arm in arms) assertEquals("${arm.name}: version collisions", 0, tallies[arm.name]!!.collisions)
    }

    /** The row on screen: the tidy pass Home runs, then, under a lean, LeanRow.compose. */
    private fun onScreen(row: BuiltRow, played: List<PlayedSong>, songs: Map<String, SongRow>): List<Card> {
        val lead = row.lean.lane
        val againIds = row.cards.filter { it.lane == Lane.AGAIN }.mapTo(HashSet()) { it.songId }
        val tidied = TidyPass(played).row(row.cards + row.pool, true, { it.songId }, { songs[it.songId]?.title }, { songs[it.songId]?.artistName }, { songs[it.songId]?.artistId },
            { it.songId in againIds }, 2, if (lead == null) null else { c -> if (c.lane == lead) maxOf(2, row.leadArtistCap) else 2 })
        if (lead == null) return tidied.take(20)
        return LeanRow.compose(row.cards, tidied, lead, 20, 4, { it.songId }, { it.lane }, { songs[it.songId]?.artistName })
    }
}
