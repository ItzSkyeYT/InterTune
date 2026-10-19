/*
 * Copyright (C) 2026 InterTune
 *
 * SPDX-License-Identifier: GPL-3.0
 */

package com.dd3boh.outertune.playback

import com.dd3boh.outertune.constants.EndReason
import com.dd3boh.outertune.engine.EngineInput
import com.dd3boh.outertune.engine.EngineLoader
import com.dd3boh.outertune.engine.ListenRow
import com.dd3boh.outertune.engine.SongRow
import com.dd3boh.outertune.engine.SongTags
import com.dd3boh.outertune.engine.TagFit
import com.dd3boh.outertune.engine.Weights
import com.dd3boh.outertune.models.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** How well a song fits a radio right now, in the engine's terms. See QueueFit. */
class QueueFitTest {

    private val now = 1_000L * 86_400_000L

    private fun song(id: String, title: String = "Song $id", artist: String = "Artist $id", liked: Boolean = false) =
        MediaMetadata(id = id, title = title, artists = listOf(MediaMetadata.Artist(null, artist)), duration = 200, genre = null, liked = liked)

    private fun run(vararg kept: String, referrers: Map<String, List<String>> = emptyMap(), skips: Set<String> = emptySet(), exempt: Set<String> = emptySet()) =
        QueueFit.Run(TagFit.context(kept.map { SongTags.of(it) }), referrers, skips, exempt)

    private val empty = QueueFit.Taste.empty(now)

    @Test
    fun `a song on this run's seeds' lists fits better, more so on more of them`() {
        val none = QueueFit.score(song("x"), empty, run()).fit
        val one = QueueFit.score(song("x"), empty, run(referrers = mapOf("x" to listOf("s1")))).fit
        val two = QueueFit.score(song("x"), empty, run(referrers = mapOf("x" to listOf("s1", "s2")))).fit
        assertTrue(none < one && one < two)
        assertEquals("a seed with no history weighs one half: 0.5 / 1.5 of x_seed's weight", 1.2 / 3, one - none, 1e-9)
    }

    @Test
    fun `the words follow the run as it was kept`() {
        val slowedRun = run("A (Slowed)", "B (Slowed)", "C (Slowed)")
        val plainRun = run("A", "B", "C")
        assertTrue(QueueFit.score(song("x", "X (Slowed)"), empty, slowedRun).fit > QueueFit.score(song("y", "Y"), empty, slowedRun).fit)
        assertTrue(QueueFit.score(song("y", "Y"), empty, plainRun).fit > QueueFit.score(song("x", "X (Slowed)"), empty, plainRun).fit)
        assertTrue(QueueFit.score(song("y", "Y"), empty, slowedRun).tagFit < 0)
    }

    @Test
    fun `one skip of the artist in this run costs half a logit`() {
        val plain = QueueFit.score(song("x", artist = "Band A"), empty, run()).fit
        val skipped = QueueFit.score(song("x", artist = "Band A"), empty, run(skips = setOf("band a")))
        assertEquals(-0.5, skipped.fit - plain, 1e-9)
        assertTrue(skipped.oneSkip)
    }

    private fun tasteWithSkips(artist: String, skips: Int): QueueFit.Taste {
        val songs = HashMap<String, SongRow>()
        val listens = ArrayList<ListenRow>()
        var t = now - 86_400_000L
        for (i in 0 until 40) {
            songs["k$i"] = SongRow("k$i", "Kept $i", "kid$i", "Kept artist $i")
            listens += ListenRow("k$i", t, t + 200_000, 200_000, 200_000, EndReason.ENDED, 0, 0, t, 0); t += 1000
        }
        for (i in 0 until skips) {
            songs["c$i"] = SongRow("c$i", "Skipped $i", null, artist)
            listens += ListenRow("c$i", t, t + 60_000, 60_000, 200_000, EndReason.SKIPPED, 0, 0, t, 0); t += 1000
        }
        val input = EngineInput(now, songs, listens, emptyList())
        return QueueFit.Taste.of(EngineLoader.HeardRows(input, emptyList(), Weights.PRIORS))
    }

    @Test
    fun `the lasting habit costs up to a whole logit, and nothing for a liked song or an artist kept this run`() {
        val taste = tasteWithSkips("Band C", 8)
        val by = song("new", artist = "Band C")
        val score = QueueFit.score(by, taste, run())
        assertEquals(1.0, score.habit, 1e-9)
        assertEquals(-1.0, score.fit - QueueFit.score(song("new", artist = "Band Z"), taste, run()).fit, 1e-9)
        assertEquals(0.0, QueueFit.score(song("new", artist = "Band C", liked = true), taste, run()).habit, 1e-9)
        assertEquals(0.0, QueueFit.score(by, taste, run(exempt = setOf("band c"))).habit, 1e-9)
    }

    @Test
    fun `the bias is left out, so the order is the same whatever it is`() {
        val r = run("A", "B", referrers = mapOf("x" to listOf("s1")))
        val low = QueueFit.Taste(now, empty.stats, empty.habits, Weights(mapOf("b" to -5.0)), emptyMap())
        val high = QueueFit.Taste(now, empty.stats, empty.habits, Weights(mapOf("b" to 2.0)), emptyMap())
        assertEquals(QueueFit.score(song("x"), low, r).fit, QueueFit.score(song("x"), high, r).fit, 1e-9)
    }

    @Test
    fun `the learned weights are the ones used`() {
        val r = run(referrers = mapOf("x" to listOf("s1")))
        val strong = QueueFit.Taste(now, empty.stats, empty.habits, Weights(mapOf("x_seed" to 3.0)), emptyMap())
        assertEquals(3.0 / 3, QueueFit.score(song("x"), strong, r).fit - QueueFit.score(song("y"), strong, r).fit, 1e-9)
    }

    @Test
    fun `a ban covers the song, its other uploads by title and artist, and its artist by name`() {
        val taste = QueueFit.Taste(
            now, empty.stats, empty.habits, Weights.PRIORS, emptyMap(),
            bannedSongIds = setOf("b1"), bannedSongKeys = setOf(com.dd3boh.outertune.engine.versionKey("Paper Moon", "The Band")),
            bannedArtists = setOf("banned band"),
        )
        assertTrue(taste.banned(song("b1")))
        assertTrue(taste.banned(song("b2", "Paper Moon (Live)", "The Band")))
        assertTrue(taste.banned(song("b3", artist = "Banned Band")))
        assertTrue(!taste.banned(song("ok")))
    }
}
