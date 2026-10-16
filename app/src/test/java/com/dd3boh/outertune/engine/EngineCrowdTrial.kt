/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.random.Random

/**
 * The engine over many listeners, not one: the public Last.fm 1K log, turned by
 * experiments/features/recs-crowd/make_crowd.py into what the engine reads. Gated on
 * `ENGINE_CROWD=<that folder>`, so the ordinary run needs nothing.
 *
 * Every listener is scored at the bake-off's session starts: the row is built from the plays that
 * started before `t`, at the priors, as a new install would build it, and a hit is the session's
 * first song inside the row's twenty cards. A scrobble is a song heard well, so each play is a
 * whole listen of a song of unknown length. Related lists come from other listeners (the bake-off's
 * A half), each pair known from the first time one of them played it; no song has a title, so the
 * tag features say nothing, and nobody has liked anything.
 *
 * `ENGINE_CROWD_ARMS` names leans (AUTO, FAMILIAR, comma separated; default both), or MIX for
 * Familiar first with the mix learned (EngineParams.mixLearned),
 * `ENGINE_CROWD_USERS` keeps that many listeners spread over the list, `ENGINE_CROWD_EVALS` that
 * many session starts a listener, `ENGINE_CROWD_OUT` writes one line per start and arm (listener,
 * t, new, arm, rank or -1, lane of the hit, new cards in the row, cards, new cards the listener
 * went on to play within a week, familiar cards played again within a week) for pairing with the
 * bake-off's rows by listener and start.
 *
 * The week is a second yardstick, for the new cards: a new song is almost never the first of the
 * next session, so that count cannot tell good new cards from bad ones; whether the listener came
 * to play the song in the next seven days, however they found it, can.
 */
class EngineCrowdTrial {
    private class Event(val user: Int, val t: Long, val target: Int, val new: Boolean)

    /**
     * A lean, or MIX (Familiar first with the mix learned), then any changes joined by +: fresh<N>
     * the fresh hours, cap<N> the cards one artist may hold, decay<N> Familiar first's decay in
     * tenths, floor<N> the learned mix's fewest new cards, window<N> the listens it reads. FAMILIAR+cap4 is
     * Familiar first with four cards an artist.
     */
    private class Arm(val name: String) {
        private val parts = name.split('+')
        val lean: Lean = if (parts[0] == "MIX") Lean.FAMILIAR else Lean.valueOf(parts[0])
        val p: EngineParams = parts.drop(1).fold(if (parts[0] == "MIX") EngineParams.DEFAULT.copy(mixLearned = true) else EngineParams.DEFAULT) { p, change ->
            val n = change.dropWhile { it.isLetter() }.toInt()
            when (change.takeWhile { it.isLetter() }) {
                "fresh" -> p.copy(engineFreshHours = n)
                "cap" -> p.copy(maxPerArtist = n)
                "decay" -> p.copy(familiarDecay = n / 10.0)
                "floor" -> p.copy(mixNewMin = n)
                "window" -> p.copy(mixWindowListens = n)
                else -> error("unknown change $change in arm $name")
            }
        }
    }

    private class Tally {
        var events = 0; var hits = 0; var newTargets = 0; var newHits = 0; var newCards = 0L; var cards = 0L; var nanos = 0L
        var newWeek = 0L; var famCards = 0L; var famWeek = 0L
        val perUser = HashMap<Int, IntArray>()   // events, hits
        val laneHits = IntArray(Lane.entries.size)
        val laneCards = IntArray(Lane.entries.size)
    }

