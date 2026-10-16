/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.db.RecommendationSql
import com.dd3boh.outertune.db.RelatedSql
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.ZoneId
import java.util.TimeZone
import kotlin.random.Random

/**
 * The engine against a real history, session by session. Gated on `ENGINE_REPLAY=/path/to/song.db`
 * (a version 21 database, with the listen log backfilled) so nothing private is ever needed for
 * the ordinary run.
 *
 * At each session start `t` every row is given the database as it stood at `t` ([AsOf]): the
 * listens that had ended, the likes, library adds, related lists and version links that existed,
 * and only the songs the table held. The engine builds its row from that at its priors, and the
 * shipped classic query runs its own text with `:now = t` over copies of its tables cut at `t`.
 * Until 16 Oct 2026 the classic query read the whole database, so its last five plays, its week
 * and its likes were the end of the history, and the engine was handed every related list, every
 * like and every song the table would ever hold. Both made the comparison unfair.
 *
 * Each row is scored on the session's picks: hit@20 is the share of picks whose version group was
 * in the row, split into songs heard before and songs new to the listener; engaged hit sums their
 * engagement. Two baselines go with them, most recent and personal top frequency. Picks are
 * matched by the version groups known today; collisions, two versions in one row, by the groups
 * known at `t`, since a version link fetched later joins songs no row could have told apart then.
 * The engine's must be zero.
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

    /** Which edge query the engine row reads: the app's ENGINE_EDGES for one source, its ENGINE_EDGES_ALL, or every stored row. */
    private enum class EdgeKind { YOUTUBE, LASTFM, UNION, EVERY }

    /** A song's own facts, every stored local time already put back to an instant. */
    private class SongFacts(val id: String, val title: String, val artistId: String?, val artistName: String?, val likedAt: Long?, val inLibraryAt: Long?, val downloadAt: Long?, val isLocal: Boolean)

    /** One stored related row, and the moment it became known. */
    private class EdgeRow(val id: Long, val seed: String, val song: String, val source: Int, val knownAt: Long)

    /**
     * The database as it stood at a moment, for every row the replay builds.
     *
     * A listen counts from its end. A like, a library add and a download count from their own
     * dates. A related list counts from when it was fetched: a legacy list of unknown age from its
     * seed's first listen, which is how the app dates it (dateLegacyRelatedEdges), or from the
     * start when the seed was never heard. YouTube's version links count from when they were
     * fetched. A song exists from the first of these that names it, so no lane can offer a song the
     * table only learned of later. A list refreshed after the session replaced the one the seed had
     * then, which is gone, so that seed has no list here at all: every row loses it alike.
     *
     * The classic query keeps its own text. It runs over TEMP tables named event, related_song_map
     * and song that hold only what existed at the moment, which shadow the real ones because SQLite
     * looks a bare table name up in temp before main. [classic] checks that it did.
     */
    private inner class AsOf(private val db: Connection, private val facts: Map<String, SongFacts>, zone: ZoneId) {
        private val edgeRows: List<EdgeRow>
        private val edgeKnown: LongArray
        private val linkRows: List<Pair<Long, VersionLink>>
        private val linkKnown: LongArray
        private val songKnown = HashMap<String, Long>()
        /** Event ids by the instant they were written, which is the end of the play. */
        private val events: List<Pair<Long, Long>>
        private val likes: List<Pair<Long, String>>
        private var eventsIn = 0
        private var edgesIn = 0
        private var likesIn = 0
        private var at = Long.MIN_VALUE
        private var shadowing = false

        init {
            val firstListen = db.rows("SELECT songId, MIN(startedAt) AS first FROM listen WHERE startedAt > 1000000000000 GROUP BY songId")
                .associate { it["songId"] as String to (it["first"] as Number).toLong() }
            edgeRows = db.rows("SELECT id, songId, relatedSongId, fetchedAt, source FROM related_song_map ORDER BY id").map {
                val seed = it["songId"] as String
                val fetchedAt = (it["fetchedAt"] as Number).toLong()
                EdgeRow((it["id"] as Number).toLong(), seed, it["relatedSongId"] as String, (it["source"] as Number).toInt(),
                    if (fetchedAt > 0) fetchedAt else firstListen[seed] ?: 0L)
            }.sortedWith(compareBy<EdgeRow> { it.knownAt }.thenBy { it.id })
            edgeKnown = LongArray(edgeRows.size) { edgeRows[it].knownAt }
            linkRows = db.rows("SELECT songId, versionId, fetchedAt FROM song_version_map")
                .map { (it["fetchedAt"] as Number).toLong() to VersionLink(it["songId"] as String, it["versionId"] as String) }
                .sortedBy { it.first }
            linkKnown = LongArray(linkRows.size) { linkRows[it].first }
            events = db.rows("SELECT id, timestamp FROM event")
                .map { storedLocalToInstant((it["timestamp"] as Number).toLong(), zone) to (it["id"] as Number).toLong() }
                .sortedWith(compareBy<Pair<Long, Long>> { it.first }.thenBy { it.second })
            likes = facts.values.mapNotNull { f -> f.likedAt?.let { it to f.id } }.sortedBy { it.first }
            fun note(id: String, t: Long) { songKnown.merge(id, t, ::minOf) }
            for ((id, t) in firstListen) note(id, t)
            for (e in edgeRows) { note(e.seed, e.knownAt); note(e.song, e.knownAt) }
            for (f in facts.values) { f.likedAt?.let { note(f.id, it) }; f.inLibraryAt?.let { note(f.id, it) }; f.downloadAt?.let { note(f.id, it) } }
        }

        private fun knownCount(times: LongArray, t: Long): Int {
            var lo = 0; var hi = times.size
            while (lo < hi) { val mid = (lo + hi) ushr 1; if (times[mid] <= t) lo = mid + 1 else hi = mid }
            return lo
        }

        /** The songs the table held at [t], each liked and in the library only from its own date. */
        fun songs(t: Long): Map<String, SongRow> {
            val out = HashMap<String, SongRow>(facts.size * 2)
            for (f in facts.values) {
                if ((songKnown[f.id] ?: continue) > t) continue
                val liked = f.likedAt != null && f.likedAt <= t
                out[f.id] = SongRow(f.id, f.title, f.artistId, f.artistName, liked, f.likedAt?.takeIf { liked }, f.inLibraryAt != null && f.inLibraryAt <= t, f.isLocal)
            }
            return out
        }

        fun links(t: Long): List<VersionLink> = linkRows.subList(0, knownCount(linkKnown, t)).map { it.second }

        /**
         * The edges [kind] reads, from the lists known at [t]. The same rules as the app's queries
         * (RelatedSql.ENGINE_EDGES and ENGINE_EDGES_ALL), applied here because the Last.fm fallback
         * depends on which lists existed then; [checkAgainstApp] holds the two together.
         */
        fun edges(kind: EdgeKind, t: Long): List<Edge> {
            val known = edgeRows.subList(0, knownCount(edgeKnown, t))
            return when (kind) {
                EdgeKind.YOUTUBE -> known.filter { it.source == 0 }.map { Edge(it.seed, it.song, Provenance.YOUTUBE) }
                EdgeKind.LASTFM -> {
                    val ownList = known.filter { it.source == 1 }.mapTo(HashSet()) { it.seed }
                    known.filter { it.source == 1 || (it.source == 0 && it.seed !in ownList) }
                        .map { Edge(it.seed, it.song, if (it.source == 1) Provenance.LASTFM else Provenance.YOUTUBE) }
                }
                EdgeKind.UNION -> {
                    val bits = LinkedHashMap<Pair<String, String>, Int>()
                    for (e in known) if (e.source == 0 || e.source == 1) bits.merge(e.seed to e.song, if (e.source == 0) Provenance.YOUTUBE else Provenance.LASTFM) { a, b -> a or b }
                    bits.map { (pair, b) -> Edge(pair.first, pair.second, b) }
                }
                EdgeKind.EVERY -> known.map { Edge(it.seed, it.song, Provenance.YOUTUBE) }
            }
        }

        /** With every list known, [edges] must be exactly what the app's own query returns. Run before [classic] first shadows the tables. */
        fun checkAgainstApp(kind: EdgeKind) {
            check(!shadowing) { "the app's query would read the shadow tables" }
            val sql = when (kind) {
                EdgeKind.YOUTUBE -> RelatedSql.ENGINE_EDGES.replace(":source", "0")
                EdgeKind.LASTFM -> RelatedSql.ENGINE_EDGES.replace(":source", "1")
                EdgeKind.UNION -> RelatedSql.ENGINE_EDGES_ALL
                EdgeKind.EVERY -> "SELECT songId, relatedSongId FROM related_song_map"
            }
            val app = db.rows(sql).groupingBy { Triple(it["songId"] as String, it["relatedSongId"] as String, (it["sources"] as Number?)?.toInt() ?: Provenance.YOUTUBE) }.eachCount()
            val mine = edges(kind, Long.MAX_VALUE).groupingBy { Triple(it.seedId, it.songId, it.sources) }.eachCount()
            assertEquals("$kind edges with every list known, against the app's query", app, mine)
        }

        private fun shadow() {
            db.createStatement().use { st ->
                st.execute("PRAGMA temp_store = MEMORY")
                st.execute("CREATE TEMP TABLE event AS SELECT * FROM main.event WHERE 0")
                st.execute("CREATE TEMP TABLE related_song_map AS SELECT * FROM main.related_song_map WHERE 0")
                st.execute("CREATE INDEX temp.replay_related_seed ON related_song_map(songId)")
                st.execute("CREATE TEMP TABLE song AS SELECT * FROM main.song")
                st.execute("UPDATE temp.song SET liked = 0")
                st.execute("CREATE UNIQUE INDEX temp.replay_song_id ON song(id)")
            }
            shadowing = true
        }

        private fun count(table: String): Long = (db.rows("SELECT COUNT(*) AS n FROM $table").single()["n"] as Number).toLong()

        /** Moves the shadow tables on to [t]: the events written by then, the lists fetched by then, the likes made by then. */
        private fun advance(t: Long) {
            check(t >= at) { "the replay only moves forward" }
            at = t
            if (!shadowing) shadow()
            db.autoCommit = false
            db.prepareStatement("INSERT INTO temp.event(rowid, id, songId, timestamp, playTime) SELECT id, id, songId, timestamp, playTime FROM main.event WHERE id = ?").use { ps ->
                while (eventsIn < events.size && events[eventsIn].first <= t) { ps.setLong(1, events[eventsIn].second); ps.addBatch(); eventsIn++ }
                ps.executeBatch()
            }
            db.prepareStatement("INSERT INTO temp.related_song_map SELECT * FROM main.related_song_map WHERE id = ?").use { ps ->
                while (edgesIn < edgeRows.size && edgeRows[edgesIn].knownAt <= t) { ps.setLong(1, edgeRows[edgesIn].id); ps.addBatch(); edgesIn++ }
                ps.executeBatch()
            }
            db.prepareStatement("UPDATE temp.song SET liked = 1 WHERE id = ?").use { ps ->
                while (likesIn < likes.size && likes[likesIn].first <= t) { ps.setString(1, likes[likesIn].second); ps.addBatch(); likesIn++ }
                ps.executeBatch()
            }
            db.commit()
            db.autoCommit = true
        }

        /** The shipped classic query, its own text, run as the database stood at [t]: its top twenty. */
        fun classic(t: Long): List<String> {
            advance(t)
            // A bare name that reached main instead of temp would make this the old, uncut row.
            assertEquals("event, as the query reads it", eventsIn.toLong(), count("event"))
            assertEquals("related_song_map, as the query reads it", edgesIn.toLong(), count("related_song_map"))
            return db.rows(RecommendationSql.QUICK_PICKS.replace(":now", t.toString())).map { it["id"] as String }.take(20)
        }
    }

    @Test
    fun `replay a real history`() {
        val path = System.getenv("ENGINE_REPLAY"); assumeTrue("set ENGINE_REPLAY to run", !path.isNullOrBlank())
        // The working copy goes to ENGINE_REPLAY_WORK when it is set, so a private history can be
        // kept out of the shared temp folder.
        val work = System.getenv("ENGINE_REPLAY_WORK")?.takeIf { it.isNotBlank() }?.let { File(it).apply { mkdirs() } }
        val copy = File.createTempFile("replay", ".db", work).apply { deleteOnExit() }; File(path!!).copyTo(copy, overwrite = true)
        DriverManager.getConnection("jdbc:sqlite:${copy.path}").use { db ->
            val zone = TimeZone.getDefault().toZoneId()
            val facts = db.rows("""SELECT s.id, s.title, s.liked, s.likedDate, s.inLibrary, s.dateDownload, s.isLocal,
                (SELECT m.artistId FROM song_artist_map m WHERE m.songId = s.id ORDER BY m.position LIMIT 1) AS artistId,
                (SELECT a.name FROM song_artist_map m JOIN artist a ON a.id = m.artistId WHERE m.songId = s.id ORDER BY m.position LIMIT 1) AS artistName FROM song s""")
                .associate { r ->
                    val id = r["id"] as String
                    val liked = (r["liked"] as Number).toInt() != 0
                    fun instant(column: String) = (r[column] as Number?)?.toLong()?.let { storedLocalToInstant(it, zone) }
                    id to SongFacts(id, r["title"] as String, r["artistId"] as String?, r["artistName"] as String?,
                        instant("likedDate")?.takeIf { liked }, instant("inLibrary"), instant("dateDownload"), (r["isLocal"] as Number).toInt() != 0)
                }
            // Today's view of every song, for the yardstick only (version groups and artists to
            // score the rows by). No row is built from it.
            val songs = facts.values.associate { f -> f.id to SongRow(f.id, f.title, f.artistId, f.artistName, f.likedAt != null, f.likedAt, f.inLibraryAt != null, f.isLocal) }
            // Through the app's own query: ENGINE_REPLAY_SOURCE=1 for Last.fm (with YouTube's
            // where it has nothing), 0 or unset for YouTube. "all" reads every row whatever its
            // source, for the 23 Sep trial's databases, which stored their edge sets that way.
            val kind = when (System.getenv("ENGINE_REPLAY_SOURCE")) { "all" -> EdgeKind.EVERY; "1" -> EdgeKind.LASTFM; else -> EdgeKind.YOUTUBE }
            val links = db.rows("SELECT songId, versionId FROM song_version_map").map { VersionLink(it["songId"] as String, it["versionId"] as String) }
            // Open rows have no end yet, and failed plays are left out as the app's loader leaves
            // them out (EngineListens). A row whose start was lost (one in the 15 Oct export starts
            // in 1970) would sort its whole session first, so it is left out too.
            val all = db.rows("SELECT songId, startedAt, endedAt, playedMs, durationMs, endReason, origin, autoplayDepth, sessionId, tzOffsetMin FROM listen WHERE endReason NOT IN (5, 6) AND startedAt > 1000000000000 ORDER BY startedAt")
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
            val asOf = AsOf(db, facts, zone)

            // ENGINE_REPLAY_MIX, with QUICK: the similar-songs sources as arms over the same sessions
            // and the same dice, instead of the one engine row. Unset, nothing below changes.
            val mixArms = System.getenv("ENGINE_REPLAY_MIX")
            if (System.getenv("ENGINE_REPLAY_QUICK") == "1" && !mixArms.isNullOrBlank()) {
                mixReplay(asOf, System.getenv("ENGINE_REPLAY_LABEL") ?: File(path).name, mixArms, songs, groups, all, sessions, ::picks, ::toListen)
                return@use
            }
            asOf.checkAgainstApp(kind)

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
                var edgeCount = 0
                // ENGINE_REPLAY_PER_SESSION=<file>: each scored session's hits, summed over the draws,
                // one line a session, so two runs over the same history can be compared session by
                // session (index, start, picks, engine hits, its related lane's hits, classic hits).
                val perSession = System.getenv("ENGINE_REPLAY_PER_SESSION")?.takeIf { it.isNotBlank() }?.let { File(it).printWriter() }
                for ((i, session) in sessions.withIndex()) {
                    if (i < 5) continue
                    val t = session.first().startedAt
                    val before = all.filter { it.endedAt <= t }
                    if (before.isEmpty()) continue
                    val sessionPicks = picks(session).filter { Signals.engagement(toListen(it), null) >= 0.5 }
                    if (sessionPicks.isEmpty()) continue
                    scored++
                    val heard = before.map { it.songId }.toSet()
                    val edges = asOf.edges(kind, t); edgeCount = edges.size
                    val input = EngineInput(t, asOf.songs(t), before.map(::toListen), edges, asOf.links(t), bucket = dayPartBucket(t, session.first().tz), tzOffsetMin = session.first().tz)
                    val knownGroups = VersionGroups(input.songs.values, input.versionLinks)
                    fun score(sc: Score, ids: List<String>) {
                        val g = ids.map { groups.groupOf(it) }
                        val k = ids.map { knownGroups.groupOf(it) }
                        sc.sessions++; sc.collisions += k.size - k.toSet().size
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
                    val engineBefore = seeded.hits; val relatedBefore = byLane[Lane.RELATED]?.get(1) ?: 0; val classicBefore = qClassic.hits
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
                    score(qClassic, asOf.classic(t))
                    perSession?.println("$i\t$t\t${sessionPicks.size}\t${seeded.hits - engineBefore}\t${(byLane[Lane.RELATED]?.get(1) ?: 0) - relatedBefore}\t${qClassic.hits - classicBefore}")
                    score(qRecent, before.sortedByDescending { it.endedAt }.map { it.songId }.distinct().take(20))
                    score(qFrequent, before.groupingBy { it.songId }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(20))
                }
                fun line(name: String, s: Score) = println("  %-10s hit@20 %.3f (repeat %d, new %d of %d picks), engaged %.1f, artists/row %.1f, collisions %d".format(
                    name, s.hits.toDouble() / maxOf(1, s.picks), s.repeatHits, s.newHits, s.picks, s.engaged, s.artists.toDouble() / maxOf(1, s.sessions), s.collisions))
                println("quick replay [$label]: ${sessions.size} sessions, $scored scored, ${all.size} listens, $edgeCount edges known at the last session, every row cut at its session's start")
                line("engine", seeded); line("classic", qClassic); line("recent", qRecent); line("frequent", qFrequent)
                for (lane in Lane.values()) byLane[lane]?.let { (cards, hits, fresh) ->
                    println("  lane %-10s %5d cards, %3d hits (%d new), %.2f%% of its cards hit".format(lane.name.lowercase(), cards, hits, fresh, 100.0 * hits / maxOf(1, cards)))
                }
                perSession?.close()
                assertEquals(0, seeded.collisions)
                return@use
            }

            val engine = Score(); val familiar = Score(); val fresh1 = Score(); val fresh1cap = Score(); val classic = Score(); val recent = Score(); val frequent = Score()
            // Warm start: fit the weights on the first six tenths of the sessions, then judge the
            // last four tenths with them against the priors. It is fitted on the database as it
            // stood when the held-out part begins.
            val warmed = Score(); val priorsOnHeldOut = Score()
            val split = (sessions.size * 0.6).toInt()
            val splitAt = sessions[split].first().startedAt
            val trainListens = sessions.take(split).flatten().map(::toListen)
            val trainInput = EngineInput(splitAt, asOf.songs(splitAt), trainListens, asOf.edges(kind, splitAt), asOf.links(splitAt), tzOffsetMin = sessions.first().first().tz)
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
            var edgeCount = 0
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
                val edges = asOf.edges(kind, t); edgeCount = edges.size
                val input = EngineInput(t, asOf.songs(t), before.map(::toListen), edges, asOf.links(t), bucket = dayPartBucket(t, session.first().tz), tzOffsetMin = session.first().tz)
                val knownGroups = VersionGroups(input.songs.values, input.versionLinks)
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
                val classicIds = asOf.classic(t)
                val recentIds = before.sortedByDescending { it.endedAt }.map { it.songId }.distinct().take(20)
                val frequentIds = before.groupingBy { it.songId }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(20)
                fun score(sc: Score, ids: List<String>, groupIds: List<String>) {
                    sc.sessions++
                    val k = ids.map { knownGroups.groupOf(it) }
                    sc.collisions += k.size - k.toSet().size
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
            println("engine replay: ${sessions.size} sessions, $considered scored, ${all.size} listens, ${songs.size} songs, $edgeCount edges known at the last session, every row cut at its session's start (legacy column: proxy picks, favours the classic query)")
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
     * Every arm goes through EngineRow.build itself, with the split the arm asks for, so what is
     * measured is the app's engine and ledger. The single-source arms pass no split, which draws
     * exactly as a run without ENGINE_REPLAY_MIX does.
     *
     * The adaptive arm grades its own cards the way the app would grade impressions, but only the
     * cards the app counts as evidence: a card whose version group the session went on to pick is
     * PLAYED at that pick's engagement, any other IGNORED. Picks are a proxy for taps, and the
     * history was shaped by YouTube's rows, so this shows that the mechanism works and what it
     * costs or gains here, not where a real phone would settle.
     */
    private fun mixReplay(
        asOf: AsOf, label: String, arms: String, songs: Map<String, SongRow>, groups: VersionGroups,
        all: List<Row>, sessions: List<List<Row>>, picks: (List<Row>) -> List<Row>, toListen: (Row) -> ListenRow,
    ) {
        val names = if (arms == "all") listOf("youtube", "lastfm", "half", "adaptive") else arms.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        // The app's own rules: one source with YouTube's list where it has none, or both merged,
        // each over the lists known at the session's start.
        class Arm(val name: String, val kind: EdgeKind, val fixed: Double?, val adaptive: Boolean) {
            var picks = 0; var hits = 0; var repeat = 0; var fresh = 0; var engaged = 0.0; var collisions = 0
            var edges = 0
            val lanes = HashMap<Lane, IntArray>()   // cards, hits, new hits
            val kinds = Array(4) { IntArray(2) }    // youtube only, lastfm only, both, none: cards, hits
            var edgeCards = 0; var lastFmOnly = 0
            val perSession = ArrayList<Int>()
        }
        val armList = names.map { n ->
            when {
                n == "youtube" -> Arm(n, EdgeKind.YOUTUBE, null, false)
                n == "lastfm" -> Arm(n, EdgeKind.LASTFM, null, false)
                n == "half" -> Arm(n, EdgeKind.UNION, 0.5, false)
                n.startsWith("fixed:") -> Arm(n, EdgeKind.UNION, n.removePrefix("fixed:").toDouble(), false)
                n == "adaptive" -> Arm(n, EdgeKind.UNION, null, true)
                else -> error("unknown arm $n; use youtube, lastfm, half, fixed:<s> or adaptive")
            }
        }
        armList.map { it.kind }.distinct().forEach(asOf::checkAgainstApp)
        val reps = System.getenv("ENGINE_REPLAY_SEEDS")?.toIntOrNull() ?: 1
        val evidence = Array(reps) { ArrayList<SourceEvidence>() }
        val shares = ArrayList<DoubleArray>()   // per scored session, the adaptive share of each draw
        val finalStates = arrayOfNulls<SourceMix.State>(reps)
        var scored = 0
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
            val base = EngineInput(t, asOf.songs(t), before.map(toListen), emptyList(), asOf.links(t), bucket = dayPartBucket(t, tz), tzOffsetMin = tz)
            val knownGroups = VersionGroups(base.songs.values, base.versionLinks)
            val pickGroups = sessionPicks.associateBy { groups.groupOf(it.songId) }
            val sessionShares = DoubleArray(reps)
            val edgesNow = HashMap<EdgeKind, List<Edge>>()
            for (arm in armList) {
                val input = base.copy(edges = edgesNow.getOrPut(arm.kind) { asOf.edges(arm.kind, t) })
                arm.edges = input.edges.size
                var sessionHits = 0
                for (rep in 0 until reps) {
                    val seed = i.toLong() + rep * 100_003L
                    val share = if (arm.adaptive) {
                        val state = SourceMix.share(evidence[rep].filter { it.visibleAt >= t - SourceMix.WINDOW_MS }, tz)
                        finalStates[rep] = state; sessionShares[rep] = state.share
                        state.share
                    } else arm.fixed
                    val built = EngineRow.build(input, random = Random(seed), lastFmShare = share)
                    val g = built.cards.map { groups.groupOf(it.songId) }
                    val k = built.cards.map { knownGroups.groupOf(it.songId) }
                    arm.collisions += k.size - k.toSet().size
                    for (pk in sessionPicks) {
                        arm.picks++
                        if (groups.groupOf(pk.songId) in g) {
                            arm.hits++; sessionHits++; arm.engaged += Signals.engagement(toListen(pk), null)
                            if (pk.songId in heard) arm.repeat++ else arm.fresh++
                        }
                    }
                    for (c in built.cards) {
                        val bits = c.sources
                        val pk = pickGroups[groups.groupOf(c.songId)]
                        val lane = arm.lanes.getOrPut(c.lane) { IntArray(3) }
                        lane[0]++
                        if (pk != null) { lane[1]++; if (pk.songId !in heard) lane[2]++ }
                        val kind = when (bits and Provenance.SOURCES) { Provenance.YOUTUBE -> 0; Provenance.LASTFM -> 1; Provenance.SOURCES -> 2; else -> 3 }
                        arm.kinds[kind][0]++; if (pk != null) arm.kinds[kind][1]++
                        val edgePlace = (c.lane == Lane.RELATED || c.lane == Lane.EXPLORE) && bits and Provenance.SOURCES != 0
                        if (edgePlace) { arm.edgeCards++; if (bits and Provenance.SOURCES == Provenance.LASTFM) arm.lastFmOnly++ }
                        if (arm.adaptive && edgePlace && Provenance.isEvidence(bits)) {
                            val y = pk?.let { Signals.engagement(toListen(it), null) }
                            evidence[rep] += SourceEvidence(c.songId, 1, c.lane.ordinal + 1, bits, t, if (y != null) Outcome.PLAYED else Outcome.IGNORED, (y ?: 0.0).toFloat())
                        }
                    }
                }
                arm.perSession += sessionHits
            }
            if (armList.any { it.adaptive }) shares += sessionShares
        }
        val seconds = (System.nanoTime() - t0) / 1e9
        println("mix replay [$label]: ${sessions.size} sessions, $scored scored, $reps draws each, ${seconds.toInt()} s, every row cut at its session's start")
        for (arm in armList) {
            println("  %-10s %6d edges known at the last session, hit@20 %.4f (repeat %d, new %d of %d picks), engaged %.1f, collisions %d".format(
                arm.name, arm.edges, arm.hits.toDouble() / maxOf(1, arm.picks), arm.repeat, arm.fresh, arm.picks, arm.engaged, arm.collisions))
            println("    lanes: " + Lane.entries.mapNotNull { l -> arm.lanes[l]?.let { (c, h, f) -> "%s %d/%d (%d new)".format(l.name.lowercase(), h, c, f) } }.joinToString(", "))
            println("    cards by source: " + listOf("youtube only", "lastfm only", "both", "none").withIndex().joinToString(", ") { (k, n) -> "%s %d/%d".format(n, arm.kinds[k][1], arm.kinds[k][0]) })
            println("    Last.fm-only cards %.1f%% of %d edge cards".format(100.0 * arm.lastFmOnly / maxOf(1, arm.edgeCards), arm.edgeCards))
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
