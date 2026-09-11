/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.engine

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.constants.PlayOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The row's rules, on a made-up library: whatever the log says, the row never breaks them. These
 * run on every build with no data of anyone's.
 */
class EngineRowTest {
    private val now = 1_789_135_200_000L
    private val hour = 3_600_000L
    private val day = 86_400_000L

    /** A library of [artists] artists with [perArtist] songs each, every song related to every other song of the next artist. */
    private class World(val now: Long) {
        val songs = LinkedHashMap<String, SongRow>()
        val listens = ArrayList<ListenRow>()
        val edges = ArrayList<Edge>()
        var session = 1L
        fun song(id: String, artist: String, title: String = id, liked: Boolean = false, likedAt: Long? = null, inLibrary: Boolean = true) {
            songs[id] = SongRow(id, title, "art_$artist", artist, liked, likedAt, inLibrary)
        }
        fun play(id: String, hoursAgo: Double, ratio: Double = 1.0, origin: PlayOrigin = PlayOrigin.SEARCH, depth: Int = 0, session: Long = this.session, ended: Int = EndReason.ENDED) {
            val start = now - (hoursAgo * 3_600_000).toLong()
            val dur = 200_000L; val played = (dur * ratio).toLong()
            listens += ListenRow(id, start, start + played, played, dur, ended, origin.code, depth, session, 0)
        }
        fun edge(seed: String, vararg to: String) = to.forEach { edges += Edge(seed, it) }
        fun input(bucket: Int = 0, seen: List<SeenCard> = emptyList(), exclusions: List<ExclusionRow> = emptyList()) =
            EngineInput(now, songs, listens, edges, seen = seen, exclusions = exclusions, bucket = bucket)
    }

    private fun bigWorld(): World {
        val w = World(now)
        // Twenty artists, eight songs each, with a live version of the first song of each artist.
        val artists = 20
        for (a in 0 until artists) for (i in 0 until 8) w.song("a${a}s$i", "artist$a", title = "Song $a $i")
        for (a in 0 until artists) w.song("a${a}live", "artist$a", title = "Song $a 0 (Live)")
        // Everyone is related to the next two artists' songs.
        for (a in 0 until artists) for (i in 0 until 8) for (n in 1..2) w.edge("a${a}s$i", *(0 until 8).map { "a${(a + n) % artists}s$it" }.toTypedArray())
        // A history: the first four songs of artists 0 to 7 played over the last month, artists 8 to 11 long ago, 12 to 19 never.
        var sess = 1L
        for (d in 1..30) { sess++; for (a in 0 until 8) w.play("a${a}s${d % 4}", hoursAgo = d * 24.0 + a, session = sess) }
        for (d in 50..70) { sess++; for (a in 8..11) w.play("a${a}s${d % 8}", hoursAgo = d * 24.0 + a, session = sess) }
        // Right now: a session with two songs of artist 0.
        w.play("a0s1", hoursAgo = 0.5, session = 999); w.play("a0s2", hoursAgo = 0.2, session = 999)
        return w
    }

    @Test
    fun `an empty library builds nothing and does not crash`() {
        val row = EngineRow.build(EngineInput(now, emptyMap(), emptyList(), emptyList()), random = Random(1))
        assertTrue(row.cards.isEmpty()); assertTrue(row.seeds.isEmpty())
    }

