/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.db.MusicDatabase.Companion.MUSIC_DATABASE_VERSION
import com.dd3boh.outertune.playback.QueueFit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import kotlin.random.Random

/**
 * The adaptive queue's taste reads the listen log a little at a time (ListenLog): only the rows
 * added since its last read and those still open then, unless a summary of the others says one of
 * them changed or went. Here that read is held to a full read of the same database after every
 * kind of write the app makes to the log, over random histories on the schema Room exported, with
 * EngineSql's own queries: the same rows in the same order, the taste made from them the same, a
 * full read only when a row read before has changed, and the summary in SQL the very numbers the
 * summary in Kotlin works out.
 */
class ListenLogTest {

    private lateinit var db: Connection

    @Before
    fun open() {
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        val schemaFile = File("schemas/com.dd3boh.outertune.db.InternalDatabase/$MUSIC_DATABASE_VERSION.json")
        val schema = Json.parseToJsonElement(schemaFile.readText()).jsonObject["database"]!!.jsonObject
        db.createStatement().use { st ->
            st.execute("PRAGMA foreign_keys = ON")
            for (entity in schema["entities"]!!.jsonArray.map { it.jsonObject }) {
                val table = entity["tableName"]!!.jsonPrimitive.content
                st.execute(entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray?.forEach { index ->
                    st.execute(index.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                }
            }
        }
    }

    @After
    fun close() = db.close()

    private fun exec(sql: String): Int = db.createStatement().use { it.executeUpdate(sql) }

    private fun longs(sql: String): List<Long> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getLong(1)) } }
    }

    private fun rows(sql: String): List<ListenRow> = db.createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.listen()) } }
    }

    private fun ResultSet.listen() = ListenRow(
        songId = getString("songId"), startedAt = getLong("startedAt"), endedAt = getLong("endedAt"),
        playedMs = getLong("playedMs"), durationMs = getLong("durationMs"), endReason = getInt("endReason"),
        origin = getInt("origin"), autoplayDepth = getInt("autoplayDepth"), sessionId = getLong("sessionId"),
        tzOffsetMin = getInt("tzOffsetMin"), learn = getInt("learn") != 0, runId = getLong("runId"),
        queueId = getLong("queueId"), impressionId = getLong("impressionId").takeUnless { wasNull() },
        contextChip = getInt("contextChip"), id = getLong("id"),
        continuesListenId = getLong("continuesListenId").takeUnless { wasNull() },
    )

    /** EngineSql's queries as Room runs them: each parameter written in, a list as Room expands it, an empty one as `()`. */
    private inner class Jdbc : ListenLog.Source {
        override fun all() = rows(EngineSql.LISTENS_IN_ORDER)

        override fun newOrOpen(after: Long, open: Collection<Long>) =
            rows(EngineSql.LISTENS_NEW_OR_OPEN.replace(":after", "$after").replace(":open", open.joinToString(",")))

        override fun mark(upTo: Long, except: Collection<Long>): ListenLog.Mark = db.createStatement().use { st ->
            st.executeQuery(EngineSql.LISTEN_MARK.replace(":upTo", "$upTo").replace(":except", except.joinToString(","))).use { rs ->
                assertTrue(rs.next())
                ListenLog.Mark(rs.getLong("count"), rs.getLong("sumA"), rs.getLong("sumB"))
            }
        }
    }

    /** A listening history that the app's own kinds of writes go on changing. */
    private inner class History(private val r: Random) {
        var clock = 1_790_000_000_000L
        private var nextSong = 0
        private var session = 1L
        private var queue = 1L
        val songs = ArrayList<String>()

        fun addSong() {
            // Ids of every shape: YouTube's, a local one, one not in ASCII.
            val id = when (r.nextInt(6)) {
                0 -> "LA${nextSong}é"
                1 -> "local-${nextSong}-ü"
                else -> "yt${nextSong}x${r.nextInt(1000)}"
            }
            nextSong++
            exec("INSERT INTO song(id, title, duration, liked, isLocal) VALUES ('$id', 'Song ${songs.size % 9}', 200, ${r.nextInt(3) / 2}, 0)")
            songs += id
        }

        fun listen(open: Boolean, id: Long? = null) {
            if (songs.isEmpty()) addSong()
            if (r.nextInt(6) == 0) session++
            if (r.nextInt(5) == 0) queue++
            clock += r.nextLong(1_000, 600_000)
            val duration = if (r.nextInt(10) == 0) -1L else r.nextLong(60_000, 400_000)
            val played = r.nextLong(0, 400_000)
            val reason = if (open) EndReason.OPEN else r.nextInt(0, 6)
            val impression = if (r.nextInt(4) == 0) "${r.nextLong(1, 500)}" else "NULL"
            val continues = if (r.nextInt(8) == 0) "${r.nextLong(1, 400)}" else "NULL"
            exec(
                "INSERT INTO listen(${if (id != null) "id, " else ""}songId, startedAt, endedAt, tzOffsetMin, playedMs, durationMs, ratio, endReason, origin, " +
                    "originSlot, queueId, autoplayDepth, sessionId, counted, learn, runId, endPositionMs, continuesListenId, impressionId, contextChip) VALUES (" +
                    "${if (id != null) "$id, " else ""}'${songs[r.nextInt(songs.size)]}', $clock, ${clock + played}, ${listOf(-300, 0, 60, 120, 330).random(r)}, " +
                    "$played, $duration, 0.5, $reason, ${r.nextInt(0, 12)}, ${r.nextInt(-1, 20)}, $queue, ${r.nextInt(0, 30)}, $session, " +
                    "${r.nextInt(2)}, ${if (r.nextInt(12) == 0) 0 else 1}, ${r.nextLong(0, 90)}, -1, $continues, $impression, ${r.nextInt(0, 6)})"
            )
        }

        /** One of the writes the app makes to the log, picked at random. */
        fun change() {
            val ids = longs("SELECT id FROM listen ORDER BY id")
            val open = longs("SELECT id FROM listen WHERE endReason = ${EndReason.OPEN}")
            when (r.nextInt(16)) {
                // Songs played and the song now playing: only rows at the top.
                0, 1 -> repeat(r.nextInt(1, 8)) { listen(open = false) }
                2 -> listen(open = true)
                // The song playing ends: its row is closed (ListenDao.update), the next one opens.
                3 -> {
                    exec("UPDATE listen SET endReason = ${r.nextInt(1, 6)}, endedAt = endedAt + 1000, playedMs = playedMs + 1000 WHERE endReason = ${EndReason.OPEN}")
                    if (r.nextBoolean()) listen(open = true)
                }
                // The minute's checkpoint of the song playing (ListenDao.checkpoint).
                4 -> exec("UPDATE listen SET playedMs = playedMs + 60000, endPositionMs = 60000 WHERE endReason = ${EndReason.OPEN}")
                // A queue no longer learned from, or learned from again (ListenDao's learn by queue).
                5 -> longs("SELECT DISTINCT queueId FROM listen").randomOrNull(r)?.let { exec("UPDATE listen SET learn = ${r.nextInt(2)} WHERE queueId = $it") }
                // Forget a session (EngineSql.FORGET_SESSION) or a stretch of time (FORGET_BETWEEN).
                6 -> longs("SELECT DISTINCT sessionId FROM listen").randomOrNull(r)?.let { exec(EngineSql.FORGET_SESSION.replace(":sessionId", "$it")) }
                7 -> ids.randomOrNull(r)?.let { exec(EngineSql.FORGET_BETWEEN.replace(":from", "${clock - 3_600_000}").replace(":to", "$clock")) }
                // A listen taken out on Your data, the song playing among them sometimes.
                8 -> (if (open.isNotEmpty() && r.nextBoolean()) open else ids).randomOrNull(r)?.let { exec("DELETE FROM listen WHERE id = $it") }
                // A song deleted with its listens (the foreign key's cascade).
                9 -> songs.randomOrNull(r)?.let { song -> exec("DELETE FROM song WHERE id = '$song'"); songs.remove(song) }
                // The whole history cleared, then played on.
                10 -> { exec("DELETE FROM listen"); repeat(r.nextInt(0, 4)) { listen(open = false) } }
                // One column of one row read before, whatever writes it: the summary must see each.
                11 -> ids.randomOrNull(r)?.let { id ->
                    val column = listOf(
                        "startedAt = startedAt + 1", "endedAt = endedAt - 7", "playedMs = playedMs + 65479", "durationMs = -1",
                        "endReason = (endReason + 1) % 6", "origin = origin + 1", "autoplayDepth = autoplayDepth + 3",
                        "sessionId = sessionId + 1", "tzOffsetMin = -tzOffsetMin - 60", "learn = 1 - learn", "runId = runId + 99991",
                        "queueId = queueId + 1", "impressionId = COALESCE(impressionId, 0) + 1", "impressionId = NULL",
                        "contextChip = (contextChip + 1) % 6", "continuesListenId = COALESCE(continuesListenId, 0) + 2",
                    ).random(r)
                    exec("UPDATE listen SET $column WHERE id = $id")
                }
                // Two rows trading a value: a count and a plain sum would not see it.
                12 -> if (ids.size >= 2) {
                    val (a, b) = ids.shuffled(r).take(2)
                    exec("UPDATE listen SET playedMs = CASE id WHEN $a THEN (SELECT playedMs FROM listen WHERE id = $b) ELSE (SELECT playedMs FROM listen WHERE id = $a) END WHERE id IN ($a, $b)")
                }
                // A row put back below the top with an id that was free (a backup, an import).
                13 -> {
                    val gap = (1L..(ids.maxOrNull() ?: 0L)).firstOrNull { it !in ids.toSet() }
                    if (gap != null) listen(open = false, id = gap)
                }
                // Columns the engine does not read: nothing it reads has changed.
                14 -> ids.randomOrNull(r)?.let { exec("UPDATE listen SET ratio = 0.9, counted = 1 - counted, endPositionMs = 1234, tappedAt = 5 WHERE id = $it") }
                // Nothing at all.
                else -> Unit
            }
        }

        fun songRows(): Map<String, SongRow> = songs.withIndex().associate { (n, id) ->
            id to SongRow(id, "Song ${n % 9}", artistId = "a${n % 7}", artistName = "Artist ${n % 7}", liked = n % 4 == 0, likedAt = if (n % 4 == 0) clock - n * 86_400_000L else null)
        }
    }

    /** Everything of a taste a radio reads, written out so two tastes compare. */
    private fun fingerprint(t: QueueFit.Taste): String = buildString {
        append(t.listens).append('|').append(t.freshIds.sorted()).append(t.freshKeys.sorted())
        append('|').append(t.habits.base).append(',').append(t.habits.rows)
        for ((id, s) in t.stats.songs.toSortedMap()) {
            append("|$id:${s.activation},${s.oldActivation},${s.bucketActivation},${s.exposure},${s.goodStarts},${s.lastGoodAt},${s.lastStartedAt},${s.listens},${s.goodListens}")
        }
        for ((id, a) in t.stats.artists.toSortedMap()) {
            append("|$id:${a.strength},${a.goodListens},${a.positiveShort},${a.positiveLong},${a.goodByBucket.toList()},${a.goodTagged}")
        }
        append('|').append(t.stats.sessionArtists).append(t.stats.latestSessionId).append(t.stats.goodByBucket.toList()).append(t.stats.goodInContextWindow)
        for (song in t.songs.values.sortedBy { it.id }) append("|${song.id}=${t.habits.of(song.title, song.artistId, song.artistName, song.liked)}")
    }

    private fun taste(listens: List<ListenRow>, songs: Map<String, SongRow>, now: Long): QueueFit.Taste =
        QueueFit.Taste.of(EngineLoader.HeardRows(EngineInput(now, songs, EngineListens.forBuild(listens, now), emptyList()), emptyList(), Weights.PRIORS))

    @Test
    fun `a read of what changed is the full read, over random histories and every kind of write`() {
        var incremental = 0
        var full = 0
        for (case in 0 until 300) {
            exec("DELETE FROM listen")
            exec("DELETE FROM song")
            val r = Random(case)
            val history = History(r)
            repeat(r.nextInt(1, 12)) { history.addSong() }
            repeat(r.nextInt(0, 60)) { history.listen(open = false) }
            if (r.nextBoolean()) history.listen(open = true)
            val log = ListenLog()
            val source = Jdbc()
            var before: List<ListenRow>? = null
            repeat(r.nextInt(3, 14)) { round ->
                if (round > 0) repeat(r.nextInt(1, 4)) { history.change() }
                if (r.nextInt(5) == 0) history.addSong()
                val merged = log.read(source)
                // What the taste read before this branch: EngineSql.LISTENS, one statement.
                val whole = rows(EngineSql.LISTENS)
                assertEquals("case $case, round $round", whole, merged)
                // A full read only where a row read before has changed or gone, or where none was read yet.
                val last = before
                val settledBefore = last?.filter { it.endReason != EndReason.OPEN }
                val top = last?.maxOfOrNull { it.id } ?: 0L
                val settledNow = whole.filter { it.id <= top && settledBefore?.any { b -> b.id == it.id } == true }
                val changed = last == null || settledBefore != settledNow ||
                    whole.any { it.id <= top && last.none { b -> b.id == it.id } }
                assertEquals("case $case, round $round: read in full", changed, log.lastWasFull)
                if (log.lastWasFull) full++ else incremental++
                // The summary in SQL is the summary in Kotlin, on whatever the log holds now.
                val open = whole.filter { it.endReason == EndReason.OPEN }.map { it.id }
                assertEquals(ListenLog.markOf(whole.filter { it.endReason != EndReason.OPEN }), source.mark(whole.maxOfOrNull { it.id } ?: 0L, open))
                before = whole
            }
            // And the taste a radio would take from it: the same as from a full read.
            val songs = history.songRows()
            assertEquals("case $case", fingerprint(taste(rows(EngineSql.LISTENS), songs, history.clock)), fingerprint(taste(log.read(source), songs, history.clock)))
        }
        println("ListenLog: $incremental reads of what changed, $full full reads, all equal to a full read")
        // Both kinds of read, many times each: the writes here change old rows far more often than
        // listening does, so full reads outnumber the others (825 to 1538 on 25 Oct).
        assertTrue("$incremental reads of what changed", incremental >= 300)
        assertTrue("$full full reads", full >= 300)
    }

    @Test
    fun `no write in the app changes the song of a listen, which the summary leaves out`() {
        // The summary has no column for the song: SQLite has no way to fold a string into a number
        // that would see every change of it. That is safe only while nothing changes it, so every
        // UPDATE of the log in SQL and every row copied and written back is held to it here.
        val app = File("src/main/java/com/dd3boh/outertune").walkTopDown().filter { it.extension == "kt" }.joinToString("\n") { it.readText() }
        val sets = Regex("""UPDATE listen SET ([^"]*)""", RegexOption.IGNORE_CASE).findAll(app).map { it.groupValues[1] }.toList()
        assertTrue(sets.toString(), sets.size >= 4)
        for (set in sets) assertFalse("an UPDATE of the log changes the song: $set", "songId" in set)
        val copies = Regex("""update\((\w+)\.copy\(([^()]*)\)\)""").findAll(app).map { it.groupValues[2] }.toList()
        assertTrue("the rows closed by copying the open one", copies.size >= 2)
        for (copy in copies) assertFalse("a row is written back with another song: $copy", "songId" in copy)
        // And the one place the rows are deleted by their song is the song's own foreign key.
        val schema = File("schemas/com.dd3boh.outertune.db.InternalDatabase/$MUSIC_DATABASE_VERSION.json").readText()
        assertTrue("FOREIGN KEY(`songId`) REFERENCES `song`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE" in schema)
    }

    @Test
    fun `fifteen thousand listens and one new song, two rows fetched instead of all of them`() {
        val history = History(Random(7))
        repeat(5_000) { history.addSong() }
        db.autoCommit = false
        repeat(15_000) { history.listen(open = false) }
        history.listen(open = true)
        db.commit()
        db.autoCommit = true
        val log = ListenLog()
        val source = Jdbc()
        val t0 = System.nanoTime()
        val first = log.read(source)
        val fullMs = (System.nanoTime() - t0) / 1e6
        assertTrue(log.lastWasFull)
        assertEquals(15_001, first.size)
        // The song playing ends and the next one starts.
        exec("UPDATE listen SET endReason = ${EndReason.ENDED}, endedAt = endedAt + 1000 WHERE endReason = ${EndReason.OPEN}")
        history.listen(open = true)
        val t1 = System.nanoTime()
        val next = log.read(source)
        val smallMs = (System.nanoTime() - t1) / 1e6
        assertFalse(log.lastWasFull)
        assertEquals("the row that closed and the one that opened", 2, log.lastFetched)
        assertEquals(rows(EngineSql.LISTENS), next)
        val t2 = System.nanoTime()
        val songs = history.songRows()
        taste(next, songs, history.clock)
        val tasteMs = (System.nanoTime() - t2) / 1e6
        println("ListenLog over JDBC: full read of 15,001 rows in %.0f ms, the read of what changed in %.0f ms; the taste made from them in %.0f ms".format(fullMs, smallMs, tasteMs))
    }
}