    @Test
    fun crowd() {
        val dir = System.getenv("ENGINE_CROWD")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("ENGINE_CROWD not set", dir != null && dir.isDirectory)
        dir!!
        val arms = (System.getenv("ENGINE_CROWD_ARMS")?.takeIf { it.isNotBlank() } ?: "AUTO,FAMILIAR").split(',').map { Arm(it.trim()) }
        val userLimit = System.getenv("ENGINE_CROWD_USERS")?.toIntOrNull()
        val evalLimit = System.getenv("ENGINE_CROWD_EVALS")?.toIntOrNull()
        val out = System.getenv("ENGINE_CROWD_OUT")?.takeIf { it.isNotBlank() }?.let { File(it).printWriter() }
        val started = System.nanoTime()

        val artistOf = readInts(File(dir, "artist.bin"))
        // Related lists, sorted by seed: a seed's rows are seedStart[i] until seedStart[i + 1].
        val edgeFile = File(dir, "edges.bin")
        val nEdges = (edgeFile.length() / 20).toInt()
        val eSeed = IntArray(nEdges); val eRel = IntArray(nEdges); val eKnown = LongArray(nEdges)
        map(edgeFile).let { b -> for (i in 0 until nEdges) { eSeed[i] = b.getInt(); eRel[i] = b.getInt(); eKnown[i] = b.getLong(); b.getInt() } }
        val seedFirst = HashMap<Int, Int>()
        for (i in nEdges - 1 downTo 0) seedFirst[eSeed[i]] = i

        val index = File(dir, "index.tsv").readLines().filter { it.isNotBlank() }.map { l -> l.split('\t').let { Triple(it[0].toInt(), it[1].toLong(), it[2].toInt()) } }
        val events = File(dir, "events.tsv").readLines().filter { it.isNotBlank() }.map { l -> l.split('\t').let { Event(it[0].toInt(), it[1].toLong(), it[2].toInt(), it[3] == "1") } }.groupBy { it.user }
        val listeners = index.let { all -> if (userLimit != null && userLimit < all.size) (0 until userLimit).map { all[(it.toLong() * all.size / userLimit).toInt()] } else all }
        val plays = map(File(dir, "plays.bin"))
        val tallies = arms.associateWith { Tally() }

        for ((user, first, count) in listeners) {
            val ts = LongArray(count); val song = IntArray(count)
            plays.position((first * 16).toInt())
            for (i in 0 until count) { ts[i] = plays.getLong(); song[i] = plays.getInt(); plays.getInt() }
            // One session per run of plays less than half an hour apart, as the bake-off cuts them.
            var session = 0L
            val listens = ArrayList<ListenRow>(count)
            for (i in 0 until count) {
                if (i > 0 && ts[i] - ts[i - 1] > GAP_MS) session++
                listens += ListenRow("s${song[i]}", ts[i], ts[i] + PLAY_MS, PLAY_MS, PLAY_MS, EndReason.ENDED, 0, 0, session, 0)
            }
            // Every play time of each song, for the week after a row.
            val playsOf = HashMap<Int, MutableList<Long>>()
            for (i in 0 until count) playsOf.getOrPut(song[i]) { ArrayList() } += ts[i]
            fun playedWithin(id: Int, from: Long, to: Long): Boolean {
                val times = playsOf[id] ?: return false
                val k = lowerBound(times, from)
                return k < times.size && times[k] < to
            }
            val mine = events[user].orEmpty().let { all -> if (evalLimit != null && evalLimit < all.size) (0 until evalLimit).map { all[(it.toLong() * all.size / evalLimit).toInt()] } else all }
            for (ev in mine) {
                val k = lowerBound(ts, ev.t)
                if (k == 0) continue
                val heard = HashSet<Int>()
                for (i in 0 until k) heard += song[i]
                val songs = HashMap<String, SongRow>(heard.size * 3)
                val edges = ArrayList<Edge>()
                fun songRow(id: Int) = songs.getOrPut("s$id") { SongRow("s$id", "s$id", artistOf.getOrNull(id)?.takeIf { it >= 0 }?.let { "a$it" }, artistOf.getOrNull(id)?.takeIf { it >= 0 }?.let { "a$it" }) }
                for (id in heard) {
                    songRow(id)
                    val from = seedFirst[id] ?: continue
                    var j = from
                    while (j < nEdges && eSeed[j] == id) {
                        if (eKnown[j] <= ev.t) { songRow(eRel[j]); edges += Edge("s$id", "s${eRel[j]}") }
                        j++
                    }
                }
                val input = EngineInput(ev.t, songs, listens.subList(0, k), edges, bucket = dayPartBucket(ev.t, 0), tzOffsetMin = 0)
                val target = "s${ev.target}"
                for (arm in arms) {
                    val tally = tallies.getValue(arm)
                    val t0 = System.nanoTime()
                    val row = EngineRow.build(input, p = arm.p, random = Random(ev.user * 1_000_003L + ev.t), stored = arm.lean)
                    tally.nanos += System.nanoTime() - t0
                    val ids = row.cards.map { it.songId }
                    val rank = ids.indexOf(target)
                    val cardSongs = row.cards.map { it.songId.substring(1).toInt() }
                    val newCards = cardSongs.count { it !in heard }
                    val newWeek = cardSongs.count { it !in heard && playedWithin(it, ev.t, ev.t + WEEK_MS) }
                    val famWeek = cardSongs.count { it in heard && playedWithin(it, ev.t, ev.t + WEEK_MS) }
                    tally.events++; tally.cards += ids.size; tally.newCards += newCards
                    tally.newWeek += newWeek; tally.famCards += ids.size - newCards; tally.famWeek += famWeek
                    if (ev.new) tally.newTargets++
                    val u = tally.perUser.getOrPut(user) { IntArray(2) }
                    u[0]++
                    for (c in row.cards) tally.laneCards[c.lane.ordinal]++
                    if (rank >= 0) {
                        tally.hits++; u[1]++
                        if (ev.new) tally.newHits++
                        tally.laneHits[row.cards[rank].lane.ordinal]++
                    }
                    out?.println("$user\t${ev.t}\t${if (ev.new) 1 else 0}\t${arm.name}\t$rank\t${if (rank >= 0) row.cards[rank].lane.name else "-"}\t$newCards\t${ids.size}\t$newWeek\t$famWeek")
                }
            }
        }
        out?.close()
        val secs = (System.nanoTime() - started) / 1e9
        println("crowd: ${listeners.size} listeners, ${"%.0f".format(secs)} s, ${nEdges} related rows; hit = the session's first song in the row of twenty")
        for ((arm, t) in tallies) {
            val perUser = t.perUser.values.map { it[1].toDouble() / it[0] }
            println("  %-9s hit@20 %.3f over %d starts, mean per listener %.3f, new songs found %d of %d new starts, new cards a row %.1f of %.1f, build %.0f ms".format(
                arm.name, t.hits.toDouble() / t.events, t.events, perUser.average(), t.newHits, t.newTargets, t.newCards.toDouble() / t.events, t.cards.toDouble() / t.events, t.nanos / 1e6 / t.events))
            println("    within a week: %.1f%% of new cards played, %.1f%% of familiar cards played again".format(100.0 * t.newWeek / maxOf(1L, t.newCards), 100.0 * t.famWeek / maxOf(1L, t.famCards)))
            println("    by lane: " + Lane.entries.joinToString(", ") { "${it.name.lowercase()} ${t.laneHits[it.ordinal]} hits of ${t.laneCards[it.ordinal]} cards" })
        }
    }

    private fun map(f: File) = RandomAccessFile(f, "r").use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.length()).order(ByteOrder.LITTLE_ENDIAN) }

    private fun readInts(f: File): IntArray = map(f).let { b -> IntArray((f.length() / 4).toInt()) { b.getInt() } }

    /** The first index whose start is at or after [t]. */
    private fun lowerBound(ts: LongArray, t: Long): Int {
        var lo = 0; var hi = ts.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (ts[mid] < t) lo = mid + 1 else hi = mid }
        return lo
    }

    private fun lowerBound(ts: List<Long>, t: Long): Int {
        var lo = 0; var hi = ts.size
        while (lo < hi) { val mid = (lo + hi) ushr 1; if (ts[mid] < t) lo = mid + 1 else hi = mid }
        return lo
    }

    private companion object {
        const val GAP_MS = 30 * 60_000L
        const val WEEK_MS = 7 * 86_400_000L
        /** A scrobble is a song heard well; its length is unknown, so every play is three minutes and twenty seconds, heard whole. */
        const val PLAY_MS = 200_000L
    }
}