    @Test
    fun `the row keeps every rule`() {
        val w = bigWorld()
        repeat(20) { attempt ->
            val row = EngineRow.build(w.input(), random = Random(attempt.toLong()))
            assertEquals(20, row.cards.size)
            val groups = VersionGroups(w.songs.values)
            // one card per version group
            assertEquals(row.cards.size, row.cards.map { groups.groupOf(it.songId) }.toSet().size)
            // at most two per artist, one per artist per column
            row.cards.groupingBy { w.songs[it.songId]!!.artistId }.eachCount().values.forEach { assertTrue("artist cap", it <= 2) }
            row.cards.chunked(4).forEach { col -> assertEquals(col.size, col.map { w.songs[it.songId]!!.artistId }.toSet().size) }
            // at most three related cards from one seed
            row.cards.filter { it.lane == Lane.RELATED }.groupingBy { it.seedId }.eachCount().values.forEach { assertTrue("seed cap", it <= 3) }
            // no seed and nothing from the current session in the row
            row.cards.forEach { c -> assertTrue("seed in row", c.songId !in row.seeds); assertTrue("just played", c.songId !in setOf("a0s1", "a0s2")) }
            // the first column spans four lanes when every lane has something
            if (row.quotas.values.all { it > 0 } && row.cards.size >= 4) assertEquals(4, row.cards.take(4).map { it.lane }.toSet().size)
            // probabilities are probabilities
            row.cards.forEach { assertTrue(it.p in 0.0..1.0) }
        }
    }

    @Test
    fun `the dial sets the explore share by largest remainder`() {
        assertEquals(mapOf(Lane.EXPLORE to 2, Lane.RELATED to 9, Lane.ARTIST to 5, Lane.REDISCOVER to 4), quotas(20, 0.15, false))
        assertEquals(20, quotas(20, 1.0, false).values.sum())
        assertEquals(7, quotas(20, 1.0, false)[Lane.EXPLORE])
        assertEquals(1, quotas(20, 0.0, false)[Lane.EXPLORE])
        assertEquals(20, quotas(20, 0.5, true)[Lane.EXPLORE])
    }

    @Test
    fun `a banned song takes its versions with it and a snoozed artist is gone`() {
        val w = bigWorld()
        val banned = w.input(exclusions = listOf(ExclusionRow(1, "a1s0"), ExclusionRow(2, "art_artist2")))
        repeat(10) { attempt ->
            val row = EngineRow.build(banned, random = Random(attempt.toLong()))
            row.cards.forEach { c ->
                assertTrue(c.songId != "a1s0" && c.songId != "a1live")
                assertTrue(w.songs[c.songId]!!.artistId != "art_artist2")
            }
        }
    }

    @Test
    fun `explore cards are new to the listener and say so`() {
        val w = bigWorld()
        val row = EngineRow.build(w.input(), random = Random(3))
        val explore = row.cards.filter { it.lane == Lane.EXPLORE }
        assertTrue(explore.isNotEmpty())
        explore.forEach { c ->
            assertTrue(c.features[Features.NOVEL] >= 0.5)
            assertEquals("new_to_you", c.reasons.first())
        }
    }

    @Test
    fun `the same seed gives the same row`() {
        val w = bigWorld()
        val a = EngineRow.build(w.input(), random = Random(7)).cards.map { it.songId }
        val b = EngineRow.build(w.input(), random = Random(7)).cards.map { it.songId }
        assertEquals(a, b)
    }

    @Test
    fun `a card passed over is demoted and a satiated song seeds weakly`() {
        val w = bigWorld()
        val seen = (1..6).map { SeenCard("a1s3", now - it * day) }
        val plain = EngineRow.build(w.input(), random = Random(11))
        val demoted = EngineRow.build(w.input(seen = seen), random = Random(11))
        val zPlain = (plain.cards + plain.pool).firstOrNull { it.songId == "a1s3" }?.z
        val zDemoted = (demoted.cards + demoted.pool).firstOrNull { it.songId == "a1s3" }?.z
        if (zPlain != null && zDemoted != null) assertTrue(zDemoted < zPlain)
        // Forty full plays of one song in an afternoon: exposure past the ramp.
        val binge = World(now); binge.song("x", "a"); binge.song("y", "a"); binge.edge("x", "y")
        repeat(40) { binge.play("x", hoursAgo = it * 0.1, session = 5) }
        val stats = LibraryStats(binge.input())
        val ctx = Features.Context(stats, VersionGroups(binge.songs.values), binge.input(), emptyMap())
        assertEquals(1.0, Features.of("x", ctx)[Features.SAT], 1e-9)
    }
}
